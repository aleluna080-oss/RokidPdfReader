package com.alexluna.rokidpdfreader;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/**
 * Visor con zoom y desplazamiento.
 * Admite táctil y también órdenes normalizadas provenientes del seguimiento de manos.
 */
public class ZoomImageView extends ImageView {

    private final Matrix imageMatrix = new Matrix();
    private ScaleGestureDetector scaleDetector;

    private float minScale = 1f;
    private float maxScale = 5f;
    private float currentScale = 1f;

    private float lastX;
    private float lastY;
    private boolean dragging;

    public ZoomImageView(Context context) {
        super(context);
        init(context);
    }

    public ZoomImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ZoomImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setScaleType(ScaleType.MATRIX);

        scaleDetector = new ScaleGestureDetector(
                context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        zoomBy(
                                detector.getScaleFactor(),
                                detector.getFocusX(),
                                detector.getFocusY()
                        );
                        return true;
                    }
                }
        );
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        post(this::fitToScreen);
    }

    public void fitToScreen() {
        Drawable drawable = getDrawable();

        if (drawable == null || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }

        float dw = drawable.getIntrinsicWidth();
        float dh = drawable.getIntrinsicHeight();

        if (dw <= 0 || dh <= 0) {
            return;
        }

        float scale = Math.min(
                getWidth() / dw,
                getHeight() / dh
        );

        float dx = (getWidth() - dw * scale) / 2f;
        float dy = (getHeight() - dh * scale) / 2f;

        imageMatrix.reset();
        imageMatrix.postScale(scale, scale);
        imageMatrix.postTranslate(dx, dy);

        minScale = scale;
        currentScale = scale;
        maxScale = scale * 5f;

        setImageMatrix(imageMatrix);
    }

    public void zoomIn() {
        zoomBy(
                1.25f,
                getWidth() / 2f,
                getHeight() / 2f
        );
    }

    public void zoomOut() {
        zoomBy(
                0.80f,
                getWidth() / 2f,
                getHeight() / 2f
        );
    }

    public void zoomByGesture(
            float factor,
            float normalizedFocusX,
            float normalizedFocusY
    ) {
        float clampedFactor = Math.max(
                0.90f,
                Math.min(1.10f, factor)
        );

        float focusX = normalizedFocusX * getWidth();
        float focusY = normalizedFocusY * getHeight();

        zoomBy(
                clampedFactor,
                focusX,
                focusY
        );
    }

    public void panByGesture(
            float normalizedDx,
            float normalizedDy
    ) {
        // Ganancia para que el movimiento de mano se sienta natural con un FOV pequeño.
        float dx = normalizedDx * getWidth() * 1.9f;
        float dy = normalizedDy * getHeight() * 1.9f;

        panBy(dx, dy);
    }

    public int getZoomPercent() {
        if (minScale <= 0f) {
            return 100;
        }

        return Math.round(
                (currentScale / minScale) * 100f
        );
    }

    public boolean isZoomed() {
        return currentScale > minScale * 1.04f;
    }

    public void zoomBy(
            float factor,
            float focusX,
            float focusY
    ) {
        if (getDrawable() == null) {
            return;
        }

        float target = currentScale * factor;

        if (target < minScale) {
            factor = minScale / currentScale;
            target = minScale;
        } else if (target > maxScale) {
            factor = maxScale / currentScale;
            target = maxScale;
        }

        imageMatrix.postScale(
                factor,
                factor,
                focusX,
                focusY
        );

        currentScale = target;

        constrainTranslation();
        setImageMatrix(imageMatrix);
    }

    private void panBy(float dx, float dy) {
        if (getDrawable() == null) {
            return;
        }

        imageMatrix.postTranslate(dx, dy);
        constrainTranslation();
        setImageMatrix(imageMatrix);
    }

    private void constrainTranslation() {
        Drawable drawable = getDrawable();

        if (drawable == null) {
            return;
        }

        RectF rect = new RectF(
                0,
                0,
                drawable.getIntrinsicWidth(),
                drawable.getIntrinsicHeight()
        );

        imageMatrix.mapRect(rect);

        float dx = 0f;
        float dy = 0f;

        if (rect.width() <= getWidth()) {
            dx = getWidth() / 2f - rect.centerX();
        } else if (rect.left > 0) {
            dx = -rect.left;
        } else if (rect.right < getWidth()) {
            dx = getWidth() - rect.right;
        }

        if (rect.height() <= getHeight()) {
            dy = getHeight() / 2f - rect.centerY();
        } else if (rect.top > 0) {
            dy = -rect.top;
        } else if (rect.bottom < getHeight()) {
            dy = getHeight() - rect.bottom;
        }

        imageMatrix.postTranslate(dx, dy);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX();
                lastY = event.getY();
                dragging = true;
                return true;

            case MotionEvent.ACTION_MOVE:
                if (dragging && !scaleDetector.isInProgress()) {
                    float dx = event.getX() - lastX;
                    float dy = event.getY() - lastY;

                    panBy(dx, dy);

                    lastX = event.getX();
                    lastY = event.getY();
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                return true;

            default:
                return true;
        }
    }
}
