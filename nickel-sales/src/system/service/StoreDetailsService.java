package system.service;

import auth.contract.AuditLogger;
import auth.contract.User;
import pos.receipt.StoreInfo;
import system.model.StoreDetailsRecord;
import system.repository.StoreDetailsRepository;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * The store details printed at the top of every receipt (name, address, proprietor, TIN, phone),
 * kept as a log in the store database (StoreDetails): every change adds a row with who made it
 * (Performed By), and the newest row is the one in use. Nothing is ever edited or deleted.
 *
 * <ul>
 *   <li>{@link #registerAtInstallation}: Installation Setup on the Main POS, right after the first admin
 *       is created; the row is performed by that admin (NSC-00001).</li>
 *   <li>{@link #change}: Settings &gt; Store Details, Admin only (MANAGE_SYSTEM, checked here).</li>
 *   <li>{@link #current}: what receipts and labels print; read each time, so a change shows on the next
 *       receipt without restarting.</li>
 * </ul>
 *
 * Before anything is saved (older installs, developer PCs), the details come from the settings file /
 * environment variables (NSC_STORE_*), else Nickel Sales Center's own ({@link StoreInfo}).
 */
public final class StoreDetailsService {

    public static final int MAX_NAME = 100;          // the StoreDetails columns' sizes
    public static final int MAX_ADDRESS = 255;
    public static final int MAX_PROPRIETOR = 100;
    public static final int MAX_TIN = 30;
    public static final int MAX_PHONE = 30;
    private static final int HISTORY_LIMIT = 500;

    private final StoreDetailsRepository repository;
    private final SystemAccess access;
    private final AuditLogger audit;                 // may be null
    private final String terminalId;
    private final StoreInfo fallback;
    private final Clock clock;
    private volatile StoreInfo lastKnown;            // the newest row read, for when the database is down

    /**
     * @param fallback the details used until the first row is saved (from the settings file)
     */
    public StoreDetailsService(StoreDetailsRepository repository, SystemAccess access, AuditLogger audit,
                               String terminalId, StoreInfo fallback, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.access = Objects.requireNonNull(access, "access");
        this.audit = audit;
        this.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The details in use: the newest row of the log, else the fallback. Never fails: if the database
     * cannot be read, the last details read (or the fallback) are used, so a sale can still print.
     */
    public StoreInfo current() {
        try {
            StoreInfo now = repository.latest().map(StoreDetailsRecord::store).orElse(fallback);
            lastKnown = now;
            return now;
        } catch (RuntimeException e) {
            System.err.println("[STORE] could not read the store details (" + e + "); using the last known ones");
            StoreInfo known = lastKnown;
            return known == null ? fallback : known;
        }
    }

    /** True once any store details have been saved in the database. */
    public boolean registered() {
        return repository.latest().isPresent();
    }

    /**
     * Every change, newest first (Admin only).
     *
     * @throws SystemException not signed in, or not an admin
     */
    public List<StoreDetailsRecord> history(String sessionId) throws SystemException {
        access.require(sessionId);
        try {
            return repository.history(HISTORY_LIMIT);
        } catch (RuntimeException e) {
            throw new SystemException("The store details history could not be loaded. Try again.", e);
        }
    }

    /**
     * Settings &gt; Store Details: saves new details as a new row, performed by the signed-in admin.
     *
     * @throws SystemException not an admin, a detail is wrong, nothing changed, or it could not be saved;
     *                         the message can be shown as is
     */
    public StoreDetailsRecord change(String sessionId, String name, String address, String proprietor,
                                     String tin, String phone) throws SystemException {
        User actor = access.require(sessionId);
        StoreInfo details = checked(name, address, proprietor, tin, phone);
        if (details.equals(current())) {
            throw new SystemException("Nothing was changed. Edit a detail, then save.");
        }
        try {
            shared.MasterIds.requireMain();   // every terminal prints them: changed only while the Main POS is reachable
        } catch (shared.MainPosUnavailableException e) {
            throw new SystemException(e.getMessage(), e);
        }
        return save(details, actor.getUserId(), "STORE_DETAILS_CHANGED");
    }

    /**
     * Installation Setup on the Main POS: the store's first details, performed by the admin account
     * setup has just created. Only for Installation Setup, which runs before anyone can sign in.
     *
     * @param adminId the first admin's user ID (NSC-00001)
     * @throws SystemException a detail is wrong, or it could not be saved
     */
    public StoreDetailsRecord registerAtInstallation(String adminId, String name, String address,
                                                     String proprietor, String tin, String phone)
            throws SystemException {
        if (adminId == null || adminId.isBlank()) {
            throw new SystemException("Create the admin account first.");
        }
        return save(checked(name, address, proprietor, tin, phone), adminId.trim(), "STORE_DETAILS_REGISTERED");
    }

    private StoreDetailsRecord save(StoreInfo details, String userId, String event) throws SystemException {
        StoreDetailsRecord saved;
        try {
            saved = repository.add(new StoreDetailsRecord(0, details, userId, clock.instant(), terminalId));
        } catch (RuntimeException e) {
            throw new SystemException("The store details could not be saved. Check that MySQL is running, "
                    + "then try again.", e);
        }
        lastKnown = details;
        if (audit != null) {
            try {
                audit.log(event + " " + details.name(), userId);
            } catch (RuntimeException e) {
                System.err.println("[AUDIT] could not log " + event + ": " + e);
            }
        }
        return saved;
    }

    /** The details trimmed and checked; the store name is required, the rest may be blank. */
    static StoreInfo checked(String name, String address, String proprietor, String tin, String phone)
            throws SystemException {
        String n = trim(name);
        if (n.isEmpty()) {
            throw new SystemException("Enter the store name.");
        }
        fits(n, MAX_NAME, "store name");
        fits(trim(address), MAX_ADDRESS, "address");
        fits(trim(proprietor), MAX_PROPRIETOR, "proprietor");
        fits(trim(tin), MAX_TIN, "TIN");
        fits(trim(phone), MAX_PHONE, "phone number");
        return new StoreInfo(n, trim(address), trim(proprietor), trim(tin), trim(phone));
    }

    private static void fits(String value, int max, String what) throws SystemException {
        if (value.length() > max) {
            throw new SystemException("The " + what + " can be at most " + max + " characters.");
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
