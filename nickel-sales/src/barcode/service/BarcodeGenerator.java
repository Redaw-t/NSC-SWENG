package barcode.service;

import product.contract.ProductLookup;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.Random;
import java.util.function.Predicate;

/**
 * Makes store-only EAN-13 barcodes: the prefix 200 (GS1 keeps 200-299 for
 * in-store use, so no manufacturer has it), 9 random digits and a correct
 * check digit. Skips any code that is already used.
 *
 * "Not used" is only checked here as a courtesy. The real guarantee is the
 * database's unique index on Product.Barcode, which ProductService turns into
 * a message if two terminals pick the same code.
 */
public final class BarcodeGenerator {

    public static final String STORE_PREFIX = "200";
    private static final int ATTEMPTS = 20;

    private final Predicate<String> taken;
    private final Random random;

    /** Uses the product catalog to see which codes are taken (inactive products count too). */
    public BarcodeGenerator(ProductLookup products) {
        this(code -> products.findByBarcode(code).isPresent(), new SecureRandom());
    }

    /** @param taken true when the code is already used; {@code random} can be seeded in tests */
    public BarcodeGenerator(Predicate<String> taken, Random random) {
        this.taken = Objects.requireNonNull(taken, "taken");
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * A new unused barcode.
     *
     * @throws IllegalStateException 20 tries in a row were all taken (practically impossible)
     */
    public String next() {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            String code = candidate();
            if (!taken.test(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Could not find an unused barcode. Try again.");
    }

    private String candidate() {
        StringBuilder digits = new StringBuilder(STORE_PREFIX);
        for (int i = 0; i < 9; i++) {
            digits.append(random.nextInt(10));
        }
        return digits.append(BarcodeValidator.checkDigit(digits.toString())).toString();
    }
}