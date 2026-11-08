package shared;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Opens connections to {@code nickelsales_appconfig}: the app's own configuration and state
 * (e.g. which POS terminals are set up), kept apart from the store's records in
 * NickelSales_Database ({@link Database}). Created by database/NickelSales-AppConfig.sql
 * (setup.bat / setup.sh apply it).
 *
 * Same MySQL server and login as {@link Database}: NSC_DB_USER and NSC_DB_PASSWORD. Override the
 * address with the environment variable NSC_APPCONFIG_DB_URL.
 */
public final class AppConfigDatabase {

    /** The database's name, as created by the SQL script. */
    public static final String NAME = "nickelsales_appconfig";

    private static final String URL = env("NSC_APPCONFIG_DB_URL",
            "jdbc:mysql://localhost:3306/" + NAME + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
    private static final String USER = env("NSC_DB_USER", "root");
    private static final String PASSWORD = env("NSC_DB_PASSWORD", "");

    private AppConfigDatabase() { }

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isEmpty()) ? fallback : value;
    }
}
