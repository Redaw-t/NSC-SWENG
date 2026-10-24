package auth.account;

import auth.contract.PasswordPolicy;

public class PassPolicy implements PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    // jBCrypt only uses the first 72 bytes, so longer passwords would be silently truncated.
    public static final int MAX_LENGTH = 72;
    public static final String REQUIREMENTS =
            "Password must be 8-72 characters and include an uppercase letter and a number.";

    @Override
    public boolean validate(String password) {
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            return false;
        }

        boolean hasUpper = false;
        boolean hasDigit = false;
        for (char c : password.toCharArray()) {
            if (Character.isUpperCase(c)) {
                hasUpper = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            }
        }
        return hasUpper && hasDigit;
    }
}