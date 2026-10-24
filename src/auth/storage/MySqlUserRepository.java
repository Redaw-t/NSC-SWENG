package auth.storage;

import shared.Database;

import auth.contract.User;
import auth.contract.UserRepository;
import auth.contract.UserRole;
import auth.contract.UserStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** UserRepository backed by the Users table in NickelSales_Database. */
public class MySqlUserRepository implements UserRepository {

	private static final String SELECT_COLUMNS =
	        "SELECT UserID, UserLoginName, UserPassword, UserName, UserLastname, UserRole, UserStatus FROM Users";

    @Override
    public User findById(String userId) {
        if (userId == null) {
            return null;
        }
        return queryOne(SELECT_COLUMNS + " WHERE UserID = ?", userId);
    }

    @Override
    public User findByUsername(String username) {
        if (username == null) {
            return null;
        }
        // Login name, not User ID. Trim spaces and compare case-insensitively.
        return queryOne(SELECT_COLUMNS + " WHERE LOWER(UserLoginName) = ?",
                username.trim().toLowerCase(Locale.ROOT));
    }

    @Override
    public void save(User user) {
        String sql = "INSERT INTO Users (UserID, UserLoginName, UserPassword, UserName, UserLastname, UserRole, UserStatus) "
                   + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
        	ps.setString(1, user.getUserId());
        	ps.setString(2, user.getLoginName());
        	ps.setString(3, user.getPasswordHash());
        	ps.setString(4, user.getFirstName());
        	ps.setString(5, user.getLastName());
        	ps.setString(6, UserRole.toDbCodes(user.getRoles()));
        	ps.setString(7, user.getStatus().toDbString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Could not save user " + user.getUserId(), e);
        }
    }

    @Override
    public void update(User user) {
    	String sql = "UPDATE Users SET UserLoginName = ?, UserPassword = ?, UserName = ?, UserLastname = ?, "
    	           + "UserRole = ?, UserStatus = ? WHERE UserID = ?";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
        	ps.setString(1, user.getLoginName());
        	ps.setString(2, user.getPasswordHash());
        	ps.setString(3, user.getFirstName());
        	ps.setString(4, user.getLastName());
        	ps.setString(5, UserRole.toDbCodes(user.getRoles()));
        	ps.setString(6, user.getStatus().toDbString());
        	ps.setString(7, user.getUserId());
            if (ps.executeUpdate() == 0) {
                throw new RuntimeException("No such user: " + user.getUserId());
            }
        } catch (SQLException e) {
            throw new RuntimeException("Could not update user " + user.getUserId(), e);
        }
    }

    @Override
    public void updatePassword(String userId, String newPasswordHash) {
        String sql = "UPDATE Users SET UserPassword = ? WHERE UserID = ?";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, newPasswordHash);
            ps.setString(2, userId);
            if (ps.executeUpdate() == 0) {
                throw new RuntimeException("No such user: " + userId);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Could not update password for " + userId, e);
        }
    }

    @Override
    public List<User> findAll() {
        List<User> users = new ArrayList<>();
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT_COLUMNS + " ORDER BY UserID");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                users.add(map(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Could not load users", e);
        }
        return users;
    }

    private User queryOne(String sql, String param) {
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Database error while reading Users", e);
        }
    }

    private User map(ResultSet rs) throws SQLException {
    	return new User(
    	        rs.getString("UserID"),
    	        rs.getString("UserLoginName"),
    	        rs.getString("UserPassword"),
    	        rs.getString("UserName"),
    	        rs.getString("UserLastname"),
    	        UserRole.parseDbRoles(rs.getString("UserRole")),
    	        UserStatus.fromDbString(rs.getString("UserStatus")));
    }
}