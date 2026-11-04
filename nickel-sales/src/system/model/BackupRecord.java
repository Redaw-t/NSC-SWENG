package system.model;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * One backup or restore that was made (or tried).
 *
 * @param backupId   database ID; 0 until saved
 * @param kind       {@link #BACKUP}, {@link #SAFETY} (taken automatically before a restore) or {@link #RESTORE}
 * @param fileName   the backup file
 * @param folderPath the folder it is in
 * @param sizeBytes  the file's size; null if there is no file
 * @param result     {@link #SUCCESS} or {@link #FAILED}
 * @param message    what went wrong; null if nothing did
 * @param userId     who did it
 */
public record BackupRecord(long backupId, Instant createdAt, String terminalId, String kind, String fileName,
                           String folderPath, Long sizeBytes, String result, String message, String userId) {

    public static final String BACKUP = "BACKUP";
    public static final String SAFETY = "SAFETY";
    public static final String RESTORE = "RESTORE";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";

    public BackupRecord {
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(terminalId, "terminalId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(folderPath, "folderPath");
        Objects.requireNonNull(result, "result");
    }

    public boolean succeeded() {
        return SUCCESS.equals(result);
    }

    /** "Backup", "Safety backup" or "Restore". */
    public String kindLabel() {
        return switch (kind) {
            case BACKUP -> "Backup";
            case SAFETY -> "Safety backup";
            case RESTORE -> "Restore";
            default -> kind;
        };
    }

    /** "Success" or "Failed". */
    public String resultLabel() {
        return succeeded() ? "Success" : "Failed";
    }

    /** The size in words, e.g. "1.4 MB"; empty if there is no file. */
    public String sizeLabel() {
        return sizeBytes == null ? "" : size(sizeBytes);
    }

    /** {@code bytes} in words, e.g. "850 KB". */
    public static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
