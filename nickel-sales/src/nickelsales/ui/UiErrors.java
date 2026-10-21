package nickelsales.ui;

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

    /** The database answered, but the request failed (e.g. an out-of-date database). */
    public static final String DATABASE_ERROR =
            "A database error occurred. Try again, or contact the system administrator.";

    private UiErrors() { }

    /**
     * The message to show for {@code error}: {@link #CANNOT_CONNECT} or
     * {@link #DATABASE_ERROR} for database problems, otherwise {@code fallback}.
     */
    public static String message(Throwable error, String fallback) {
        if (isConnectionProblem(error)) {
            return CANNOT_CONNECT;
        }
        return isDatabaseError(error) ? DATABASE_ERROR : fallback;
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
