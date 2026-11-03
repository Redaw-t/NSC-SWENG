package product.model;

/**
 * A supplier of products. Only the name is required.
 *
 * @param supplierId    the database ID; null for a supplier that is not saved yet
 * @param name          unique (ignoring upper/lower case)
 * @param contactPerson optional
 * @param phone         optional
 * @param email         optional
 * @param address       optional
 * @param active        false = hidden from the add / edit product form; existing products keep it
 */
public record Supplier(
        Integer supplierId,
        String name,
        String contactPerson,
        String phone,
        String email,
        String address,
        boolean active) {

    public static final int MAX_NAME_LENGTH = 100;
    public static final int MAX_CONTACT_LENGTH = 100;
    public static final int MAX_PHONE_LENGTH = 30;
    public static final int MAX_EMAIL_LENGTH = 100;
    public static final int MAX_ADDRESS_LENGTH = 200;

    public Supplier {
        name = name == null ? "" : name.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("The supplier name is required.");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("The supplier name can be at most " + MAX_NAME_LENGTH + " characters.");
        }
        contactPerson = optional(contactPerson, MAX_CONTACT_LENGTH, "The contact person");
        phone = optional(phone, MAX_PHONE_LENGTH, "The phone number");
        email = optional(email, MAX_EMAIL_LENGTH, "The email");
        if (email != null && (!email.contains("@") || email.startsWith("@") || email.endsWith("@"))) {
            throw new IllegalArgumentException("The email must look like name@example.com.");
        }
        address = optional(address, MAX_ADDRESS_LENGTH, "The address");
    }

    /** Blank becomes null; too long is refused. */
    private static String optional(String value, int max, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new IllegalArgumentException(label + " can be at most " + max + " characters.");
        }
        return trimmed;
    }

    public Supplier withActive(boolean newActive) {
        return new Supplier(supplierId, name, contactPerson, phone, email, address, newActive);
    }

    @Override
    public String toString() {
        return name;
    }
}
