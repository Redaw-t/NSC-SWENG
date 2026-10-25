package auth.account;

import auth.login.AuthenticationService;
import auth.contract.AuditLogger;
import auth.contract.PasswordPolicy;
import auth.contract.User;
import auth.contract.UserRepository;
import org.mindrot.jbcrypt.BCrypt;

import java.util.Objects;

public class UserAccountService {

    private final AuthenticationService auth;
    private final UserRepository userRepository;
    private final PasswordPolicy passwordPolicy;
    private final AuditLogger auditLogger;

    public UserAccountService(AuthenticationService auth, UserRepository userRepository,
                              PasswordPolicy passwordPolicy, AuditLogger auditLogger) {
        this.auth = Objects.requireNonNull(auth, "auth");
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
        this.passwordPolicy = Objects.requireNonNull(passwordPolicy, "passwordPolicy");
        this.auditLogger = auditLogger;
    }

    public void changePassword(String sessionId, String currentPassword,
                               String newPassword, String confirmPassword) throws PasswordChangeException {
        User user = auth.getCurrentUser(sessionId);
        if (user == null || !user.isActive()) {
            throw new PasswordChangeException("Your session has expired. Please log in again.");
        }

        if (currentPassword == null || newPassword == null || confirmPassword == null
                || currentPassword.isEmpty() || newPassword.isEmpty() || confirmPassword.isEmpty()) {
            throw new PasswordChangeException("Fill in all password fields.");
        }
        if (!newPassword.equals(confirmPassword)) {
            throw new PasswordChangeException("New password and confirmation do not match.");
        }

        // Verified before the policy so the rules can't be probed without knowing the password.
        if (!auth.validateCredentials(user.getUsername(), currentPassword)) {
            log("PASSWORD_CHANGE_FAILED", user.getUserId());
            throw new PasswordChangeException("Current password is incorrect.");
        }

        if (!passwordPolicy.validate(newPassword)) {
            throw new PasswordChangeException(PassPolicy.REQUIREMENTS);
        }
        if (newPassword.equals(currentPassword)) {
            throw new PasswordChangeException("New password must be different from the current password.");
        }

        userRepository.updatePassword(user.getUserId(), BCrypt.hashpw(newPassword, BCrypt.gensalt(10)));
        log("PASSWORD_CHANGED", user.getUserId());
    }

    private void log(String event, String userId) {
        if (auditLogger != null) {
            auditLogger.log(event, userId);
        }
    }
}