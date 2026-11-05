package pos.receipt;

import barcode.service.Code128;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws a {@link Receipt} as one black-and-white picture, dot for dot at the printer's resolution
 * (203 dpi: 384 dots across on 58 mm paper, up to 576 on 80 mm). The SAME picture is sent to the
 * printer ({@link EscPosReceiptPrinter}) and shown in the on-screen preview, so the cashier sees
 * exactly what prints.
 *
 * <p>Layout (top to bottom): the logo and store header, "SALES INVOICE", the invoice details, the
 * items, item count, subtotal and TOTAL, payment and CHANGE, the VAT breakdown, the customer's
 * fill-in lines, the invoice barcode and the closing note, with dotted lines between the parts.
 * On 58 mm paper the logo sits above the store name; on wider paper it sits beside it.
 *
 * <p>Text is drawn without anti-aliasing so every dot is plainly black or white. The logo
 * ({@value #LOGO}) is turned into dots with a fine pattern, so the red part prints lighter than the
 * blue. Plain Java (java.awt images, no window), so it also runs in the checks.
 */
public final class ReceiptRenderer {

    /** The logo, from resources/images (copied next to the classes by run-demo). */
    static final String LOGO = "/images/nickel-logo-icon.png";
    private static final String FONT = "Arial";

    private static BufferedImage logoCache;
    private static boolean logoTried;

    private ReceiptRenderer() { }

    /** Printable width in dots for a receipt {@code widthChars} characters wide (32 -> 384 dots, 48+ -> 576). */
    public static int dotsFor(int widthChars) {
        return ReceiptBarcode.paperDots(widthChars);
    }

    /**
     * The receipt as a black-and-white picture ({@link BufferedImage#TYPE_BYTE_BINARY}).
     *
     * @param widthChars the receipt width setting (NSC_RECEIPT_WIDTH): 32 for 58 mm paper, 42-48 for 80 mm
     */
    public static BufferedImage render(Receipt receipt, int widthChars) {
        int width = dotsFor(widthChars);
        List<Receipt.Line> lines = receipt.lines();
        BufferedImage canvas = new BufferedImage(width, 2400 + 140 * lines.size(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
            g.setColor(Color.BLACK);

            Layout l = new Layout(g, canvas, width, widthChars);
            for (Receipt.Line line : lines) {
                l.draw(line, receipt);
            }
            int height = Math.min(canvas.getHeight(), l.y + l.px(8));
            return toBlackAndWhite(canvas, width, height);
        } finally {
            g.dispose();
        }
    }

    /* ------------------------------ layout ------------------------------ */

    /** Draws the lines one after another, keeping the current height in {@link #y}. */
    private static final class Layout {
        final Graphics2D g;
        final BufferedImage canvas;
        final int width;
        final int widthChars;
        final double k;          // 1.0 on 58 mm paper, up to 1.5 on 80 mm
        final int left;
        final int right;
        int y;

        final Font small;
        final Font normal;
        final Font bold;
        final Font large;
        final Font total;
        final Font storeName;
        final Font header;
        final Font headerBold;

        Layout(Graphics2D g, BufferedImage canvas, int width, int widthChars) {
            this.g = g;
            this.canvas = canvas;
            this.width = width;
            this.widthChars = widthChars;
            this.k = 1 + (width / 384.0 - 1) * 0.6;   // wider paper: a little bigger, not in proportion
            this.left = px(10);
            this.right = width - px(10);
            this.y = px(6);
            small = font(Font.PLAIN, 15);
            normal = font(Font.PLAIN, 17);
            bold = font(Font.BOLD, 17);
            large = font(Font.PLAIN, 21);
            total = font(Font.BOLD, 23);
            storeName = font(Font.PLAIN, 30);
            header = font(Font.PLAIN, 14);
            headerBold = font(Font.BOLD, 14);
        }

        int px(double dotsOn58mm) {
            return (int) Math.round(dotsOn58mm * k);
        }

        Font font(int style, double size) {
            return new Font(FONT, style, px(size));
        }

        Font fontOf(Receipt.Style style) {
            return switch (style) {
                case SMALL -> small;
                case NORMAL -> normal;
                case BOLD -> bold;
                case LARGE -> large;
                case TOTAL -> total;
            };
        }

        void draw(Receipt.Line line, Receipt receipt) {
            switch (line) {
                case Receipt.Header h -> header(h.store());
                case Receipt.Rule r -> rule();
                case Receipt.Gap gap -> y += px(8);
                case Receipt.Center c -> {
                    for (String part : wrap(c.text(), fontOf(c.style()), right - left)) {
                        text(part, fontOf(c.style()), (width - stringWidth(part, fontOf(c.style()))) / 2);
                    }
                }
                case Receipt.Left t -> {
                    if (stringWidth(t.text(), fontOf(t.style())) <= right - left) {
                        text(t.text(), fontOf(t.style()), left);   // as written (keeps "Item Count   9.5" spacing)
                    } else {
                        for (String part : wrap(t.text(), fontOf(t.style()), right - left)) {
                            text(part, fontOf(t.style()), left);
                        }
                    }
                }
                case Receipt.Pair p -> pair(p.left(), p.right(), fontOf(p.style()), left);
                case Receipt.Item i -> {
                    for (String part : wrap(i.name(), normal, right - left)) {
                        text(part, normal, left);
                    }
                    pair(i.detail(), i.amount(), normal, left + px(22));
                    y += px(3);
                }
                case Receipt.FillIn f -> fillIn(f.label());
                case Receipt.Barcode b -> barcode(b.value());
            }
        }

        /** Logo and store details: logo on top (58 mm) or beside them (80 mm). */
        void header(StoreInfo store) {
            BufferedImage logo = logo();
            boolean beside = width >= 500 && logo != null;
            int logoSize = beside ? px(80) : px(92);
            int top = y;

            int textLeft = left;
            int textRight = right;
            if (logo != null && beside) {
                drawLogo(logo, left, top, logoSize);
                textLeft = left + logoSize + px(10);
            } else if (logo != null) {
                drawLogo(logo, (width - logoSize) / 2, top, logoSize);
                y = top + logoSize + px(6);
            }
            int textWidth = textRight - textLeft;
            int startY = y;

            Font name = fit(store.name(), storeName, textWidth, px(18));
            centered(store.name(), name, textLeft, textRight);
            y += px(2);
            for (String part : wrap(store.address(), header, textWidth)) {
                centered(part, header, textLeft, textRight);
            }
            if (!store.proprietor().isEmpty()) {
                // "JERICK CHUA CUBILLAN - PROPRIETOR": the name in bold
                String rest = " - PROPRIETOR";
                int w = stringWidth(store.proprietor(), headerBold) + stringWidth(rest, header);
                if (w <= textWidth) {
                    int x = textLeft + (textWidth - w) / 2;
                    int base = y + ascent(header);
                    g.setFont(headerBold);
                    g.drawString(store.proprietor(), x, base);
                    g.setFont(header);
                    g.drawString(rest, x + stringWidth(store.proprietor(), headerBold), base);
                    y += lineHeight(header);
                } else {
                    for (String part : wrap(store.proprietor() + rest, headerBold, textWidth)) {
                        centered(part, headerBold, textLeft, textRight);
                    }
                }
            }
            if (!store.tin().isEmpty()) {
                centered("VAT-REG TIN: " + store.tin(), header, textLeft, textRight);
            }
            if (!store.phone().isEmpty()) {
                centered("Tel. " + store.phone(), header, textLeft, textRight);
            }
            if (beside) {
                // centre the text block against the logo when the logo is the taller of the two
                int textHeight = y - startY;
                if (textHeight < logoSize) {
                    shiftDown(textLeft, startY, textRight, y, (logoSize - textHeight) / 2);
                    y = top + logoSize;
                }
            }
            y += px(4);
        }

        /** A dotted line across the paper. */
        void rule() {
            y += px(5);
            int dot = Math.max(2, px(2));
            int step = px(6);
            for (int x = left; x + dot <= right; x += step) {
                g.fillRect(x, y, dot, dot);
            }
            y += dot + px(7);
        }

        /** "Label: ......." up to the right edge. */
        void fillIn(String label) {
            String start = label + ": ";
            text(start, normal, left);
            int base = y - descent(normal) - 1;
            int from = left + stringWidth(start, normal);
            for (int x = from; x < right; x += Math.max(3, px(3))) {
                g.fillRect(x, base, 1, 1);
            }
            y += px(4);
        }

        /** The bars, centred, and the number under them. */
        void barcode(String value) {
            y += px(6);
            boolean[] modules = Code128.modules(value);
            int moduleDots = ReceiptBarcode.moduleDots(value, widthChars);
            int barsWidth = modules.length * moduleDots;
            int x0 = (width - barsWidth) / 2;
            int height = px(62);
            for (int m = 0; m < modules.length; m++) {
                if (modules[m]) {
                    g.fillRect(x0 + m * moduleDots, y, moduleDots, height);
                }
            }
            y += height + px(4);
            text(value, small, (width - stringWidth(value, small)) / 2);
        }

        /** Left text and a right-aligned value; the value drops to its own line when both don't fit. */
        void pair(String leftText, String rightText, Font font, int x) {
            int rw = stringWidth(rightText, font);
            int lw = stringWidth(leftText, font);
            if (x + lw + px(8) + rw <= right) {
                int base = y + ascent(font);
                g.setFont(font);
                g.drawString(leftText, x, base);
                g.drawString(rightText, right - rw, base);
                y += lineHeight(font);
            } else {
                for (String part : wrap(leftText, font, right - x)) {
                    text(part, font, x);
                }
                for (String part : wrap(rightText, font, right - x)) {
                    text(part, font, right - stringWidth(part, font));
                }
            }
        }

        void centered(String text, Font font, int from, int to) {
            text(text, font, from + (to - from - stringWidth(text, font)) / 2);
        }

        void text(String text, Font font, int x) {
            g.setFont(font);
            g.drawString(text, x, y + ascent(font));
            y += lineHeight(font);
        }

        int stringWidth(String s, Font f) {
            return g.getFontMetrics(f).stringWidth(s);
        }

        int ascent(Font f) {
            return g.getFontMetrics(f).getAscent();
        }

        int descent(Font f) {
            return g.getFontMetrics(f).getDescent();
        }

        int lineHeight(Font f) {
            FontMetrics fm = g.getFontMetrics(f);
            return fm.getAscent() + fm.getDescent() + px(2);
        }

        /** The largest size of {@code font} (down to {@code minSize}) at which {@code text} fits. */
        Font fit(String text, Font font, int max, int minSize) {
            Font f = font;
            while (stringWidth(text, f) > max && f.getSize() > minSize) {
                f = f.deriveFont((float) (f.getSize() - 1));
            }
            return f;
        }

        /** Word-wraps by width; a word too long for a line is cut. */
        List<String> wrap(String text, Font font, int max) {
            List<String> out = new ArrayList<>();
            if (text == null || text.isBlank()) {
                return out;
            }
            StringBuilder line = new StringBuilder();
            for (String word : text.trim().split("\\s+")) {
                while (stringWidth(word, font) > max) {
                    int cut = word.length() - 1;
                    while (cut > 1 && stringWidth(word.substring(0, cut), font) > max) {
                        cut--;
                    }
                    if (line.length() > 0) {
                        out.add(line.toString());
                        line.setLength(0);
                    }
                    out.add(word.substring(0, cut));
                    word = word.substring(cut);
                }
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (line.length() > 0 && stringWidth(candidate, font) > max) {
                    out.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                } else {
                    line.setLength(0);
                    line.append(candidate);
                }
            }
            if (line.length() > 0) {
                out.add(line.toString());
            }
            return out;
        }

        /** Moves an already drawn area down (to centre the header text against the logo). */
        void shiftDown(int x0, int y0, int x1, int y1, int by) {
            if (by <= 0) {
                return;
            }
            BufferedImage part = canvas.getSubimage(x0, y0, x1 - x0, y1 - y0);
            BufferedImage copy = new BufferedImage(part.getWidth(), part.getHeight(), BufferedImage.TYPE_INT_RGB);
            copy.getGraphics().drawImage(part, 0, 0, null);
            g.setColor(Color.WHITE);
            g.fillRect(x0, y0, x1 - x0, y1 - y0);
            g.drawImage(copy, x0, y0 + by, null);
            g.setColor(Color.BLACK);
        }

        /** The logo as dots: dark blue solid, red as a lighter dot pattern, transparent as paper. */
        void drawLogo(BufferedImage logo, int x0, int y0, int size) {
            int[][] bayer = {{0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}};
            for (int yy = 0; yy < size; yy++) {
                for (int xx = 0; xx < size; xx++) {
                    int sx = xx * logo.getWidth() / size;
                    int sy = yy * logo.getHeight() / size;
                    int argb = logo.getRGB(sx, sy);
                    double alpha = ((argb >>> 24) & 0xFF) / 255.0;
                    double lum = (0.299 * ((argb >> 16) & 0xFF) + 0.587 * ((argb >> 8) & 0xFF)
                            + 0.114 * (argb & 0xFF)) / 255.0;
                    double light = 1 - alpha * (1 - lum);                    // on white paper
                    double darkness = Math.min(1, (1 - light) * 1.6);       // blue -> solid, red -> about half
                    boolean black = darkness > (bayer[yy % 4][xx % 4] + 0.5) / 16.0;
                    if (black) {
                        canvas.setRGB(x0 + xx, y0 + yy, 0x000000);
                    }
                }
            }
        }
    }

    /** The logo image, or null if it is not on the classpath (the header is then text only). */
    static synchronized BufferedImage logo() {
        if (!logoTried) {
            logoTried = true;
            try (InputStream in = ReceiptRenderer.class.getResourceAsStream(LOGO)) {
                logoCache = in == null ? null : ImageIO.read(in);
            } catch (IOException e) {
                logoCache = null;
            }
        }
        return logoCache;
    }

    /** Every dot plainly black or white (the printer has nothing in between). */
    private static BufferedImage toBlackAndWhite(BufferedImage canvas, int width, int height) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = canvas.getRGB(x, y);
                int lum = ((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF);
                out.setRGB(x, y, lum < 3 * 128 ? 0x000000 : 0xFFFFFF);
            }
        }
        return out;
    }
}
