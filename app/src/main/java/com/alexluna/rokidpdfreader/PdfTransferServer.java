package com.alexluna.rokidpdfreader;

import fi.iki.elonen.NanoHTTPD;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public final class PdfTransferServer extends NanoHTTPD {

    public interface Listener {
        void onPdfReceived(File file);
    }

    private final File pdfDirectory;
    private final Listener listener;

    public PdfTransferServer(int port, File pdfDirectory, Listener listener) {
        super(port);
        this.pdfDirectory = pdfDirectory;
        this.listener = listener;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();

        if (Method.GET.equals(session.getMethod())
                && ("/".equals(uri) || "/index.html".equals(uri))) {
            return html(Response.Status.OK, uploadPage(""));
        }

        if (Method.POST.equals(session.getMethod())
                && "/upload".equals(uri)) {
            return handleUpload(session);
        }

        return html(
                Response.Status.NOT_FOUND,
                page("No encontrado", "<p>Ruta no encontrada.</p>")
        );
    }

    private Response handleUpload(IHTTPSession session) {
        Map<String, String> files = new HashMap<>();

        try {
            session.parseBody(files);

            String tempPath = files.get("pdf");
            String originalName = session.getParms().get("pdf");

            if (tempPath == null) {
                return html(
                        Response.Status.BAD_REQUEST,
                        uploadPage("No se recibió ningún archivo.")
                );
            }

            if (originalName == null || originalName.trim().isEmpty()) {
                originalName = "documento.pdf";
            }

            originalName = sanitizeFileName(originalName);

            if (!originalName.toLowerCase().endsWith(".pdf")) {
                return html(
                        Response.Status.BAD_REQUEST,
                        uploadPage("El archivo debe tener extensión PDF.")
                );
            }

            File tempFile = new File(tempPath);

            if (!looksLikePdf(tempFile)) {
                return html(
                        Response.Status.BAD_REQUEST,
                        uploadPage("El archivo recibido no parece ser un PDF válido.")
                );
            }

            File destination = uniqueDestination(originalName);
            copyFile(tempFile, destination);

            if (listener != null) {
                listener.onPdfReceived(destination);
            }

            String body =
                    "<div class='ok'>✓ PDF recibido correctamente</div>" +
                    "<p><b>" + escapeHtml(destination.getName()) + "</b></p>" +
                    "<p>Ya aparece en la biblioteca de las Rokid.</p>" +
                    "<a class='button' href='/'>Enviar otro PDF</a>";

            return html(Response.Status.OK, page("PDF recibido", body));

        } catch (Exception e) {
            return html(
                    Response.Status.INTERNAL_ERROR,
                    uploadPage("Error al recibir el archivo: " + escapeHtml(safeMessage(e)))
            );
        }
    }

    private String uploadPage(String message) {
        String alert = "";

        if (message != null && !message.isEmpty()) {
            alert = "<div class='error'>" + escapeHtml(message) + "</div>";
        }

        String body =
                alert +
                "<h2>Enviar PDF a Rokid</h2>" +
                "<p>Selecciona un documento PDF de tu teléfono.</p>" +
                "<form action='/upload' method='post' enctype='multipart/form-data'>" +
                "<input class='file' type='file' name='pdf' accept='application/pdf,.pdf' required>" +
                "<button type='submit'>SUBIR PDF</button>" +
                "</form>" +
                "<p class='small'>Mantén abierta la pantalla de recepción en las Rokid hasta terminar.</p>";

        return page("Rokid PDF Reader", body);
    }

    private String page(String title, String body) {
        return "<!doctype html>" +
                "<html><head>" +
                "<meta charset='utf-8'>" +
                "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<title>" + escapeHtml(title) + "</title>" +
                "<style>" +
                "body{font-family:Arial,sans-serif;background:#101010;color:#fff;margin:0;padding:24px;text-align:center}" +
                ".card{max-width:520px;margin:20px auto;background:#1f1f1f;border-radius:18px;padding:24px}" +
                "button,.button{display:block;width:100%;box-sizing:border-box;margin-top:18px;padding:16px;border:0;border-radius:12px;background:#fff;color:#000;font-size:18px;font-weight:bold;text-decoration:none}" +
                ".file{display:block;width:100%;box-sizing:border-box;padding:14px;background:#333;color:#fff;border-radius:10px}" +
                ".small{color:#aaa;font-size:13px;margin-top:20px}" +
                ".ok{color:#7CFF8A;font-size:20px;font-weight:bold}" +
                ".error{color:#ff8a8a;margin-bottom:16px}" +
                "</style>" +
                "</head><body><div class='card'>" +
                body +
                "</div></body></html>";
    }

    private Response html(Response.Status status, String html) {
        return newFixedLengthResponse(
                status,
                "text/html; charset=utf-8",
                html
        );
    }

    private boolean looksLikePdf(File file) throws IOException {
        byte[] header = new byte[5];

        try (FileInputStream input = new FileInputStream(file)) {
            int count = input.read(header);
            return count == 5
                    && header[0] == '%'
                    && header[1] == 'P'
                    && header[2] == 'D'
                    && header[3] == 'F'
                    && header[4] == '-';
        }
    }

    private void copyFile(File source, File destination) throws IOException {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {

            byte[] buffer = new byte[64 * 1024];
            int read;

            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    private File uniqueDestination(String fileName) {
        File target = new File(pdfDirectory, fileName);

        if (!target.exists()) {
            return target;
        }

        int dot = fileName.toLowerCase().lastIndexOf(".pdf");
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;

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

    private String sanitizeFileName(String name) {
        String clean = name
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .trim();

        return clean.isEmpty()
                ? "documento.pdf"
                : clean;
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();

        return (message == null || message.trim().isEmpty())
                ? throwable.getClass().getSimpleName()
                : message;
    }

    private String escapeHtml(String value) {
        if (value == null) return "";

        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
