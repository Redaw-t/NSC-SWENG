package nickelsales.auth.account;

import nickelsales.auth.auth.AuthenticationService;
import nickelsales.auth.contract.AuditLogger;
import nickelsales.auth.contract.PasswordPolicy;
import nickelsales.auth.contract.User;
import nickelsales.auth.contract.UserRepository;
import nickelsales.auth.contract.UserRole;
import nickelsales.auth.contract.UserStatus;
import nickelsales.authz.AuthorizationService;
import nickelsales.authz.Permission;
import org.mindrot.jbcrypt.BCrypt;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Admin-only account management: create, edit, activate and deactivate users.
 * Every method takes the caller's session id and requires MANAGE_USERS.
 */
public class UserAdminService {

    private static final String ID_PREFIX = "NSC-";
    private static final int MAX_NAME_LENGTH = 30;       // Users.UserName / UserLastname are VARCHAR(30)
    private static final int MAX_LOGIN_NAME_LENGTH = 30; // Users.UserLoginName is VARCHAR(30)

    private final AuthenticationService auth;
    private final UserRepository userRepository;
    private final AuthorizationService authz;
    private final PasswordPolicy passwordPolicy;
    private final AuditLogger auditLogger;

    public UserAdminService(AuthenticationService auth, UserRepository userRepository,
                            AuthorizationService authz, PasswordPolicy passwordPolicy,
                            AuditLogger auditLogger) {
        this.auth = Objects.requireNonNull(auth, "auth");
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
        this.authz = Objects.requireNonNull(authz, "authz");
        this.passwordPolicy = Objects.requireNonNull(passwordPolicy, "passwordPolicy");
        this.auditLogger = auditLogger;
    }

    public List<User> listUsers(String sessionId) throws AccountException {
        requireAdmin(sessionId);
        return userRepository.findAll();
    }

    /**
     * Creates an Active user with a generated NSC-##### id and a generated login name
     * (first initial + last name, e.g. KChong; KChong2 if taken) and the given temporary password.
     */
    public User createUser(String sessionId, String firstName, String lastName,
                           Set<UserRole> roles, String tempPassword) throws AccountException {
        User actor = requireAdmin(sessionId);
        validateProfile(firstName, lastName, roles);
        if (!passwordPolicy.validate(tempPassword)) {
            throw new AccountException(PassPolicy.REQUIREMENTS);
        }

        User user = new User(nextUserId(), generateLoginName(firstName, lastName),
                BCrypt.hashpw(tempPassword, BCrypt.gensalt(10)),
                firstName.trim(), lastName.trim(), EnumSet.copyOf(roles), UserStatus.ACTIVE);
        userRepository.save(user);
        log("USER_CREATED", actor, user);
        return user;
    }

    /** The login name never changes on edit, so a rename does not break anyone's login. */
    public void updateUser(String sessionId, String userId, String firstName, String lastName,
                           Set<UserRole> roles) throws AccountException {
        User actor = requireAdmin(sessionId);
        validateProfile(firstName, lastName, roles);
        User target = requireUser(userId);

        if (target.hasRole(UserRole.ADMIN) && !roles.contains(UserRole.ADMIN)) {
            // An admin may not demote themselves (they would lose access mid-task
            // and could lock everyone out). Another admin has to do it.
            if (target.getUserId().equals(actor.getUserId())) {
                throw new AccountException(
                        "You cannot remove your own Admin role. Ask another administrator to do it.");
            }
            requireAnotherActiveAdmin(target);
        }
        userRepository.update(copyWith(target, firstName.trim(), lastName.trim(),
                EnumSet.copyOf(roles), target.getStatus()));
        log("USER_UPDATED", actor, target);
    }

