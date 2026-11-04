package reports.model;

import shared.PhTime;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * The days a report covers, in PHILIPPINE time: a day, a month, a year or a custom range.
 * Every sales report takes one of these, so there is one method per report instead of one
 * per period (no salesForDay / salesForMonth / ...).
 *
 * <p>Days are cut at PH midnight, whatever time zone the terminal is set to: a sale at
 * 23:30 PH on Nov 4 (15:30 UTC) belongs to Nov 4. {@link #start()} and {@link #end()} turn
 * the dates into the instants that {@code SaleHistory.findCompleted(from, to)} expects.
 *
 * @param from first day, included
 * @param to   last day, included
 */
public record ReportPeriod(Kind kind, LocalDate from, LocalDate to) {

    /** What the period is; decides the label and how the sales summary is broken down. */
    public enum Kind {
        DAY("Daily"), MONTH("Monthly"), YEAR("Yearly"), CUSTOM("Custom range");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US);

    public ReportPeriod {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("The From date is after the To date.");
        }
    }

    /** Today in the Philippines (the default of every report). */
    public static ReportPeriod today() {
        return day(PhTime.today());
    }

    public static ReportPeriod day(LocalDate date) {
        return new ReportPeriod(Kind.DAY, date, date);
    }

    public static ReportPeriod month(YearMonth month) {
        return new ReportPeriod(Kind.MONTH, month.atDay(1), month.atEndOfMonth());
    }

    public static ReportPeriod year(int year) {
        return new ReportPeriod(Kind.YEAR, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
    }

    /** From {@code from} to {@code to}, both days included. */
    public static ReportPeriod custom(LocalDate from, LocalDate to) {
        return new ReportPeriod(Kind.CUSTOM, from, to);
    }

    /**
     * What the screen's period controls mean: {@code day} is the chosen date (any day of the
     * month or year for Monthly / Yearly); {@code to} is only used for a custom range.
     *
     * @throws IllegalArgumentException a date is missing, or From is after To
     */
    public static ReportPeriod of(Kind kind, LocalDate day, LocalDate to) {
        Objects.requireNonNull(kind, "kind");
        if (day == null) {
            throw new IllegalArgumentException("Choose a date.");
        }
        return switch (kind) {
            case DAY -> day(day);
            case MONTH -> month(YearMonth.from(day));
            case YEAR -> year(day.getYear());
            case CUSTOM -> {
                if (to == null) {
                    throw new IllegalArgumentException("Choose the To date.");
                }
                yield custom(day, to);
            }
        };
    }

    /** PH midnight at the start of {@link #from}. */
    public Instant start() {
        return PhTime.startOf(from);
    }

    /** PH midnight at the end of {@link #to} (the start of the next day): NOT included. */
    public Instant end() {
        return PhTime.startOf(to.plusDays(1));
    }

    /** True if {@code instant} falls in the period: {@code start() <= instant < end()}. */
    public boolean contains(Instant instant) {
        return !instant.isBefore(start()) && instant.isBefore(end());
    }

    /** "Nov 4, 2026", "November 2026", "2026" or "Nov 1, 2026 - Nov 4, 2026". */
    public String label() {
        return switch (kind) {
            case DAY -> DAY_LABEL.format(from);
            case MONTH -> MONTH_LABEL.format(from);
            case YEAR -> String.valueOf(from.getYear());
            case CUSTOM -> from.equals(to) ? DAY_LABEL.format(from)
                    : DAY_LABEL.format(from) + " - " + DAY_LABEL.format(to);
        };
    }
}
