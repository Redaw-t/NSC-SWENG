package barcode.service;

/**
 * Bar patterns of EAN-13, UPC-A and EAN-8, for the on-screen label preview, so the preview shows
 * the same bars the label printer's own EAN / UPC command prints. (Code 128, used on receipts,
 * is {@link Code128}.) Each module is one bar-width: true = black, false = white. The caller must
 * pass a code with a correct check digit (see {@link BarcodeValidator#problemWith}).
 */
public final class Ean {

    private static final String[] L = {"0001101", "0011001", "0010011", "0111101", "0100011",
            "0110001", "0101111", "0111011", "0110111", "0001011"};
    private static final String[] G = {"0100111", "0110011", "0011011", "0100001", "0011101",
            "0111001", "0000101", "0010001", "0001001", "0010111"};
    private static final String[] R = {"1110010", "1100110", "1101100", "1000010", "1011100",
            "1001110", "1010000", "1000100", "1001000", "1110100"};
    /** Which of the 6 left digits use the G pattern (else L), by the first digit of an EAN-13. */
    private static final String[] PARITY = {"LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG",
            "LGGLLG", "LGGGLL", "LGLGLG", "LGLGGL", "LGGLGL"};

    private Ean() { }

    /** True when {@code code} is an EAN-13, UPC-A or EAN-8 with a correct check digit. */
    public static boolean canDraw(String code) {
        return code != null && BarcodeValidator.formatOf(code) != BarcodeValidator.Format.OTHER
                && BarcodeValidator.hasValidCheckDigit(code);
    }

    /** The modules of the barcode: 95 for EAN-13 and UPC-A, 67 for EAN-8. */
    public static boolean[] modules(String code) {
        if (!canDraw(code)) {
            throw new IllegalArgumentException("Not a valid EAN-13, UPC-A or EAN-8 code: " + code);
        }
        StringBuilder bits = new StringBuilder("101");
        switch (BarcodeValidator.formatOf(code)) {
            case EAN_8 -> {
                for (int i = 0; i < 4; i++) {
                    bits.append(L[digit(code, i)]);
                }
                bits.append("01010");
                for (int i = 4; i < 8; i++) {
                    bits.append(R[digit(code, i)]);
                }
            }
            default -> {
                String ean = code.length() == 12 ? "0" + code : code;   // UPC-A is an EAN-13 starting with 0
                String parity = PARITY[digit(ean, 0)];
                for (int i = 1; i <= 6; i++) {
                    bits.append(parity.charAt(i - 1) == 'L' ? L[digit(ean, i)] : G[digit(ean, i)]);
                }
                bits.append("01010");
                for (int i = 7; i <= 12; i++) {
                    bits.append(R[digit(ean, i)]);
                }
            }
        }
        bits.append("101");
        boolean[] modules = new boolean[bits.length()];
        for (int i = 0; i < modules.length; i++) {
            modules[i] = bits.charAt(i) == '1';
        }
        return modules;
    }

    private static int digit(String code, int index) {
        return code.charAt(index) - '0';
    }
}
