package auth.account;

import java.util.function.Predicate;

/**
 * How usernames are made, the same everywhere (Manage users > Add user, and the admin account made
 * in Installation Setup): the first name's initial, upper case, then the last name without spaces
 * or symbols, e.g. Jerick Cubillan = JCubillan. If that is taken, a number is added: JCubillan2,
 * JCubillan3 ...
 */
public final class LoginNames {

    /** Users.UserLoginName is VARCHAR(30). */
    public static final int MAX_LENGTH = 30;

    private LoginNames() { }

    /**
     * The username for this person.
     *
     * @param taken true for a username someone already has (compare ignoring case)
     * @throws AccountException the first or last name has no letters or numbers to build it from
     */
    public static String generate(String firstName, String lastName, Predicate<String> taken)
            throws AccountException {
        String first = firstName == null ? "" : firstName.trim();
        if (first.isEmpty()) {
            throw new AccountException("First name is required.");
        }
        String surname = (lastName == null ? "" : lastName).replaceAll("[^\\p{L}\\p{N}]", "");
        if (surname.isEmpty()) {
            throw new AccountException("Last name must contain letters or numbers.");
        }
        String base = Character.toUpperCase(first.charAt(0)) + surname;
        // Leave room for a collision number such as the 2 in KChong2.
        base = base.substring(0, Math.min(base.length(), MAX_LENGTH - 3));

        String candidate = base;
        for (int n = 2; taken.test(candidate); n++) {
            candidate = base + n;
        }
        return candidate;
    }
}