    /** Activating also clears a Locked status. */
    public void setActive(String sessionId, String userId, boolean active) throws AccountException {
        User actor = requireAdmin(sessionId);
        User target = requireUser(userId);

        if (!active) {
            if (target.getUserId().equals(actor.getUserId())) {
                throw new AccountException("You cannot deactivate your own account.");
            }
            if (target.hasRole(UserRole.ADMIN)) {
                requireAnotherActiveAdmin(target);
            }
        }
        UserStatus status = active ? UserStatus.ACTIVE : UserStatus.INACTIVE;
        userRepository.update(copyWith(target, target.getFirstName(), target.getLastName(),
                target.getRoles(), status));
        log(active ? "USER_ACTIVATED" : "USER_DEACTIVATED", actor, target);
    }

    private User requireAdmin(String sessionId) throws AccountException {
        User actor = auth.getCurrentUser(sessionId);
        if (actor == null) {
            throw new AccountException("Your session has expired. Please log in again.");
        }
        if (!authz.checkAccess(actor, Permission.MANAGE_USERS)) {
            throw new AccountException("You do not have permission to manage users.");
        }
        return actor;
    }

    private User requireUser(String userId) throws AccountException {
        User user = userRepository.findById(userId);
        if (user == null) {
            throw new AccountException("User " + userId + " no longer exists.");
        }
        return user;
    }

    private void requireAnotherActiveAdmin(User target) throws AccountException {
        if (!target.isActive()) {
            return;
        }
        boolean otherAdmin = userRepository.findAll().stream()
                .anyMatch(u -> !u.getUserId().equals(target.getUserId())
                        && u.isActive() && u.hasRole(UserRole.ADMIN));
        if (!otherAdmin) {
            throw new AccountException("There must be at least one active administrator.");
        }
    }

    private static void validateProfile(String firstName, String lastName, Set<UserRole> roles)
            throws AccountException {
        if (isBlank(firstName) || isBlank(lastName)) {
            throw new AccountException("First name and last name are required.");
        }
        if (firstName.trim().length() > MAX_NAME_LENGTH || lastName.trim().length() > MAX_NAME_LENGTH) {
            throw new AccountException("Names can be at most " + MAX_NAME_LENGTH + " characters.");
        }
        if (roles == null || roles.isEmpty()) {
            throw new AccountException("Select at least one role.");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String nextUserId() {
        int max = 0;
        for (User u : userRepository.findAll()) {
            String id = u.getUserId();
            if (id.startsWith(ID_PREFIX)) {
                try {
                    max = Math.max(max, Integer.parseInt(id.substring(ID_PREFIX.length())));
                } catch (NumberFormatException ignored) {
                    // Not a generated id; skip it.
                }
            }
        }
        return String.format("%s%05d", ID_PREFIX, max + 1);
    }

    private String generateLoginName(String firstName, String lastName) throws AccountException {
        String surname = lastName.replaceAll("[^\\p{L}\\p{N}]", "");
        if (surname.isEmpty()) {
            throw new AccountException("Last name must contain letters or numbers.");
        }
        String base = Character.toUpperCase(firstName.trim().charAt(0)) + surname;
        // Leave room for a collision number such as the 2 in KChong2.
        base = base.substring(0, Math.min(base.length(), MAX_LOGIN_NAME_LENGTH - 3));

        String candidate = base;
        for (int n = 2; loginNameTaken(candidate); n++) {
            candidate = base + n;
        }
        return candidate;
    }

    private boolean loginNameTaken(String loginName) {
        return userRepository.findAll().stream()
                .anyMatch(u -> u.getLoginName().equalsIgnoreCase(loginName));
    }

    // The only place besides createUser that builds a User; update it if the User constructor changes.
    private static User copyWith(User u, String firstName, String lastName,
                                 Set<UserRole> roles, UserStatus status) {
        return new User(u.getUserId(), u.getLoginName(), u.getPasswordHash(),
                firstName, lastName, roles, status);
    }

    private void log(String event, User actor, User target) {
        if (auditLogger != null) {
            auditLogger.log(event + " target=" + target.getUserId(), actor.getUserId());
        }
    }
}
