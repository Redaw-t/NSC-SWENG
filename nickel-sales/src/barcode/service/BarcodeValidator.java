package barcode.service;

/**
 * Barcode format and check-digit rules (GS1). EAN-13, EAN-8 and UPC-A are
 * checked; any other code is accepted as free text, because legacy products
 * may carry codes the supplier made up.
 */
public final class BarcodeValidator {

    public enum Format { EAN_13, EAN_8, UPC_A, OTHER }

    /** Same limit as Product.MAX_BARCODE_LENGTH. */
    public static final int MAX_LENGTH = 50;

    private BarcodeValidator() { }

    /** The format of {@code code}: digits only and 13 / 8 / 12 long are EAN-13 / EAN-8 / UPC-A. */
    public static Format formatOf(String code) {
        if (code == null || !isDigits(code)) {
            return Format.OTHER;
        }
        return switch (code.length()) {
            case 13 -> Format.EAN_13;
            case 8 -> Format.EAN_8;
            case 12 -> Format.UPC_A;
            default -> Format.OTHER;
        };
    }

    /** The check digit (0-9) for {@code payload}, the code WITHOUT its last digit. Digits only. */
    public static int checkDigit(String payload) {
        if (payload == null || payload.isEmpty() || !isDigits(payload)) {
            throw new IllegalArgumentException("The payload must be digits only: " + payload);
        }
        int sum = 0;
        // From the right, the weights are 3, 1, 3, 1 ...
        for (int i = payload.length() - 1, weight = 3; i >= 0; i--, weight = 4 - weight) {
            sum += (payload.charAt(i) - '0') * weight;
        }
        return (10 - sum % 10) % 10;
    }

    /** True when the last digit of {@code code} is the correct check digit (digits only, 2+ long). */
    public static boolean hasValidCheckDigit(String code) {
        if (code == null || code.length() < 2 || !isDigits(code)) {
            return false;
        }
        int last = code.charAt(code.length() - 1) - '0';
        return checkDigit(code.substring(0, code.length() - 1)) == last;
    }

    /**
     * A message ready to show the user, or null when the barcode is acceptable.
     * Does not check that the barcode is unused: that is ProductService's rule
     * (and the database's unique index).
     */
    public static String problemWith(String barcode) {
        if (barcode == null || barcode.isBlank()) {
            return "Enter a barcode.";
        }
        String code = barcode.trim();
        if (code.length() > MAX_LENGTH) {
            return "The barcode can be at most " + MAX_LENGTH + " characters.";
        }
        if (code.chars().anyMatch(Character::isWhitespace)) {
            return "The barcode cannot contain spaces.";
        }
        Format format = formatOf(code);
        if (format != Format.OTHER && !hasValidCheckDigit(code)) {
            String payload = code.substring(0, code.length() - 1);
            return "That looks like an " + label(format) + " barcode, but its last digit is wrong. "
                    + "It should end in " + checkDigit(payload) + ".";
        }
        return null;
    }

    private static String label(Format format) {
        return switch (format) {
            case EAN_13 -> "EAN-13";
            case EAN_8 -> "EAN-8";
            case UPC_A -> "UPC-A";
            case OTHER -> "other";
        };
    }

    private static boolean isDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return !s.isEmpty();
    }
}