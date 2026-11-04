package system.model;

import java.time.LocalDate;

/**
 * What the Activity Logs screen asks for. Every part is optional (null = no limit on it).
 *
 * @param from    first day, in Philippine time
 * @param to      last day (included), in Philippine time
 * @param userId  only this user's actions
 * @param module  only this module (see {@link AuditEvent#MODULES})
 * @param keyword found anywhere in the action, user, target, details or values
 */
public record ActivityFilter(LocalDate from, LocalDate to, String userId, String module, String keyword) {

    /** Everything. */
    public static ActivityFilter all() {
        return new ActivityFilter(null, null, null, null, null);
    }

    public ActivityFilter {
        userId = blankToNull(userId);
        module = blankToNull(module);
        keyword = blankToNull(keyword);
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("The From date is after the To date.");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
