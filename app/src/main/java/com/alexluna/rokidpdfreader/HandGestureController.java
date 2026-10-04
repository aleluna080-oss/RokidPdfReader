package com.alexluna.rokidpdfreader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer;
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CameraX + MediaPipe hand gesture input for the PDF reader.
 *
 * No camera preview is rendered and no frame is written to disk.
 * Frames are used only for on-device inference.
 */
public final class HandGestureController {

    public interface Listener {
        void onNextPage();
        void onPreviousPage();
        void onPan(float normalizedDx, float normalizedDy);
        void onZoom(float factor, float focusX, float focusY);
        void onFitPage();
        void onBackToLibrary();
        void onStatus(String text);
    }

    private static final String MODEL_ASSET =
            "gesture_recognizer.task";

    private static final long FRAME_INTERVAL_MS = 100L;
    private static final long ACTION_COOLDOWN_MS = 700L;
    private static final long FIST_HOLD_MS = 900L;

    private static final float PINCH_THRESHOLD = 0.060f;
    private static final float SWIPE_THRESHOLD = 0.17f;
    private static final float GESTURE_MIN_SCORE = 0.52f;

    private final Context context;
    private final LifecycleOwner lifecycleOwner;
    private final Listener listener;
    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private final ExecutorService cameraExecutor =
            Executors.newSingleThreadExecutor();

    private GestureRecognizer gestureRecognizer;
    private ProcessCameraProvider cameraProvider;
    private ImageAnalysis imageAnalysis;

    private volatile boolean active;
    private volatile boolean destroyed;
    private boolean cameraBound;

    private long lastFrameAt;
    private long lastActionAt;

    // One-hand open-palm swipe.
    private boolean swipeTracking;
    private float swipeStartX;
    private long swipeStartAt;

    // One-hand pinch pan.
    private boolean pinchTracking;
    private float lastPinchX;
    private float lastPinchY;

    // Two-hand zoom.
    private float lastTwoHandDistance = -1f;

    // Hold closed fist to leave viewer.
    private long fistStartedAt;

    private String lastStatus = "";

    public HandGestureController(
            Context context,
            LifecycleOwner lifecycleOwner,
            Listener listener
    ) {
        this.context = context.getApplicationContext();
        this.lifecycleOwner = lifecycleOwner;
        this.listener = listener;
    }

    public void start() {
        if (destroyed) {
            return;
        }

        active = true;
        resetGestureState();

        if (gestureRecognizer == null) {
            if (!createRecognizer()) {
                active = false;
                return;
            }
        }

        if (!cameraBound) {
            bindCamera();
        } else {
            status("Gestos activos · muestra una mano");
        }
    }

    public void stop() {
        active = false;
        resetGestureState();

        if (imageAnalysis != null) {
            imageAnalysis.clearAnalyzer();
            imageAnalysis = null;
        }

        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }

