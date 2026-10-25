package shared;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Shared time rules for every module.
 *
 * - Times are stored in UTC ({@link Instant}, MySQL DATETIME(3); the JDBC URL
 *   in Database sets serverTimezone=UTC).
 * - They are SHOWN, and grouped into days / months / years, in Philippine
 *   time (Asia/Manila, UTC+8, no daylight saving). Use this class rather than
 *   the computer's own time zone, so a terminal set to another zone still
 *   prints the right date and reports still cut days at PH midnight.
 */
public final class PhTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Manila");

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.US);

    private PhTime() { }

    /** The PH date and time of an instant. */
    public static ZonedDateTime of(Instant instant) {
        return instant.atZone(ZONE);
    }

    /** Today's date in the Philippines. */
    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    /** The year in the Philippines at that instant (used in invoice numbers). */
    public static int year(Instant instant) {
        return of(instant).getYear();
    }

    /** PH midnight at the start of {@code date}, as an Instant (for date-range queries). */
    public static Instant startOf(LocalDate date) {
        return date.atStartOfDay(ZONE).toInstant();
    }

    /** "Oct 24, 2026 3:23 PM" in PH time — for receipts and screens. */
    public static String format(Instant instant) {
        return DATE_TIME.format(of(instant));
    }
}
