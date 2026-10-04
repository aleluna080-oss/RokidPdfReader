# Rokid PDF Reader

Lector de PDF diseñado para Rokid Glasses con pantalla.

## V1 — alcance

- Biblioteca local dentro de la app.
- Importar PDF mediante el selector de documentos de Android.
- Recibir PDF desde `Compartir` / `Abrir con`.
- Renderizar la página real del PDF con `PdfRenderer`.
- Página anterior / siguiente.
- Zoom + / −.
- Ajustar página a pantalla.
- Pinch-to-zoom y arrastre si el firmware entrega eventos táctiles.
- Sin Internet obligatorio.
- Sin permisos generales de almacenamiento.

## Importante

La V1 todavía NO incluye:
- seguimiento de manos,
- zoom con dos manos,
- transferencia por QR/Wi-Fi,
- búsqueda de texto,
- IA sobre el contenido.

Esas funciones se agregarán después de validar el visor físicamente en las Rokid.

## APK automático

Cada push a `main` ejecuta GitHub Actions y genera:

`RokidPdfReader-V1-APK`

Dentro del artifact está:

`RokidPdfReader-V1-debug.apk`

## Stack

- Java 17
- Android Gradle Plugin 8.7.3
- Gradle 8.9
- compileSdk 35
- minSdk 29
- targetSdk 32
- Android `PdfRenderer`
