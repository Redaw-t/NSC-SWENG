package barcode.service;

import shared.Money;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Draws a product label as a {@link LabelBitmap}, at the printer's own resolution:
 *
 * <pre>
 *            NICKEL SALES CENTER            (small)
 *         Bond Paper A4 Long (ream)...      (bold, cut with "..." if too long)
 *         ||| |||| || ||| | ||||| ||        (the barcode)
 *               4800000000019
 *   Retail P112.00          Wholesale P100.80   (small; the ticked prices only, one alone is centred)
 * </pre>
 *
 * Text is drawn without anti-aliasing, so every dot is plainly black or white on the thermal paper
 * and in the preview alike. Plain Java (java.awt images, no window), so it also runs in the checks.
 */
public final class LabelRenderer {

    /** Printable width of 58 mm paper on a 203 dpi printer (most POS-58 printers). */
    public static final int WIDTH_58MM = 384;

    private static final int SIDE = 12;            // dots kept clear at the left and right edges
    private static final int QUIET = 20;           // extra clear space beside the bars, for scanners
    private static final int BAR_HEIGHT = 80;
    private static final int MAX_MODULE_DOTS = 3;

    private static final Font STORE = new Font("Arial", Font.BOLD, 16);
    private static final Font NAME = new Font("Arial", Font.BOLD, 24);
    private static final Font NUMBER = new Font("Arial", Font.PLAIN, 20);
    private static final Font PRICE = new Font("Arial", Font.PLAIN, 17);

    private LabelRenderer() { }

    /**
     * @param storeName  the small line at the top, e.g. "NICKEL SALES CENTER"
     * @param widthDots  the paper's printable width in dots ({@link #WIDTH_58MM})
     */
    public static LabelBitmap render(String storeName, LabelContent label, int widthDots) {
        BufferedImage img = new BufferedImage(widthDots, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, widthDots, img.getHeight());
            g.setColor(Color.BLACK);

            int textWidth = widthDots - 2 * SIDE;
            int y = 4;
            y = centred(g, STORE, fit(g, STORE, storeName == null ? "" : storeName.toUpperCase(), textWidth),
                    widthDots, y) + 4;
            y = centred(g, NAME, fit(g, NAME, label.productName(), textWidth), widthDots, y) + 8;

            boolean[] modules = Ean.canDraw(label.barcode()) ? Ean.modules(label.barcode())
                    : Code128.modules(label.barcode());
            int moduleDots = EscPosBarcode.moduleDots(modules.length, widthDots - 2 * (SIDE + QUIET), MAX_MODULE_DOTS);
            int barsWidth = modules.length * moduleDots;
            int x0 = (widthDots - barsWidth) / 2;
            for (int m = 0; m < modules.length; m++) {
                if (modules[m]) {
                    g.fillRect(x0 + m * moduleDots, y, moduleDots, BAR_HEIGHT);
                }
            }
            y += BAR_HEIGHT + 4;
            y = centred(g, NUMBER, label.barcode(), widthDots, y) + 8;

            String peso = PRICE.canDisplay('\u20B1') ? "\u20B1" : "P";
            String retail = label.retailPrice() == null ? null : "Retail " + peso + Money.format(label.retailPrice());
            String wholesale = label.wholesalePrice() == null ? null
                    : "Wholesale " + peso + Money.format(label.wholesalePrice());
            if (retail != null || wholesale != null) {
                g.setFont(PRICE);
                FontMetrics pm = g.getFontMetrics();
                int baseline = y + pm.getAscent();
                if (retail != null && wholesale != null) {        // retail left, wholesale right
                    g.drawString(retail, SIDE, baseline);
                    g.drawString(wholesale, widthDots - SIDE - pm.stringWidth(wholesale), baseline);
                } else {                                            // only one: centred
                    String only = retail != null ? retail : wholesale;
                    g.drawString(only, (widthDots - pm.stringWidth(only)) / 2, baseline);
                }
                y = baseline + pm.getDescent() + 6;
            } else {
                y -= 2;                                             // no prices: the label just ends sooner
            }

            return toBitmap(img, widthDots, Math.min(y, img.getHeight()));
        } finally {
            g.dispose();
        }
    }

    /** Draws {@code text} centred with its top at {@code top}; returns the y just below it. */
    private static int centred(Graphics2D g, Font font, String text, int width, int top) {
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(text, (width - fm.stringWidth(text)) / 2, top + fm.getAscent());
        return top + fm.getAscent() + fm.getDescent();
    }

    /** {@code text}, cut and ended with "..." when it is wider than {@code max} dots. */
    static String fit(Graphics2D g, Font font, String text, int max) {
        FontMetrics fm = g.getFontMetrics(font);
        String t = text == null ? "" : text.trim();
        if (fm.stringWidth(t) <= max) {
            return t;
        }
        while (!t.isEmpty() && fm.stringWidth(t + "...") > max) {
            t = t.substring(0, t.length() - 1);
        }
        return t.stripTrailing() + "...";
    }

    private static LabelBitmap toBitmap(BufferedImage img, int width, int height) {
        boolean[] black = new boolean[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = img.getRGB(x, y);
                int lum = ((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF);
                black[y * width + x] = lum < 3 * 128;
            }
        }
        return new LabelBitmap(width, height, black);
    }

    /** For the self-check (test/barcode/BarcodeCheck): {@link #fit} with the product-name font. */
    public static String fitForCheck(Graphics2D g, String text, int max) {
        return fit(g, NAME, text, max);
    }

    /** For the self-check: the product-name font. */
    public static Font nameFontForCheck() {
        return NAME;
    }
}
