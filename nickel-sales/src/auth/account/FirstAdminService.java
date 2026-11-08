package auth.account;

import auth.contract.AuditLogger;
import auth.contract.PasswordPolicy;
import auth.contract.User;
import auth.contract.UserRepository;
import auth.contract.UserRole;
import auth.contract.UserStatus;
import org.mindrot.jbcrypt.BCrypt;

import java.util.EnumSet;
import java.util.Objects;

/**
 * Installation Setup on the Main POS: creates the store's first account, the administrator, as user
 * {@link #ADMIN_ID} (NSC-00001) with every role (Admin, Cashier, Inventory). The database starts with
 * no users at all, so this is the only way in; everyone else is added by this admin in User Management.
 *
 * <p>Setup asks for the admin's first and last name; the username is made from them the same way as
 * Manage users > Add user ({@link LoginNames}, e.g. Jerick Cubillan = JCubillan) and shown before the
 * password is chosen.
 *
 * <p>Safe to run again: if NSC-00001 already exists (e.g. setup was interrupted after this step), it is
 * replaced, since nobody can have signed in before setup finished.
 */
public final class FirstAdminService {

    public static final String ADMIN_ID = "NSC-00001";
    private static final int MAX_NAME = 30;                    // Users.UserName / UserLastname

    private final UserRepository users;
    private final PasswordPolicy policy;
    private final AuditLogger audit;   // may be null

    public FirstAdminService(UserRepository users, PasswordPolicy policy, AuditLogger audit) {
        this.users = Objects.requireNonNull(users, "users");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.audit = audit;
    }

    /**
     * Checks the admin's names and returns the username they will sign in with (first initial + last
     * name, e.g. JCubillan). Nothing is saved.
     *
     * @throws AccountException a name is missing, too long, or has no letters or numbers
     * @throws RuntimeException database problem
     */
    public String usernameFor(String firstName, String lastName) throws AccountException {
        required(firstName, "first name");
        required(lastName, "last name");
        // NSC-00001's own username (from an interrupted setup) does not count as taken
        return LoginNames.generate(firstName, lastName, name -> {
            User other = users.findByUsername(name);
            return other != null && !ADMIN_ID.equals(other.getUserId());
        });
    }

    /**
     * Saves the administrator with the username {@link #usernameFor} gives for these names. Every
     * problem comes back as an {@link AccountException} whose message can be shown as is.
     *
     * @return the saved user (NSC-00001)
     * @throws AccountException a name is wrong, the password breaks the rules, or the two do not match
     * @throws RuntimeException database problem
     */
    public User create(String firstName, String lastName, String password, String confirm) throws AccountException {
        String login = usernameFor(firstName, lastName);
        if (password == null || password.isEmpty()) {
            throw new AccountException("Enter a password for the admin account.");
        }
        if (!policy.validate(password)) {
            throw new AccountException(PassPolicy.REQUIREMENTS);
        }
        if (!password.equals(confirm)) {
            throw new AccountException("The two passwords do not match. Type them again.");
        }

        User admin = new User(ADMIN_ID, login, BCrypt.hashpw(password, BCrypt.gensalt(10)),
                firstName.trim(), lastName.trim(),
                EnumSet.of(UserRole.ADMIN, UserRole.CASHIER, UserRole.INVENTORY_STAFF), UserStatus.ACTIVE);
        if (users.findById(ADMIN_ID) == null) {
            users.save(admin);
        } else {
            users.update(admin);   // a setup that was interrupted after this step: take what was entered now
        }
        if (audit != null) {
            try {
                audit.log("USER_CREATED " + ADMIN_ID + " (administrator, Installation Setup)", ADMIN_ID);
            } catch (RuntimeException e) {
                System.err.println("[AUDIT] could not log the first admin: " + e);
            }
        }
        return admin;
    }

    private static String required(String value, String what) throws AccountException {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) {
            throw new AccountException("Enter the admin's " + what + ".");
        }
        if (v.length() > MAX_NAME) {
            throw new AccountException("The " + what + " can be at most " + MAX_NAME + " characters.");
        }
        return v;
    }
}