        cameraBound = false;
    }

    public void close() {
        destroyed = true;
        stop();

        if (gestureRecognizer != null) {
            gestureRecognizer.close();
            gestureRecognizer = null;
        }

        cameraExecutor.shutdown();
    }

    private boolean createRecognizer() {
        status("Gestos · cargando modelo…");

        try {
            BaseOptions baseOptions =
                    BaseOptions.builder()
                            .setModelAssetPath(MODEL_ASSET)
                            .build();

            GestureRecognizer.GestureRecognizerOptions options =
                    GestureRecognizer.GestureRecognizerOptions
                            .builder()
                            .setBaseOptions(baseOptions)
                            .setRunningMode(RunningMode.LIVE_STREAM)
                            .setNumHands(2)
                            .setMinHandDetectionConfidence(0.45f)
                            .setMinHandPresenceConfidence(0.45f)
                            .setMinTrackingConfidence(0.45f)
                            .setResultListener(
                                    (result, input) ->
                                            handleResult(result)
                            )
                            .setErrorListener(
                                    error ->
                                            status(
                                                    "Gestos · error: "
                                                            + safeMessage(error)
                                            )
                            )
                            .build();

            gestureRecognizer =
                    GestureRecognizer.createFromOptions(
                            context,
                            options
                    );

            return true;

        } catch (Exception error) {
            status(
                    "Gestos no disponibles: "
                            + safeMessage(error)
            );
            return false;
        }
    }

    private void bindCamera() {
        status("Gestos · iniciando cámara…");

        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(context);

        future.addListener(
                () -> {
                    if (!active || destroyed) {
                        return;
                    }

                    try {
                        cameraProvider = future.get();

                        imageAnalysis =
                                new ImageAnalysis.Builder()
                                        .setTargetResolution(
                                                new Size(640, 480)
                                        )
                                        .setOutputImageFormat(
                                                ImageAnalysis
                                                        .OUTPUT_IMAGE_FORMAT_RGBA_8888
                                        )
                                        .setBackpressureStrategy(
                                                ImageAnalysis
                                                        .STRATEGY_KEEP_ONLY_LATEST
                                        )
                                        .build();

                        imageAnalysis.setAnalyzer(
                                cameraExecutor,
                                this::analyzeFrame
                        );

                        cameraProvider.unbindAll();

                        CameraSelector selector =
                                CameraSelector.DEFAULT_BACK_CAMERA;

                        if (!cameraProvider.hasCamera(selector)) {
                            if (cameraProvider
                                    .getAvailableCameraInfos()
                                    .isEmpty()) {
                                throw new IllegalStateException(
                                        "No hay cámara accesible"
                                );
                            }

                            // Algunas gafas reportan la cámara mundial con lens-facing no estándar.
                            selector =
                                    new CameraSelector.Builder()
                                            .addCameraFilter(
                                                    infos ->
                                                            java.util.Collections
                                                                    .singletonList(
                                                                            infos.get(0)
                                                                    )
                                            )
                                            .build();
                        }

                        cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                selector,
                                imageAnalysis
                        );

                        cameraBound = true;
                        status(
                                "Gestos activos · muestra una mano"
                        );

                    } catch (Exception error) {
                        cameraBound = false;
                        status(
                                "Gestos · cámara no disponible: "
                                        + safeMessage(error)
                        );
                    }
                },
                ContextCompat.getMainExecutor(context)
        );
    }

    private void analyzeFrame(@NonNull ImageProxy imageProxy) {
        long now = SystemClock.uptimeMillis();

        if (!active
                || destroyed
                || gestureRecognizer == null
                || now - lastFrameAt < FRAME_INTERVAL_MS) {
            imageProxy.close();
            return;
        }

        lastFrameAt = now;

        Bitmap sourceBitmap = null;

        try {
            sourceBitmap =
                    Bitmap.createBitmap(
                            imageProxy.getWidth(),
                            imageProxy.getHeight(),
                            Bitmap.Config.ARGB_8888
                    );

            ByteBuffer buffer =
                    imageProxy.getPlanes()[0]
                            .getBuffer();

            buffer.rewind();
            sourceBitmap.copyPixelsFromBuffer(buffer);

            int rotation =
                    imageProxy.getImageInfo()
                            .getRotationDegrees();

            Matrix matrix = new Matrix();
            matrix.postRotate(rotation);

            Bitmap rotatedBitmap =
                    Bitmap.createBitmap(
                            sourceBitmap,
                            0,
                            0,
                            sourceBitmap.getWidth(),
                            sourceBitmap.getHeight(),
                            matrix,
                            true
                    );

            MPImage mpImage =
                    new BitmapImageBuilder(
                            rotatedBitmap
                    ).build();

            gestureRecognizer.recognizeAsync(
                    mpImage,
                    now
            );

        } catch (Exception error) {
            status(
                    "Gestos · análisis: "
                            + safeMessage(error)
            );
        } finally {
            imageProxy.close();
        }
    }

    private void handleResult(
            GestureRecognizerResult result
    ) {
        if (!active || destroyed) {
            return;
        }

        List<List<NormalizedLandmark>> hands =
                result.landmarks();

        if (hands == null || hands.isEmpty()) {
            resetTransientTracking();
            status(
                    "Gestos activos · no veo una mano"
            );
            return;
        }

        long now = SystemClock.uptimeMillis();

        if (hands.size() >= 2
                && isGesture(
                        result,
                        0,
                        "Open_Palm",
                        0.42f
                )
                && isGesture(
                        result,
                        1,
                        "Open_Palm",
                        0.42f
                )) {

            handleTwoHandZoom(
                    hands.get(0),
                    hands.get(1)
            );

            pinchTracking = false;
            swipeTracking = false;
            fistStartedAt = 0L;

            status(
                    "2 manos · separa/junta para zoom"
            );
            return;
        }

        lastTwoHandDistance = -1f;

        List<NormalizedLandmark> hand =
                hands.get(0);

        if (hand.size() < 21) {
            resetTransientTracking();
            return;
        }

        float pinchDistance =
                distance(
                        hand.get(4),
                        hand.get(8)
                );

        // Pinch has priority over canned gestures.
        if (pinchDistance < PINCH_THRESHOLD) {
            handlePinchPan(hand);
            swipeTracking = false;
            fistStartedAt = 0L;

            status(
                    "🤏 Pinza · mueve la página"
            );
            return;
        }

        pinchTracking = false;

        if (isGesture(
                result,
                0,
                "Thumb_Up",
                0.60f
        )) {
            swipeTracking = false;
            fistStartedAt = 0L;

            if (now - lastActionAt
                    >= ACTION_COOLDOWN_MS) {
                lastActionAt = now;

                mainHandler.post(
                        listener::onFitPage
                );

                status(
                        "👍 Página ajustada"
                );
            }

            return;
        }

        if (isGesture(
                result,
                0,
                "Closed_Fist",
                0.60f
        )) {
            swipeTracking = false;

            if (fistStartedAt == 0L) {
                fistStartedAt = now;
            }

            long held =
                    now - fistStartedAt;

            if (held >= FIST_HOLD_MS
                    && now - lastActionAt
                    >= ACTION_COOLDOWN_MS) {

                lastActionAt = now;
                fistStartedAt = 0L;

                mainHandler.post(
                        listener::onBackToLibrary
                );

                status(
                        "✊ Volviendo a biblioteca"
                );
            } else {
                status(
                        "✊ Mantén el puño para volver"
                );
            }

            return;
        }

        fistStartedAt = 0L;

        if (isGesture(
                result,
                0,
                "Open_Palm",
                GESTURE_MIN_SCORE
        )) {
            handleOpenPalmSwipe(
                    hand,
                    now
            );
            return;
        }

        swipeTracking = false;

        String gesture =
                topGestureName(
                        result,
                        0
                );

        if (gesture == null
                || "None".equals(gesture)) {
            status(
                    "1 mano · abre la palma para pasar página"
            );
        } else {
            status(
                    "1 mano · " + spanishGesture(gesture)
            );
        }
    }

    private void handleOpenPalmSwipe(
            List<NormalizedLandmark> hand,
            long now
    ) {
        float x = palmCenterX(hand);

        if (!swipeTracking) {
            swipeTracking = true;
            swipeStartX = x;
            swipeStartAt = now;

            status(
                    "✋ Palma · desliza izquierda/derecha"
            );
            return;
        }

        long elapsed =
                now - swipeStartAt;

        if (elapsed > 1400L) {
            swipeStartX = x;
            swipeStartAt = now;
            return;
        }

        float dx =
                x - swipeStartX;

        if (Math.abs(dx)
                < SWIPE_THRESHOLD) {
            return;
        }

        if (now - lastActionAt
                < ACTION_COOLDOWN_MS) {
            return;
        }

        lastActionAt = now;
        swipeTracking = false;

        if (dx < 0f) {
            mainHandler.post(
                    listener::onNextPage
            );

            status(
                    "✋ Página siguiente"
            );
        } else {
            mainHandler.post(
                    listener::onPreviousPage
            );

            status(
                    "✋ Página anterior"
            );
        }
    }

    private void handlePinchPan(
            List<NormalizedLandmark> hand
    ) {
        NormalizedLandmark thumb =
                hand.get(4);

        NormalizedLandmark index =
                hand.get(8);

        float x =
                (thumb.x() + index.x()) / 2f;

        float y =
                (thumb.y() + index.y()) / 2f;

        if (pinchTracking) {
            float dx = x - lastPinchX;
            float dy = y - lastPinchY;

            if (Math.abs(dx) < 0.12f
                    && Math.abs(dy) < 0.12f) {
                mainHandler.post(
                        () -> listener.onPan(
                                dx,
                                dy
                        )
                );
            }
        }

        lastPinchX = x;
        lastPinchY = y;
        pinchTracking = true;
    }

    private void handleTwoHandZoom(
            List<NormalizedLandmark> first,
            List<NormalizedLandmark> second
    ) {
        float firstX = palmCenterX(first);
        float firstY = palmCenterY(first);
        float secondX = palmCenterX(second);
        float secondY = palmCenterY(second);

        float dx = firstX - secondX;
        float dy = firstY - secondY;

        float distance =
                (float) Math.sqrt(
                        dx * dx + dy * dy
                );

        float focusX =
                (firstX + secondX) / 2f;

        float focusY =
                (firstY + secondY) / 2f;

        if (lastTwoHandDistance > 0f) {
            float factor =
                    distance
                            / lastTwoHandDistance;

            if (factor > 1.025f
                    || factor < 0.975f) {

                float safeFactor =
                        Math.max(
                                0.92f,
                                Math.min(
                                        1.08f,
                                        factor
                                )
                        );

                mainHandler.post(
                        () -> listener.onZoom(
                                safeFactor,
                                focusX,
                                focusY
                        )
                );
            }
        }

        lastTwoHandDistance = distance;
    }

    private boolean isGesture(
            GestureRecognizerResult result,
            int handIndex,
            String expected,
            float minScore
    ) {
        if (result.gestures() == null
                || handIndex < 0
                || handIndex >= result.gestures().size()
                || result.gestures().get(handIndex).isEmpty()) {
            return false;
        }

        Category category =
                result.gestures()
                        .get(handIndex)
                        .get(0);

        return expected.equals(
                category.categoryName()
        )
                && category.score()
                >= minScore;
    }

    private String topGestureName(
            GestureRecognizerResult result,
            int handIndex
    ) {
        if (result.gestures() == null
                || handIndex < 0
                || handIndex >= result.gestures().size()
                || result.gestures().get(handIndex).isEmpty()) {
            return null;
        }

        return result.gestures()
                .get(handIndex)
                .get(0)
                .categoryName();
    }

    private float palmCenterX(
            List<NormalizedLandmark> hand
    ) {
        return (
                hand.get(0).x()
                        + hand.get(5).x()
                        + hand.get(9).x()
                        + hand.get(13).x()
                        + hand.get(17).x()
        ) / 5f;
    }

    private float palmCenterY(
            List<NormalizedLandmark> hand
    ) {
        return (
                hand.get(0).y()
                        + hand.get(5).y()
                        + hand.get(9).y()
                        + hand.get(13).y()
                        + hand.get(17).y()
        ) / 5f;
    }

    private float distance(
            NormalizedLandmark a,
            NormalizedLandmark b
    ) {
        float dx = a.x() - b.x();
        float dy = a.y() - b.y();

        return (float) Math.sqrt(
                dx * dx + dy * dy
        );
    }

    private void resetGestureState() {
        lastActionAt = 0L;
        resetTransientTracking();
    }

    private void resetTransientTracking() {
        swipeTracking = false;
        pinchTracking = false;
        lastTwoHandDistance = -1f;
        fistStartedAt = 0L;
    }

    private void status(String text) {
        if (text == null
                || text.equals(lastStatus)) {
            return;
        }

        lastStatus = text;

        mainHandler.post(
                () -> listener.onStatus(text)
        );
    }

    private String spanishGesture(String gesture) {
        switch (gesture) {
            case "Open_Palm":
                return "palma abierta";
            case "Closed_Fist":
                return "puño";
            case "Pointing_Up":
                return "índice";
            case "Thumb_Up":
                return "pulgar arriba";
            case "Thumb_Down":
                return "pulgar abajo";
            case "Victory":
                return "victoria";
            case "ILoveYou":
                return "gesto detectado";
            default:
                return "mano detectada";
        }
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();

        if (message == null
                || message.trim().isEmpty()) {
            return throwable
                    .getClass()
                    .getSimpleName();
        }

        return message;
    }
}
