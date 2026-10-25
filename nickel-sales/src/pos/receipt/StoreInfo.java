package pos.receipt;

import java.util.Objects;

/**
 * The store details printed at the top of every receipt. Set per terminal
 * with environment variables (see app.AppConfig): NSC_STORE_NAME,
 * NSC_STORE_ADDRESS, NSC_STORE_TIN, NSC_STORE_PHONE. Blank details are
 * simply left off the receipt.
 */
public record StoreInfo(String name, String address, String tin, String phone) {

    public static final String DEFAULT_NAME = "NICKEL SALES CENTER";

    public StoreInfo {
        name = (name == null || name.isBlank()) ? DEFAULT_NAME : name.trim();
        address = Objects.requireNonNullElse(address, "").trim();
        tin = Objects.requireNonNullElse(tin, "").trim();
        phone = Objects.requireNonNullElse(phone, "").trim();
    }

    /** Just the store name, no other details. */
    public static StoreInfo named(String name) {
        return new StoreInfo(name, "", "", "");
    }
}
