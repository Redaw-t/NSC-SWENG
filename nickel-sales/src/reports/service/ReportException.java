package reports.service;

/** A report could not be shown. The message is ready to show to the user. */
public class ReportException extends Exception {

    private static final long serialVersionUID = 1L;

    public ReportException(String message) {
        super(message);
    }

    public ReportException(String message, Throwable cause) {
        super(message, cause);
    }
}
