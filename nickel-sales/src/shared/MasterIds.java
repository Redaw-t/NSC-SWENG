package shared;

/**
 * New IDs for the records every POS shares and edits (products, users, categories, suppliers), and
 * the rule that only the Main POS's copy may be changed while it can be reached.
 *
 * The app plugs in a {@link Source} at start-up (sync.MasterIdSources): on the Main POS it hands
 * out IDs itself; on an Additional POS it asks the Main POS, so two terminals never make the same
 * ID. With no source (self-tests, a PC not set up) the repositories work out IDs as before.
 */
public final class MasterIds {

    public static final String PRODUCT = "PRODUCT";
    public static final String USER = "USER";
    public static final String CATEGORY = "CATEGORY";
    public static final String SUPPLIER = "SUPPLIER";

    /** Where IDs come from. */
    public interface Source {

        /**
         * The next ID of {@code kind}: "P-0042", "NSC-00007", or a number as text for categories and
         * suppliers. Never handed out twice.
         *
         * @throws MainPosUnavailableException an Additional POS can't reach the Main POS
         */
        String next(String kind);

        /**
         * Returns if shared records may be changed now; throws if this is an Additional POS that
         * can't reach the Main POS.
         *
         * @throws MainPosUnavailableException the Main POS can't be reached
         */
        void requireMain();
    }

    private static volatile Source source;

    private MasterIds() { }

    /** Sets (or with null, removes) where IDs come from. */
    public static void use(Source s) {
        source = s;
    }

    /** The next ID of {@code kind}, or null if no source is set (the caller works it out as before). */
    public static String next(String kind) {
        Source s = source;
        return s == null ? null : s.next(kind);
    }

    /** {@link #next} for categories and suppliers: the number, or null if no source is set. */
    public static Integer nextNumber(String kind) {
        String id = next(kind);
        return id == null ? null : Integer.valueOf(id.trim());
    }

    /**
     * Call before changing a shared record. Does nothing on the Main POS or with no source.
     *
     * @throws MainPosUnavailableException an Additional POS can't reach the Main POS
     */
    public static void requireMain() {
        Source s = source;
        if (s != null) {
            s.requireMain();
        }
    }
}
