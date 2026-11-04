package barcode.service;

import java.io.ByteArrayOutputStream;

/**
 * A finished label as black and white dots, one dot per printer dot (203 dpi). The SAME bitmap is
 * shown in the preview and sent to the printer, so what the user sees is what prints.
 */
public final class LabelBitmap {

    private final int width;
    private final int height;
    private final boolean[] black;

    LabelBitmap(int width, int height, boolean[] black) {
        if (black.length != width * height) {
            throw new IllegalArgumentException("bitmap size does not match " + width + " x " + height);
        }
        this.width = width;
        this.height = height;
        this.black = black;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** True if the dot at (x, y) prints black. */
    public boolean isBlack(int x, int y) {
        return black[y * width + x];
    }

    /** ESC/POS "GS v 0" raster image bytes for the whole label (print with the line centred). */
    public byte[] toEscPosRaster() {
        int widthBytes = (width + 7) / 8;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x1D);                         // GS v 0 m xL xH yL yH d1...dk
        out.write(0x76);
        out.write(0x30);
        out.write(0);                            // normal density
        out.write(widthBytes & 0xFF);
        out.write(widthBytes >> 8);
        out.write(height & 0xFF);
        out.write(height >> 8);
        byte[] row = new byte[widthBytes];
        for (int y = 0; y < height; y++) {
            java.util.Arrays.fill(row, (byte) 0);
            for (int x = 0; x < width; x++) {
                if (black[y * width + x]) {
                    row[x / 8] |= (byte) (0x80 >> (x % 8));
                }
            }
            out.write(row, 0, row.length);
        }
        return out.toByteArray();
    }
}
