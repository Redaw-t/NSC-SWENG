package barcode.service;

import java.io.ByteArrayOutputStream;

/**
 * Turns a {@link Code128} barcode into an ESC/POS raster image (GS v 0) that a
 * thermal receipt printer prints as a picture. A picture is used instead of the
 * printer's own barcode command so that every printer model prints the same bars
 * the screen preview shows.
 */
public final class EscPosBarcode {

    private EscPosBarcode() { }

    /**
     * The largest bar width (in printer dots, 1 to {@code maxModuleDots}) at which
     * {@code modules} modules still fit in {@code availableDots}; 1 if even that is too wide.
     */
    public static int moduleDots(int modules, int availableDots, int maxModuleDots) {
        int dots = availableDots / Math.max(1, modules);
        return Math.max(1, Math.min(maxModuleDots, dots));
    }

    /**
     * ESC/POS bytes that print {@code modules} as black bars, each module
     * {@code moduleDots} dots wide and the whole barcode {@code heightDots} dots tall.
     * Print it with the line centered (ESC a 1) so the paper margins form the quiet zone.
     */
    public static byte[] raster(boolean[] modules, int moduleDots, int heightDots) {
        int widthDots = modules.length * moduleDots;
        int widthBytes = (widthDots + 7) / 8;
        byte[] row = new byte[widthBytes];
        for (int m = 0; m < modules.length; m++) {
            if (!modules[m]) {
                continue;
            }
            for (int d = 0; d < moduleDots; d++) {
                int dot = m * moduleDots + d;
                row[dot / 8] |= (byte) (0x80 >> (dot % 8));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x1D);                         // GS v 0 m xL xH yL yH d1...dk
        out.write(0x76);
        out.write(0x30);
        out.write(0);                            // normal density
        out.write(widthBytes & 0xFF);
        out.write(widthBytes >> 8);
        out.write(heightDots & 0xFF);
        out.write(heightDots >> 8);
        for (int y = 0; y < heightDots; y++) {
            out.write(row, 0, row.length);
        }
        return out.toByteArray();
    }
}
