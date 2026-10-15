package nickelsales.data;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Opens connections to the local MySQL database.
 *
 * Defaults match a standard local install. Override without editing code by
 * setting the environment variables NSC_DB_URL, NSC_DB_USER, NSC_DB_PASSWORD
 * (setup.bat offers to save NSC_DB_PASSWORD for you; in Eclipse/IntelliJ you
 * can also set them in the run configuration's Environment settings).
 * There is deliberately no default password: never commit a real one.
 */
public final class Database {

    private static final String URL = env("NSC_DB_URL",
            "jdbc:mysql://localhost:3306/NickelSales_Database?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
    private static final String USER = env("NSC_DB_USER", "root");
    private static final String PASSWORD = env("NSC_DB_PASSWORD", "");

    private Database() { }

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isEmpty()) ? fallback : value;
    }
}