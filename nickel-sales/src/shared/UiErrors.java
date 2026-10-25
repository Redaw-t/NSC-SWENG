package shared;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;

/**
 * Turns unexpected errors into messages a cashier or admin can act on.
 *
 * Repositories wrap database failures in a RuntimeException whose cause is the
 * SQLException, so screens catch RuntimeException and pass it here:
 * <pre>
 *   } catch (RuntimeException e) {
 *       showError(UiErrors.message(e, "Something went wrong. Try again, ..."));
 *   }
 * </pre>
 */
public final class UiErrors {

    /** MySQL is not running, not reachable, or refused our login. */
    public static final String CANNOT_CONNECT =
            "Can't connect to the database. Check that MySQL is running, then try again.";

    /** The database answered, but the request failed. */
    public static final String DATABASE_ERROR =
            "A database error occurred. Try again, or contact the system administrator.";

    /** A table or column the app needs is missing: the database was made by an older script. */
    public static final String DATABASE_OUT_OF_DATE =
            "The database is out of date. Close the app, double-click setup.bat (in the nickel-sales "
                    + "folder) to update it, then start the app again. Your data is kept.";

    /** MySQL error codes for a missing table (1146) or column (1054). */
    private static final int NO_SUCH_TABLE = 1146;
    private static final int NO_SUCH_COLUMN = 1054;

    private UiErrors() { }

    /**
     * The message to show for {@code error}: {@link #CANNOT_CONNECT},
     * {@link #DATABASE_OUT_OF_DATE} or {@link #DATABASE_ERROR} for database
     * problems, otherwise {@code fallback}. Database problems are also printed
     * to the console with their real cause, for whoever has to fix them.
     */
    public static String message(Throwable error, String fallback) {
        if (isConnectionProblem(error)) {
            logCause(error);
            return CANNOT_CONNECT;
        }
        if (isOutOfDate(error)) {
            logCause(error);
            return DATABASE_OUT_OF_DATE;
        }
        if (isDatabaseError(error)) {
            logCause(error);
            return DATABASE_ERROR;
        }
        return fallback;
    }

    /** True if a table or column the app needs does not exist (run setup to update the database). */
    public static boolean isOutOfDate(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql
                    && (sql.getErrorCode() == NO_SUCH_TABLE || sql.getErrorCode() == NO_SUCH_COLUMN)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** Prints the deepest cause, e.g. "Unknown column 'ReferenceNo' in 'field list'". */
    private static void logCause(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        System.err.println("[DATABASE] " + error.getMessage()
                + (root != error ? " - cause: " + root : ""));
    }

    /** True if the database could not be reached or would not let us in. */
    public static boolean isConnectionProblem(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof SocketTimeoutException
                    || t instanceof UnknownHostException) {
                return true;
            }
            if (t instanceof SQLException sql) {
                String state = sql.getSQLState();
                // 08xxx = connection errors, 28xxx = login refused (wrong MySQL password),
                // 1049 = the NickelSales_Database database does not exist.
                if ((state != null && (state.startsWith("08") || state.startsWith("28")))
                        || sql.getErrorCode() == 1049) {
                    return true;
                }
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** True if the error came from the database at all. */
    public static boolean isDatabaseError(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
