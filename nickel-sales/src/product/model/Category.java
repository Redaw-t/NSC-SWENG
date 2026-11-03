package product.model;

/**
 * A product category such as "Office Supplies".
 *
 * @param categoryId the database ID; null for a category that is not saved yet
 * @param name       unique (ignoring upper/lower case), at most {@value #MAX_NAME_LENGTH} characters
 * @param active     false = hidden from the add / edit product form; existing products keep it
 */
public record Category(Integer categoryId, String name, boolean active) {

    public static final int MAX_NAME_LENGTH = 50;

    public Category {
        name = name == null ? "" : name.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("The category name is required.");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("The category name can be at most " + MAX_NAME_LENGTH + " characters.");
        }
    }

    public Category withName(String newName) {
        return new Category(categoryId, newName, active);
    }

    public Category withActive(boolean newActive) {
        return new Category(categoryId, name, newActive);
    }

    @Override
    public String toString() {
        return name;
    }
}
