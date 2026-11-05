package pos.receipt;

import java.util.Objects;

/**
 * The store details printed at the top of every receipt. Set per terminal with environment
 * variables (see app.AppConfig): NSC_STORE_NAME, NSC_STORE_ADDRESS, NSC_STORE_PROPRIETOR,
 * NSC_STORE_TIN, NSC_STORE_PHONE. Blank details are simply left off the receipt.
 *
 * @param proprietor the owner's name, printed as "NAME - PROPRIETOR"
 * @param tin        the VAT registration TIN, printed as "VAT-REG TIN: ..."
 */
public record StoreInfo(String name, String address, String proprietor, String tin, String phone) {

    public static final String DEFAULT_NAME = "NICKEL SALES CENTER";

    /** Nickel Sales Center's own details: used when the environment does not set them (app.AppConfig). */
    public static final String NICKEL_ADDRESS =
            "San Nicolas St Brgy Washington POB. 8400 Surigao City Surigao del Norte Philippines";
    public static final String NICKEL_PROPRIETOR = "JERICK CHUA CUBILLAN";
    public static final String NICKEL_TIN = "178-188-702-00000";

    public StoreInfo {
        name = (name == null || name.isBlank()) ? DEFAULT_NAME : name.trim();
        address = Objects.requireNonNullElse(address, "").trim();
        proprietor = Objects.requireNonNullElse(proprietor, "").trim();
        tin = Objects.requireNonNullElse(tin, "").trim();
        phone = Objects.requireNonNullElse(phone, "").trim();
    }

    /** Without a proprietor (as before). */
    public StoreInfo(String name, String address, String tin, String phone) {
        this(name, address, "", tin, phone);
    }

    /** Just the store name, no other details. */
    public static StoreInfo named(String name) {
        return new StoreInfo(name, "", "", "", "");
    }
}
