package barcode;

import barcode.service.BarcodeGenerator;
import barcode.service.BarcodeService;
import barcode.service.BarcodeStatus;
import barcode.service.Ean;
import barcode.service.LabelPrintException;
import barcode.service.LabelBitmap;
import barcode.service.LabelContent;
import barcode.service.LabelPrinterPort;
import barcode.service.LabelRenderer;
import product.contract.BarcodeAssignException;
import product.contract.BarcodeAssigner;
import product.contract.BarcodeEntry;
import product.contract.ProductInfo;
import product.contract.ProductLookup;
import barcode.service.BarcodeValidator;
import barcode.service.BarcodeValidator.Format;
import barcode.service.EscPosLabelPrinter;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Self-test for the Barcode module (run by {@code run-demo --scripted}).
 * Needs no database. Prints "RESULT: N passed, M failed"; exit code 1 on failure.
 */
public final class BarcodeCheck {

    private static int passed;
    private static int failed;

    private BarcodeCheck() { }

    public static void main(String[] args) {
        System.out.println("=================================================================");
        System.out.println(" NICKEL SALES CENTER - Barcode module checks");
        System.out.println("=================================================================");

        check("Check digits: the seed products' barcodes are all valid EAN-13", () -> {
            for (String code : new String[] {"4800000000019", "4800000000026", "4800000000033",
                    "4800000000040", "4800000000057", "4800000000064", "6970340973798", "4800067130063"}) {
                yes(BarcodeValidator.hasValidCheckDigit(code), code + " should be valid");
                yes(BarcodeValidator.formatOf(code) == Format.EAN_13, code + " is EAN-13");
            }
            yes(BarcodeValidator.checkDigit("400638133393") == 1, "known EAN-13 4006381333931");
            yes(BarcodeValidator.hasValidCheckDigit("96385074"), "known EAN-8 96385074");
            yes(BarcodeValidator.hasValidCheckDigit("036000291452"), "known UPC-A 036000291452");
        });

        check("Check digits: a wrong last digit or non-digits are not valid", () -> {
            yes(!BarcodeValidator.hasValidCheckDigit("4800000000010"), "wrong digit");
            yes(!BarcodeValidator.hasValidCheckDigit("ABC123"), "letters");
            yes(!BarcodeValidator.hasValidCheckDigit(null) && !BarcodeValidator.hasValidCheckDigit("5"), "null / too short");
        });

        check("Format by length: 13 / 8 / 12 digits, everything else is OTHER", () -> {
            yes(BarcodeValidator.formatOf("96385074") == Format.EAN_8, "EAN-8");
            yes(BarcodeValidator.formatOf("036000291452") == Format.UPC_A, "UPC-A");
            yes(BarcodeValidator.formatOf("12345") == Format.OTHER, "5 digits");
            yes(BarcodeValidator.formatOf("ZZTEST-0001") == Format.OTHER, "text code");
            yes(BarcodeValidator.formatOf(null) == Format.OTHER, "null");
        });

        check("problemWith: messages for blank, spaces, too long, wrong check digit; null when fine", () -> {
            yes("Enter a barcode.".equals(BarcodeValidator.problemWith("  ")), "blank");
            yes(BarcodeValidator.problemWith("48 00").contains("spaces"), "spaces");
            yes(BarcodeValidator.problemWith("x".repeat(51)).contains("50"), "too long");
            String wrong = BarcodeValidator.problemWith("4800000000010");
            yes(wrong != null && wrong.contains("EAN-13") && wrong.contains("end in 9"), "wrong digit: " + wrong);
            yes(BarcodeValidator.problemWith("4800000000019") == null, "valid EAN-13");
            yes(BarcodeValidator.problemWith("  4800000000019 ") == null, "trimmed");
            yes(BarcodeValidator.problemWith("ZZTEST-0001") == null, "free-text legacy codes are accepted");
            yes(BarcodeValidator.problemWith("12345") == null, "odd lengths are accepted");
        });

        check("Generator: 13 digits, prefix 200, correct check digit", () -> {
            BarcodeGenerator gen = new BarcodeGenerator(code -> false, new Random(42));
            for (int i = 0; i < 500; i++) {
                String code = gen.next();
                yes(code.length() == 13 && code.startsWith("200"), "shape: " + code);
                yes(BarcodeValidator.hasValidCheckDigit(code), "check digit: " + code);
                yes(BarcodeValidator.problemWith(code) == null, "accepted by the validator: " + code);
            }
        });

        check("Generator: skips taken codes and never repeats while they are used", () -> {
            Set<String> used = new HashSet<>();
            BarcodeGenerator gen = new BarcodeGenerator(used::contains, new Random(7));
            for (int i = 0; i < 300; i++) {
                String code = gen.next();
                yes(used.add(code), "duplicate " + code);
            }
            AtomicInteger calls = new AtomicInteger();
            BarcodeGenerator retry = new BarcodeGenerator(code -> calls.incrementAndGet() <= 3, new Random(1));
            retry.next();
            yes(calls.get() == 4, "three taken codes skipped, the fourth used (was " + calls.get() + ")");
        });

        check("Generator: gives up with a clear error when every code is taken", () -> {
            BarcodeGenerator gen = new BarcodeGenerator(code -> true, new Random(3));
            try {
                gen.next();
                throw new AssertionError("expected IllegalStateException");
            } catch (IllegalStateException expected) {
                yes(expected.getMessage().contains("unused barcode"), "message");
            }
        });

        check("Label: one bitmap for preview and print; EAN or CODE128 bars; long names cut with ...", () -> {
            LabelContent content = new LabelContent("Bond Paper A4 (ream)", "4800000000019",
                    new BigDecimal("112.00"), new BigDecimal("100.80"));
            LabelBitmap label = LabelRenderer.render("Nickel Sales Center", content, LabelRenderer.WIDTH_58MM);
            yes(label.width() == 384, "58 mm = 384 dots wide");
            yes(label.height() > 150 && label.height() < 400, "a few cm tall: " + label.height());
            EscPosLabelPrinter printer = new EscPosLabelPrinter("Nickel Sales Center");
            LabelBitmap preview = printer.render(content);
            yes(Arrays.equals(preview.toEscPosRaster(), label.toEscPosRaster()), "the preview is exactly what prints");

            byte[] one = EscPosLabelPrinter.toBytes(label, 1);
            yes(Arrays.equals(Arrays.copyOf(one, 2), new byte[] {0x1B, 0x40}), "starts with reset");
            String text = new String(one, StandardCharsets.ISO_8859_1);
            yes(text.contains("\u001Dv0"), "sent as one raster picture (GS v 0)");
            yes(!text.contains("\u001Dk"), "no printer barcode command: the printer draws nothing itself");
            byte[] two = EscPosLabelPrinter.toBytes(label, 2);
            yes(two.length > one.length, "two copies are longer than one");

            LabelBitmap code128 = LabelRenderer.render("Nickel Sales Center",
                    new LabelContent("X", "ZZTEST-1", new BigDecimal("5.00"), null), LabelRenderer.WIDTH_58MM);
            yes(code128.height() > 100, "a text code renders too (CODE128)");

            java.awt.Graphics2D g = new java.awt.image.BufferedImage(1, 1,
                    java.awt.image.BufferedImage.TYPE_INT_RGB).createGraphics();
            String cut = LabelRenderer.fitForCheck(g, "A very very very very very very very long product name here", 200);
            yes(cut.endsWith("..."), "too long: ends with ...: " + cut);
            yes(g.getFontMetrics(LabelRenderer.nameFontForCheck()).stringWidth(cut) <= 200, "and fits");
            yes(LabelRenderer.fitForCheck(g, "Pen", 200).equals("Pen"), "short names are left alone");
        });

        check("Label prices: exactly the prices stored for the product (no VAT added)", () -> {
            ProductInfo vatable = new ProductInfo("P-9", "4800000000019", "Pen", "pc", new BigDecimal("100.00"),
                    new BigDecimal("90.00"), true, true, BigDecimal.ZERO);
            LabelContent c = LabelContent.of(vatable);
            yes(c.retailPrice().compareTo(new BigDecimal("100.00")) == 0, "retail as stored: " + c.retailPrice());
            yes(c.wholesalePrice().compareTo(new BigDecimal("90.00")) == 0, "wholesale as stored");
            ProductInfo exempt = new ProductInfo("P-8", "4800000000019", "Rice", "kg", new BigDecimal("50.00"),
                    null, false, true, BigDecimal.ZERO);
            yes(LabelContent.of(exempt).retailPrice().compareTo(new BigDecimal("50.00")) == 0, "exempt: as stored");
            yes(LabelContent.of(exempt).wholesalePrice() == null, "no wholesale price: none on the label");
            LabelContent retailOnly = LabelContent.of(vatable, true, false);
            yes(retailOnly.retailPrice() != null && retailOnly.wholesalePrice() == null, "untick wholesale: retail only");
            LabelContent none = LabelContent.of(vatable, false, false);
            yes(none.retailPrice() == null && none.wholesalePrice() == null, "untick both: no prices");
            int withPrices = LabelRenderer.render("S", LabelContent.of(vatable), 384).height();
            int withoutPrices = LabelRenderer.render("S", none, 384).height();
            yes(withoutPrices < withPrices, "no prices: a shorter label");
        });


        check("EAN-13 bars: 95 modules, guards, and 4006381333931 matches the known pattern", () -> {
            boolean[] m = Ean.modules("4006381333931");
            yes(m.length == 95, "95 modules, was " + m.length);
            yes(bits(m).startsWith("101") && bits(m).endsWith("101"), "start / end guards");
            yes(bits(m).substring(45, 50).equals("01010"), "centre guard");
            // First digit 4 = parity LGLLGG over the digits 0 0 6 3 8 1; the right half 3 3 3 9 3 1 is all R.
            String expected = "101" + "0001101" + "0100111" + "0101111" + "0111101" + "0001001" + "0110011"
                    + "01010" + "1000010" + "1000010" + "1000010" + "1110100" + "1000010" + "1100110" + "101";
            yes(bits(m).equals(expected), "bit pattern: " + bits(m));
        });

        check("EAN variants: UPC-A bars equal the EAN-13 with a leading 0, EAN-8 has 67 modules", () -> {
            yes(Ean.modules("036000291452").length == 95, "UPC-A is 95 modules");
            yes(Arrays.equals(Ean.modules("036000291452"), Ean.modules("0036000291452")), "UPC-A = 0 + code");
            yes(Ean.modules("96385074").length == 67, "EAN-8 is 67 modules");
            yes(Ean.canDraw("4800000000019") && !Ean.canDraw("4800000000010") && !Ean.canDraw("ZZTEST-0001"),
                    "only valid EAN / UPC codes can be drawn");
            try {
                Ean.modules("4800000000010");
                throw new AssertionError("a wrong check digit must be refused");
            } catch (IllegalArgumentException expected) {
                // refused
            }
        });

        check("BarcodeService: status per product (assigned, unassigned, bad check digit, shared code)", () -> {
            FakeCatalog catalog = new FakeCatalog(
                    product("P-1", "Bond Paper", "4800000000019"),
                    product("P-2", "Pencil", null),
                    product("P-3", "Wrong Digit", "4800000000010"),
                    product("P-4", "Twin A", "ZZ-SAME"),
                    product("P-5", "Twin B", "zz-same"));
            var rows = new BarcodeService(catalog, catalog, new FakePrinter()).rows();
            yes(rows.get(0).status() == BarcodeStatus.ASSIGNED && rows.get(0).printable(), "valid EAN is assigned");
            yes(rows.get(1).status() == BarcodeStatus.UNASSIGNED && rows.get(1).barcode() == null, "no barcode");
            yes(rows.get(2).status() == BarcodeStatus.ERROR && rows.get(2).problem().contains("end in 9"), "bad digit");
            yes(!rows.get(2).printable(), "an error row cannot be printed");
            yes(rows.get(3).status() == BarcodeStatus.ERROR && rows.get(4).status() == BarcodeStatus.ERROR
                    && rows.get(3).problem().contains("same barcode"), "shared code flagged on both products");
        });

        check("BarcodeService.assign: saves a good code, refuses a bad one and one used by another product", () -> {
            FakeCatalog catalog = new FakeCatalog(
                    product("P-1", "Bond Paper", "4800000000019"),
                    product("P-2", "Pencil", null));
            BarcodeService service = new BarcodeService(catalog, catalog, new FakePrinter());
            service.assign("P-2", " 4800000000026 ");
            yes("4800000000026".equals(catalog.barcodeOf("P-2")), "saved and trimmed");
            yes(service.problemWith("P-2", "4800000000026") == null, "its own code is not a clash");
            String clash = service.problemWith("P-2", "4800000000019");
            yes(clash != null && clash.contains("Bond Paper") && clash.contains("P-1"), "names the other product: " + clash);
            try {
                service.assign("P-2", "4800000000019");
                throw new AssertionError("a code used by another product must be refused");
            } catch (BarcodeAssignException expected) {
                yes("4800000000026".equals(catalog.barcodeOf("P-2")), "unchanged after the refusal");
            }
            try {
                service.assign("P-2", "4800000000010");
                throw new AssertionError("a wrong check digit must be refused");
            } catch (BarcodeAssignException expected) {
                yes(expected.getMessage().contains("EAN-13"), expected.getMessage());
            }
            try {
                service.assign("P-2", "  ");
                throw new AssertionError("a blank barcode must be refused");
            } catch (BarcodeAssignException expected) {
                yes(expected.getMessage().contains("Enter a barcode"), expected.getMessage());
            }
        });

        check("BarcodeService.printLabels: sends name, code and copies; refuses a product with no barcode", () -> {
            FakeCatalog catalog = new FakeCatalog(product("P-1", "Bond Paper", "4800000000019"),
                    product("P-2", "Pencil", null));
            FakePrinter printer = new FakePrinter();
            BarcodeService service = new BarcodeService(catalog, catalog, printer);
            service.printLabels(catalog.findById("P-1").orElseThrow(), 3);
            yes(printer.last.equals("Bond Paper|4800000000019|3"), printer.last);
            try {
                service.printLabels(catalog.findById("P-2").orElseThrow(), 1);
                throw new AssertionError("no barcode, nothing to print");
            } catch (LabelPrintException expected) {
                yes(expected.getMessage().contains("no barcode"), expected.getMessage());
            }
        });

        System.out.println("-----------------------------------------------------------------");
        System.out.println(" RESULT: " + passed + " passed, " + failed + " failed");
        System.out.println("=================================================================");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static String bits(boolean[] modules) {
        StringBuilder b = new StringBuilder();
        for (boolean m : modules) {
            b.append(m ? '1' : '0');
        }
        return b.toString();
    }

    private static ProductInfo product(String id, String name, String barcode) {
        return new ProductInfo(id, barcode, name, "pc", new BigDecimal("10.00"), null, true, true, BigDecimal.ZERO);
    }

    /** An in-memory Product module: the lookup and the assigner the Barcode service uses. */
    private static final class FakeCatalog implements ProductLookup, BarcodeAssigner {
        private final Map<String, ProductInfo> byId = new LinkedHashMap<>();

        FakeCatalog(ProductInfo... products) {
            for (ProductInfo p : products) {
                byId.put(p.productId(), p);
            }
        }

        String barcodeOf(String id) {
            return byId.get(id).barcode();
        }

        @Override
        public Optional<ProductInfo> findByBarcode(String barcode) {
            return byId.values().stream().filter(p -> barcode.equals(p.barcode())).findFirst();
        }

        @Override
        public Optional<ProductInfo> findById(String productId) {
            return Optional.ofNullable(byId.get(productId));
        }

        @Override
        public List<ProductInfo> search(String text, int limit) {
            return new ArrayList<>(byId.values());
        }

        @Override
        public List<BarcodeEntry> listEntries() {
            return byId.values().stream().map(p -> new BarcodeEntry(p, null)).toList();
        }

        @Override
        public void assignBarcode(String productId, String barcode) throws BarcodeAssignException {
            ProductInfo p = byId.get(productId);
            if (p == null) {
                throw new BarcodeAssignException("Product " + productId + " no longer exists.");
            }
            if (findByBarcode(barcode).filter(o -> !o.productId().equals(productId)).isPresent()) {
                throw new BarcodeAssignException("The barcode " + barcode + " is already used by another product.");
            }
            byId.put(productId, new ProductInfo(p.productId(), barcode, p.name(), p.unit(), p.retailPrice(),
                    p.wholesalePrice(), p.vatable(), p.active(), p.stockOnHand()));
        }
    }

    private static final class FakePrinter implements LabelPrinterPort {
        String last = "";

        @Override
        public LabelBitmap render(LabelContent label) {
            return LabelRenderer.render("Test", label, LabelRenderer.WIDTH_58MM);
        }

        @Override
        public void printLabel(LabelContent label, int copies) {
            last = label.productName() + "|" + label.barcode() + "|" + copies;
        }
    }

    private interface Body {
        void run() throws Exception;
    }

    private static void check(String name, Body body) {
        try {
            body.run();
            passed++;
            System.out.println(" [PASS] " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println(" [FAIL] " + name + "  ->  " + t);
        }
    }

    private static void yes(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}