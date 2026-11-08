package app.install;

import shared.AppConfigDatabase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * nickelsales_appconfig.AppSettings: this installation's own settings (which terminal it is, the API
 * key). Database problems come out as RuntimeException (cause: the SQLException).
 */
public final class AppSettings {

    public static final String THIS_TERMINAL_ID = "ThisTerminalID";
    public static final String API_KEY = "ApiKey";

    /** The setting's value, if it is set. */
    public Optional<String> get(String key) {
        try (Connection c = AppConfigDatabase.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT SettingValue FROM AppSettings WHERE SettingKey = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String value = rs.getString(1);
                    return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Could not read the app setting " + key, e);
        }
    }

    /** Sets a value inside {@code c} (part of the caller's transaction). */
    static void put(Connection c, String key, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO AppSettings (SettingKey, SettingValue) VALUES (?, ?) "
                        + "ON DUPLICATE KEY UPDATE SettingValue = VALUES(SettingValue)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }
}
