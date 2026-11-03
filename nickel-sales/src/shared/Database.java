package shared;

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

    /**
     * Work done inside one database transaction. {@code E} is the work's own
     * checked exception (e.g. InsufficientStockException); use
     * RuntimeException when there is none.
     */
    @FunctionalInterface
    public interface TransactionWork<T, E extends Exception> {
        T run(Connection tx) throws E, SQLException;
    }

    /**
     * Runs {@code work} as ONE all-or-nothing transaction: everything it writes
     * is committed together, or — if it throws anything — rolled back so the
     * database is exactly as before. Every module that writes as part of the
     * same action (e.g. POS saving a sale and Inventory recording the
     * stock-out) must use the {@code tx} connection it is given and must not
     * commit, roll back or close it.
     */
    public static <T, E extends Exception> T inTransaction(TransactionWork<T, E> work) throws E, SQLException {
        try (Connection tx = getConnection()) {
            tx.setAutoCommit(false);
            try {
                T result = work.run(tx);
                tx.commit();
                return result;
            } catch (Throwable t) {
                try {
                    tx.rollback();
                } catch (SQLException rollbackFailure) {
                    t.addSuppressed(rollbackFailure);
                }
                throw t;
            }
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isEmpty()) ? fallback : value;
    }
}