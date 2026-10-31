package product.service;

import auth.contract.AuditLogger;
import auth.contract.User;
import auth.permission.AuthorizationService;
import product.model.Category;
import product.repository.CategoryRepository;
import product.repository.DuplicateValueException;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Use cases for product categories. Needs MANAGE_PRODUCTS. Names are unique
 * ignoring upper/lower case. Categories are never deleted: deactivating one only
 * hides it from the product form, and products that already have it keep it.
 */
public class CategoryService {

    private final CategoryRepository categories;
    private final CatalogAccess access;

    public CategoryService(CategoryRepository categories, AuthorizationService authz, AuditLogger audit) {
        this.categories = Objects.requireNonNull(categories, "categories");
        this.access = new CatalogAccess(authz, audit);
    }

    /** All categories sorted by name; {@code includeInactive} adds the deactivated ones. */
    public List<Category> listCategories(User actor, boolean includeInactive) throws ProductException {
        access.require(actor);
        return categories.findAll(!includeInactive);
    }

    public Category createCategory(User actor, String name) throws ProductException {
        access.require(actor);
        Category category = build(name);
        requireNameFree(category.name(), null);
        try {
            Category saved = categories.insert(category);
            access.log("CATEGORY_CREATED", actor, String.valueOf(saved.categoryId()));
            return saved;
        } catch (DuplicateValueException e) {
            throw nameTaken(category.name());
        }
    }

    public Category renameCategory(User actor, int categoryId, String newName) throws ProductException {
        access.require(actor);
        Category existing = require(categoryId);
        Category renamed = existing.withName(validName(newName));
        requireNameFree(renamed.name(), categoryId);
        try {
            categories.update(renamed);
        } catch (DuplicateValueException e) {
            throw nameTaken(renamed.name());
        }
        access.log("CATEGORY_RENAMED", actor, String.valueOf(categoryId));
        return renamed;
    }

    public void setActive(User actor, int categoryId, boolean active) throws ProductException {
        access.require(actor);
        Category existing = require(categoryId);
        if (existing.active() == active) {
            return;
        }
        categories.update(existing.withActive(active));
        access.log(active ? "CATEGORY_ACTIVATED" : "CATEGORY_DEACTIVATED", actor, String.valueOf(categoryId));
    }

    private Category require(int categoryId) throws ProductException {
        return categories.findById(categoryId)
                .orElseThrow(() -> new ProductException("That category no longer exists."));
    }

    private void requireNameFree(String name, Integer ownId) throws ProductException {
        Optional<Category> same = categories.findByName(name);
        if (same.isPresent() && !same.get().categoryId().equals(ownId)) {
            throw nameTaken(name);
        }
    }

    private static ProductException nameTaken(String name) {
        return new ProductException("A category named \"" + name + "\" already exists.");
    }

    private static Category build(String name) throws ProductException {
        try {
            return new Category(null, name, true);
        } catch (IllegalArgumentException e) {
            throw new ProductException(e.getMessage());
        }
    }

    private static String validName(String name) throws ProductException {
        return build(name).name();
    }
}
