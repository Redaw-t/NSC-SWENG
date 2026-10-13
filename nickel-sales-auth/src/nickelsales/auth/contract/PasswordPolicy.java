package nickelsales.auth.contract;

/**
 * CONTRACT ONLY — the password rules used when CREATING accounts or CHANGING
 * passwords (UserAccountService). Sprint 1 login only VERIFIES passwords
 * (bcrypt compare inside AuthenticationService), but the policy is wired in
 * so both services enforce the same rules.
 */
public interface PasswordPolicy {

    /** @return true when the password satisfies the rules (min length, uppercase, number). */
    boolean validate(String password);
}
