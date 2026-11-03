package barcode;

import barcode.service.BarcodeGenerator;
import barcode.service.BarcodeValidator;
import barcode.service.BarcodeValidator.Format;
import barcode.service.EscPosLabelPrinter;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
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

        check("Label printer: ESC/POS bytes have the name, the right barcode type and the digits", () -> {
            byte[] ean = EscPosLabelPrinter.toBytes("Bond Paper A4 (ream)", "4800000000019", 1);
            String text = new String(ean, StandardCharsets.ISO_8859_1);
            yes(text.contains("Bond Paper A4 (ream)"), "name printed");
            yes(text.contains("\u001Dk" + (char) 67 + (char) 13 + "4800000000019"), "EAN-13 command: GS k 67 13 digits");
            yes(!text.contains("\u001Dk" + (char) 73), "an EAN code does not use CODE128");
            byte[] two = EscPosLabelPrinter.toBytes("X", "4800000000019", 2);
            yes(two.length > ean.length, "two copies are longer than one");
            String odd = new String(EscPosLabelPrinter.toBytes("X", "ZZTEST-1", 1), StandardCharsets.ISO_8859_1);
            yes(odd.contains("\u001Dk" + (char) 73), "text codes use CODE128");
            yes(odd.contains("{BZZTEST-1"), "CODE128 set B data");
            yes(Arrays.equals(Arrays.copyOf(ean, 2), new byte[] {0x1B, 0x40}), "starts with reset");
        });

        System.out.println("-----------------------------------------------------------------");
        System.out.println(" RESULT: " + passed + " passed, " + failed + " failed");
        System.out.println("=================================================================");
        if (failed > 0) {
            System.exit(1);
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
