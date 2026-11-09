package auth.repository;

import shared.Database;
import shared.MasterIds;
import shared.SyncOutbox;
import shared.SyncTables;

import auth.contract.User;
import auth.contract.UserRepository;
import auth.contract.UserRole;
import auth.contract.UserStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** UserRepository backed by the Users table in NickelSales_Database. */
public class MySqlUserRepository implements UserRepository {

	private static final String SELECT_COLUMNS =
	        "SELECT UserID, UserLoginName, UserPassword, UserName, UserLastname, UserRole, UserStatus, LastSignIn FROM Users";

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
        try {
            Database.inTransaction(c -> {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, user.getUserId());
                    ps.setString(2, user.getLoginName());
                    ps.setString(3, user.getPasswordHash());
                    ps.setString(4, user.getFirstName());
                    ps.setString(5, user.getLastName());
                    ps.setString(6, UserRole.toDbCodes(user.getRoles()));
                    ps.setString(7, user.getStatus().toDbString());
                    ps.executeUpdate();
                }
                queueForSync(c, user.getUserId());
                return null;
            });
        } catch (SQLException e) {
            throw new RuntimeException("Could not save user " + user.getUserId(), e);
        }
    }

    @Override
    public void update(User user) {
    	String sql = "UPDATE Users SET UserLoginName = ?, UserPassword = ?, UserName = ?, UserLastname = ?, "
    	           + "UserRole = ?, UserStatus = ? WHERE UserID = ?";
        MasterIds.requireMain();   // shared by every terminal: changed only while the Main POS is reachable
        try {
            Database.inTransaction(c -> {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
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
                }
                queueForSync(c, user.getUserId());
                return null;
            });
        } catch (SQLException e) {
            throw new RuntimeException("Could not update user " + user.getUserId(), e);
        }
    }

    @Override
    public void updatePassword(String userId, String newPasswordHash) {
        String sql = "UPDATE Users SET UserPassword = ? WHERE UserID = ?";
        // Not limited to when the Main POS is reachable: anyone may change their own password on any
        // terminal; it reaches the others when they sync.
        try {
            Database.inTransaction(c -> {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, newPasswordHash);
                    ps.setString(2, userId);
                    if (ps.executeUpdate() == 0) {
                        throw new RuntimeException("No such user: " + userId);
                    }
                }
                queueForSync(c, userId);
                return null;
            });
        } catch (SQLException e) {
            throw new RuntimeException("Could not update password for " + userId, e);
        }
    }

    /**
     * Queues the user's row (without LastSignIn, which each terminal keeps for itself) for the other
     * terminals, in the same transaction (shared.SyncOutbox).
     */
    private static void queueForSync(Connection c, String userId) throws SQLException {
        SyncOutbox.recordRows(c, SyncTables.USER, userId, "Users", "UserID = ?", userId);
    }

    /** The next user ID from the Main POS when the terminals sync ({@link MasterIds}); null otherwise. */
    @Override
    public String nextUserId() {
        return MasterIds.next(MasterIds.USER);
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

    @Override
    public void recordSignIn(String userId) {
        // UpdatedAt = UpdatedAt stops the column's ON UPDATE CURRENT_TIMESTAMP from also changing,
        // so UpdatedAt keeps meaning "the user's record was edited", not "the user logged in".
        String sql = "UPDATE Users SET LastSignIn = CURRENT_TIMESTAMP, UpdatedAt = UpdatedAt WHERE UserID = ?";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Could not record sign in for " + userId, e);
        }
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
                UserStatus.fromDbString(rs.getString("UserStatus")),
                readLocalTime(rs, "LastSignIn"));
    }

    /**
     * Reads a date-time column exactly as MySQL shows it, with no time zone conversion.
     *
     * LastSignIn is written with CURRENT_TIMESTAMP, i.e. in the database server's own (local) time, so
     * the stored value is already the local time to show. getTimestamp() must not be used here: the JDBC
     * URL says serverTimezone=UTC, so the driver would treat that local time as UTC and the screen would
     * show it shifted by the offset (8 hours in the Philippines).
     */
    private static LocalDateTime readLocalTime(ResultSet rs, String column) throws SQLException {
        String text = rs.getString(column);   // "2026-11-04 10:30:15"
        return text == null ? null : LocalDateTime.parse(text.trim().replace(' ', 'T'));
    }
}