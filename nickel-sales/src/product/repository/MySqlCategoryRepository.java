package product.repository;

import product.model.Category;
import shared.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** CategoryRepository backed by the Category table. All SQL for categories lives here. */
public class MySqlCategoryRepository implements CategoryRepository {

    private static final String SELECT = "SELECT CategoryID, CategoryName, IsActive FROM Category";

    @Override
    public List<Category> findAll(boolean activeOnly) {
        String sql = SELECT + (activeOnly ? " WHERE IsActive = TRUE" : "") + " ORDER BY CategoryName";
        List<Category> found = new ArrayList<>();
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                found.add(map(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Could not load categories", e);
        }
        return found;
    }

    @Override
    public Optional<Category> findById(int categoryId) {
        return queryOne(SELECT + " WHERE CategoryID = ?", ps -> ps.setInt(1, categoryId));
    }

    @Override
    public Optional<Category> findByName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return queryOne(SELECT + " WHERE CategoryName = ?", ps -> ps.setString(1, name.trim()));
    }

    @Override
    public Category insert(Category category) {
        String sql = "INSERT INTO Category (CategoryName, IsActive) VALUES (?, ?)";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, category.name());
            ps.setBoolean(2, category.active());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return new Category(keys.getInt(1), category.name(), category.active());
            }
        } catch (SQLException e) {
            throw MySqlErrors.translate(e, "Could not save category " + category.name());
        }
    }

    @Override
    public void update(Category category) {
        String sql = "UPDATE Category SET CategoryName = ?, IsActive = ? WHERE CategoryID = ?";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, category.name());
            ps.setBoolean(2, category.active());
            ps.setInt(3, category.categoryId());
            if (ps.executeUpdate() == 0) {
                throw new RuntimeException("No such category: " + category.categoryId());
            }
        } catch (SQLException e) {
            throw MySqlErrors.translate(e, "Could not update category " + category.categoryId());
        }
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private Optional<Category> queryOne(String sql, Binder binder) {
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Database error while reading Category", e);
        }
    }

    private static Category map(ResultSet rs) throws SQLException {
        return new Category(rs.getInt("CategoryID"), rs.getString("CategoryName"), rs.getBoolean("IsActive"));
    }
}
