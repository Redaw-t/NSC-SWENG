package product.repository;

import product.model.Category;

import java.util.List;
import java.util.Optional;

/**
 * What the Category service needs stored and loaded. Implemented by
 * {@link MySqlCategoryRepository}. Database problems are thrown as RuntimeException.
 */
public interface CategoryRepository {

    /** All categories sorted by name; {@code activeOnly} leaves out deactivated ones. */
    List<Category> findAll(boolean activeOnly);

    Optional<Category> findById(int categoryId);

    /** Exact name, ignoring upper/lower case. */
    Optional<Category> findByName(String name);

    /**
     * Saves a new category and returns it with its new ID.
     *
     * @throws DuplicateValueException the name is already used
     */
    Category insert(Category category);

    /**
     * Changes the name and active flag of an existing category.
     *
     * @throws DuplicateValueException the name belongs to another category
     * @throws RuntimeException        there is no such category
     */
    void update(Category category);
}
