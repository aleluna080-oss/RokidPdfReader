package com.alexluna.rokidpdfreader;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;

public class MainActivity extends ComponentActivity {

    private static final int TRANSFER_PORT = 8787;

    private LinearLayout libraryView;
    private LinearLayout viewerView;
    private LinearLayout transferView;
    private LinearLayout documentList;

    private TextView libraryHint;
    private TextView fileNameText;
    private TextView pageText;
    private TextView transferUrlText;
    private TextView transferStatusText;

    private ImageView qrImage;
    private ZoomImageView pdfImage;

    private Button prevButton;
    private Button nextButton;

    private File pdfDirectory;
    private PdfTransferServer transferServer;

    private ParcelFileDescriptor currentDescriptor;
    private PdfRenderer pdfRenderer;
    private PdfRenderer.Page currentPage;
    private Bitmap currentBitmap;
    private int currentPageIndex = 0;

    private final ActivityResultLauncher<String[]> openPdfLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri == null) return;

                        try {
                            getContentResolver().takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            );
                        } catch (SecurityException ignored) {
                        }

                        importUri(uri, true);
                    }
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        pdfDirectory = new File(getFilesDir(), "pdfs");

        if (!pdfDirectory.exists() && !pdfDirectory.mkdirs()) {
            Toast.makeText(
                    this,
                    "No se pudo crear la biblioteca",
                    Toast.LENGTH_LONG
            ).show();
        }

        libraryView = findViewById(R.id.libraryView);
        viewerView = findViewById(R.id.viewerView);
        transferView = findViewById(R.id.transferView);
        documentList = findViewById(R.id.documentList);

        libraryHint = findViewById(R.id.libraryHint);
        fileNameText = findViewById(R.id.fileNameText);
        pageText = findViewById(R.id.pageText);
        transferUrlText = findViewById(R.id.transferUrlText);
        transferStatusText = findViewById(R.id.transferStatusText);

        qrImage = findViewById(R.id.qrImage);
        pdfImage = findViewById(R.id.pdfImage);

        Button receiveButton = findViewById(R.id.receiveButton);
        Button importButton = findViewById(R.id.importButton);
        Button closeTransferButton = findViewById(R.id.closeTransferButton);
        Button backToLibraryButton = findViewById(R.id.backToLibraryButton);

        prevButton = findViewById(R.id.prevButton);
        nextButton = findViewById(R.id.nextButton);

        Button zoomOutButton = findViewById(R.id.zoomOutButton);
        Button fitButton = findViewById(R.id.fitButton);
        Button zoomInButton = findViewById(R.id.zoomInButton);

        receiveButton.setOnClickListener(v -> showTransferMode());

        importButton.setOnClickListener(v ->
                openPdfLauncher.launch(new String[]{"application/pdf"}));

        closeTransferButton.setOnClickListener(v -> showLibrary());
        backToLibraryButton.setOnClickListener(v -> showLibrary());

        prevButton.setOnClickListener(v -> previousPage());
        nextButton.setOnClickListener(v -> nextPage());

        zoomOutButton.setOnClickListener(v -> pdfImage.zoomOut());
        zoomInButton.setOnClickListener(v -> pdfImage.zoomIn());
        fitButton.setOnClickListener(v -> pdfImage.fitToScreen());

        getOnBackPressedDispatcher().addCallback(
                this,
                new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        if (viewerView.getVisibility() == View.VISIBLE
                                || transferView.getVisibility() == View.VISIBLE) {
                            showLibrary();
                        } else {
                            finish();
                        }
                    }
                }
        );

        refreshLibrary();
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void showTransferMode() {
        closeDocument();
        stopTransferServer();

        libraryView.setVisibility(View.GONE);
        viewerView.setVisibility(View.GONE);
        transferView.setVisibility(View.VISIBLE);

        qrImage.setImageDrawable(null);
        transferUrlText.setText("");
        transferStatusText.setText("Buscando la red local…");

        String ip = findLocalIpv4Address();

        if (ip == null) {
            transferStatusText.setText(
                    "No se encontró una red local.\n" +
                    "Conecta las Rokid a la misma Wi‑Fi que tu teléfono o al hotspot del teléfono."
            );
            return;
        }

        String url = "http://" + ip + ":" + TRANSFER_PORT + "/";

        try {
            transferServer = new PdfTransferServer(
                    TRANSFER_PORT,
                    pdfDirectory,
                    file -> runOnUiThread(() -> {
                        refreshLibrary();
                        transferStatusText.setText(
                                "✓ Recibido: " + file.getName() +
                                "\nPuedes enviar otro PDF o volver a la biblioteca."
                        );
                        Toast.makeText(
                                this,
                                "PDF recibido: " + file.getName(),
                                Toast.LENGTH_LONG
                        ).show();
                    })
            );

            transferServer.start(10_000, false);

            Bitmap qr = QrCodeUtils.create(url, 500);
            qrImage.setImageBitmap(qr);
            transferUrlText.setText(url);
            transferStatusText.setText(
                    "Servidor activo · esperando PDF\n" +
                    "Escanea el QR con la cámara de tu teléfono."
            );

        } catch (Exception e) {
            transferStatusText.setText(
                    "No se pudo iniciar la transferencia: " + safeMessage(e)
            );
            stopTransferServer();
        }
    }

    private void stopTransferServer() {
        if (transferServer != null) {
            transferServer.stop();
            transferServer = null;
        }
    }

    private String findLocalIpv4Address() {
        try {
            Enumeration<NetworkInterface> interfaces =
                    NetworkInterface.getNetworkInterfaces();

            String fallback = null;

            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();

                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }

                Enumeration<InetAddress> addresses =
                        network.getInetAddresses();

                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();

                    if (!(address instanceof Inet4Address)
                            || address.isLoopbackAddress()) {
                        continue;
                    }

                    String hostAddress = address.getHostAddress();

                    if (address.isSiteLocalAddress()) {
                        return hostAddress;
                    }

                    if (fallback == null) {
                        fallback = hostAddress;
                    }
                }
            }

            return fallback;

        } catch (Exception ignored) {
            return null;
        }
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();

        if (Intent.ACTION_VIEW.equals(action)) {
            Uri uri = intent.getData();

            if (uri != null) {
                importUri(uri, true);
            }

            return;
        }

        if (Intent.ACTION_SEND.equals(action)
                && "application/pdf".equals(intent.getType())) {

            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);

            if (uri != null) {
                importUri(uri, true);
                return;
            }

            ClipData clipData = intent.getClipData();

            if (clipData != null && clipData.getItemCount() > 0) {
                Uri clipUri = clipData.getItemAt(0).getUri();

                if (clipUri != null) {
                    importUri(clipUri, true);
                }
            }
        }
    }

    private void importUri(Uri uri, boolean openAfterImport) {
        try {
            String sourceName = queryDisplayName(uri);

            if (sourceName == null || sourceName.trim().isEmpty()) {
                sourceName = "documento.pdf";
            }

            if (!sourceName.toLowerCase().endsWith(".pdf")) {
                sourceName += ".pdf";
            }

            sourceName = sanitizeFileName(sourceName);
            File destination = uniqueDestination(sourceName);

            ContentResolver resolver = getContentResolver();

            try (InputStream input = resolver.openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(destination)) {

                if (input == null) {
                    throw new IllegalStateException(
                            "No se pudo abrir el archivo"
                    );
                }

                byte[] buffer = new byte[32 * 1024];
                int read;

                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            }

            refreshLibrary();

            if (openAfterImport) {
                openPdf(destination);
            } else {
                Toast.makeText(
                        this,
                        "PDF importado",
                        Toast.LENGTH_SHORT
                ).show();
            }

        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "No se pudo importar el PDF: " + safeMessage(e),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private String queryDisplayName(Uri uri) {
        Cursor cursor = null;

        try {
            cursor = getContentResolver().query(
                    uri,
                    new String[]{OpenableColumns.DISPLAY_NAME},
                    null,
                    null,
                    null
            );

            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(
                        OpenableColumns.DISPLAY_NAME
                );

                if (index >= 0) {
                    return cursor.getString(index);
                }
            }

        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        String last = uri.getLastPathSegment();

        return last == null
                ? "documento.pdf"
                : last;
    }

    private String sanitizeFileName(String name) {
        String clean = name
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .trim();

        return clean.isEmpty()
                ? "documento.pdf"
                : clean;
    }

    private File uniqueDestination(String fileName) {
        File target = new File(pdfDirectory, fileName);

        if (!target.exists()) {
            return target;
        }

        int dot = fileName.toLowerCase().lastIndexOf(".pdf");
        String base = dot > 0
                ? fileName.substring(0, dot)
                : fileName;

        int i = 2;

        while (true) {
            File candidate = new File(
                    pdfDirectory,
                    base + " (" + i + ").pdf"
            );

            if (!candidate.exists()) {
                return candidate;
            }

            i++;
        }
    }

    private void refreshLibrary() {
        documentList.removeAllViews();

        File[] files = pdfDirectory.listFiles(
                file -> file.isFile()
                        && file.getName().toLowerCase().endsWith(".pdf")
        );

        if (files == null || files.length == 0) {
            libraryHint.setText(
                    "No hay documentos todavía.\n" +
                    "Pulsa RECIBIR PDF DESDE TELÉFONO."
            );
            return;
        }

        Arrays.sort(
                files,
                Comparator.comparingLong(File::lastModified).reversed()
        );

        libraryHint.setText(
                files.length +
                (files.length == 1
                        ? " documento guardado"
                        : " documentos guardados")
        );

        for (File file : files) {
            Button button = new Button(this);
            button.setAllCaps(false);
            button.setText("▣  " + file.getName());
            button.setTextSize(14f);
            button.setOnClickListener(v -> openPdf(file));

            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );

            params.setMargins(0, 4, 0, 4);
            documentList.addView(button, params);
        }
    }

    private void openPdf(File file) {
        stopTransferServer();
        closeDocument();

        try {
            currentDescriptor = ParcelFileDescriptor.open(
                    file,
                    ParcelFileDescriptor.MODE_READ_ONLY
            );

            pdfRenderer = new PdfRenderer(currentDescriptor);

            if (pdfRenderer.getPageCount() <= 0) {
                throw new IllegalStateException(
                        "El PDF no contiene páginas"
                );
            }

            currentPageIndex = 0;

            libraryView.setVisibility(View.GONE);
            transferView.setVisibility(View.GONE);
            viewerView.setVisibility(View.VISIBLE);

            fileNameText.setText(file.getName());
            renderCurrentPage();

        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "No se pudo abrir el PDF: " + safeMessage(e),
                    Toast.LENGTH_LONG
            ).show();

            closeDocument();
            showLibrary();
        }
    }

    private void renderCurrentPage() {
        if (pdfRenderer == null) return;

        closeCurrentPage();

        try {
            currentPage = pdfRenderer.openPage(currentPageIndex);

            int screenWidth =
                    getResources().getDisplayMetrics().widthPixels;

            int desiredWidth = Math.max(
                    screenWidth * 2,
                    1000
            );

            desiredWidth = Math.min(
                    desiredWidth,
                    1800
            );

            float ratio =
                    currentPage.getHeight()
                            / (float) currentPage.getWidth();

            int desiredHeight = Math.max(
                    1,
                    Math.round(desiredWidth * ratio)
            );

            currentBitmap = Bitmap.createBitmap(
                    desiredWidth,
                    desiredHeight,
                    Bitmap.Config.ARGB_8888
            );

            currentBitmap.eraseColor(Color.WHITE);

            currentPage.render(
                    currentBitmap,
                    null,
                    null,
                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
            );

            pdfImage.setImageBitmap(currentBitmap);
            pdfImage.post(pdfImage::fitToScreen);

            pageText.setText(
                    (currentPageIndex + 1)
                            + " / "
                            + pdfRenderer.getPageCount()
            );

            prevButton.setEnabled(currentPageIndex > 0);

            nextButton.setEnabled(
                    currentPageIndex
                            < pdfRenderer.getPageCount() - 1
            );

        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "No se pudo renderizar la página: "
                            + safeMessage(e),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void previousPage() {
        if (pdfRenderer == null
                || currentPageIndex <= 0) {
            return;
        }

        currentPageIndex--;
        renderCurrentPage();
    }

    private void nextPage() {
        if (pdfRenderer == null
                || currentPageIndex
                >= pdfRenderer.getPageCount() - 1) {
            return;
        }

        currentPageIndex++;
        renderCurrentPage();
    }

    private void showLibrary() {
        stopTransferServer();
        closeDocument();

        transferView.setVisibility(View.GONE);
        viewerView.setVisibility(View.GONE);
        libraryView.setVisibility(View.VISIBLE);

        refreshLibrary();
    }

    private void closeCurrentPage() {
        if (currentPage != null) {
            currentPage.close();
            currentPage = null;
        }

        if (currentBitmap != null
                && !currentBitmap.isRecycled()) {
            currentBitmap.recycle();
            currentBitmap = null;
        }

        pdfImage.setImageDrawable(null);
    }

    private void closeDocument() {
        closeCurrentPage();

        if (pdfRenderer != null) {
            pdfRenderer.close();
            pdfRenderer = null;
        }

        if (currentDescriptor != null) {
            try {
                currentDescriptor.close();
            } catch (Exception ignored) {
            }

            currentDescriptor = null;
        }
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();

        if (message == null || message.trim().isEmpty()) {
            return throwable.getClass().getSimpleName();
        }

        return message;
    }

    @Override
    protected void onDestroy() {
        stopTransferServer();
        closeDocument();
        super.onDestroy();
    }
}
