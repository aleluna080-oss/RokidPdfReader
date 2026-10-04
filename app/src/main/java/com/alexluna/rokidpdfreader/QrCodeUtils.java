package com.alexluna.rokidpdfreader;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

public final class QrCodeUtils {

    private QrCodeUtils() {}

    public static Bitmap create(String text, int size) throws WriterException {
        BitMatrix matrix = new QRCodeWriter().encode(
                text,
                BarcodeFormat.QR_CODE,
                size,
                size
        );

        int[] pixels = new int[size * size];

        for (int y = 0; y < size; y++) {
            int offset = y * size;
            for (int x = 0; x < size; x++) {
                pixels[offset + x] = matrix.get(x, y)
                        ? Color.BLACK
                        : Color.WHITE;
            }
        }

        Bitmap bitmap = Bitmap.createBitmap(
                size,
                size,
                Bitmap.Config.ARGB_8888
        );

        bitmap.setPixels(
                pixels,
                0,
                size,
                0,
                0,
                size,
                size
        );

        return bitmap;
    }
}
