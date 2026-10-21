package nickelsales.auth.demo;

import nickelsales.auth.account.AccountException;
import nickelsales.auth.account.PassPolicy;
import nickelsales.auth.account.UserAdminService;
import nickelsales.auth.auth.AuthenticationService;
import nickelsales.auth.contract.User;
import nickelsales.auth.contract.UserRole;
import nickelsales.authz.AuthorizationService;

import java.util.EnumSet;

/**
 * TEST HARNESS ONLY — checks UserAdminService against AuthDemo's stub repository.
 * Run after any run-demo build:  java -cp "out;lib\jbcrypt-0.4.jar" nickelsales.auth.demo.UserAdminDemo
 */
public class UserAdminDemo {

    static final String TEMP_PASSWORD = "Temp12345";

    public static void main(String[] args) throws Exception {
        AuthDemo.StubUserRepository repo = new AuthDemo.StubUserRepository();
        PassPolicy policy = new PassPolicy();
        AuthenticationService auth = new AuthenticationService(
                repo, new AuthDemo.StubSessionManager(), policy, null);
        UserAdminService admin = new UserAdminService(auth, repo, new AuthorizationService(), policy, null);

        String adminSid = auth.login("JCubillan", AuthDemo.TEST_PASSWORD).session().getSessionId();
        String cashierSid = auth.login("JDelaCruz", AuthDemo.TEST_PASSWORD).session().getSessionId();

        AuthDemo.scenario("Non-admin cannot manage users", () -> {
            expectRejected(() -> admin.listUsers(cashierSid), "cashier listing users");
            expectRejected(() -> admin.setActive(cashierSid, "NSC-00003", false), "cashier deactivating");
        });

        AuthDemo.scenario("Admin creates a user; username is first initial + last name", () -> {
            User created = admin.createUser(adminSid, " Ana ", "Reyes",
                    EnumSet.of(UserRole.CASHIER), TEMP_PASSWORD);
            AuthDemo.check(created.getUserId().equals("NSC-00006"), "expected next id NSC-00006, got " + created.getUserId());
            AuthDemo.check(created.getLoginName().equals("AReyes"), "expected AReyes, got " + created.getLoginName());
            AuthDemo.check(created.isActive(), "new user should be Active");
            AuthDemo.check(created.getFirstName().equals("Ana"), "name should be trimmed");
            AuthDemo.check(!created.getPasswordHash().equals(TEMP_PASSWORD), "password must be hashed");
            AuthDemo.check(auth.login("areyes", TEMP_PASSWORD).user().hasRole(UserRole.CASHIER), "login should work");
        });

        AuthDemo.scenario("Create rejects bad input", () -> {
            expectRejected(() -> admin.createUser(adminSid, "", "Reyes", EnumSet.of(UserRole.CASHIER), TEMP_PASSWORD), "blank name");
            expectRejected(() -> admin.createUser(adminSid, "Ben", "Cruz", EnumSet.noneOf(UserRole.class), TEMP_PASSWORD), "no role");
            expectRejected(() -> admin.createUser(adminSid, "Ben", "Cruz", EnumSet.of(UserRole.CASHIER), "weak"), "weak password");
            AuthDemo.check(repo.findAll().size() == 6, "rejected creates must not save anything");
        });

        AuthDemo.scenario("Same username gets a number suffix", () -> {
            User second = admin.createUser(adminSid, "Alma", "Reyes",
                    EnumSet.of(UserRole.CASHIER), TEMP_PASSWORD);
            AuthDemo.check(second.getLoginName().equals("AReyes2"), "expected AReyes2, got " + second.getLoginName());
        });

        AuthDemo.scenario("Admin edits name and roles; username stays the same", () -> {
            admin.updateUser(adminSid, "NSC-00006", "Anna", "Reyes-Santos",
                    EnumSet.of(UserRole.CASHIER, UserRole.INVENTORY_STAFF));
            User u = repo.findById("NSC-00006");
            AuthDemo.check(u.getFirstName().equals("Anna"), "name not updated");
            AuthDemo.check(u.hasRole(UserRole.INVENTORY_STAFF), "role not added");
            AuthDemo.check(u.getLoginName().equals("AReyes"), "login name must not change on edit");
            AuthDemo.check(auth.validateCredentials("AReyes", TEMP_PASSWORD), "edit must keep the password");
        });

        AuthDemo.scenario("Deactivate blocks login; activate restores it", () -> {
            admin.setActive(adminSid, "NSC-00006", false);
            AuthDemo.expectCode(() -> auth.login("AReyes", TEMP_PASSWORD), "INACTIVE_USER");
            admin.setActive(adminSid, "NSC-00006", true);
            AuthDemo.check(auth.login("AReyes", TEMP_PASSWORD).user().isActive(), "should be active again");
        });

        AuthDemo.scenario("Activating a locked user unlocks them", () -> {
            admin.setActive(adminSid, "NSC-00005", true);
            AuthDemo.check(auth.login("KRodriguez", AuthDemo.TEST_PASSWORD).user().isActive(), "should be unlocked");
        });

        AuthDemo.scenario("Admin cannot lock themselves out", () -> {
            expectRejected(() -> admin.setActive(adminSid, "NSC-00001", false), "deactivating self");
            expectRejected(() -> admin.updateUser(adminSid, "NSC-00001", "Jerick", "Cubillan",
                    EnumSet.of(UserRole.CASHIER)), "removing the last admin role");
        });

        System.out.println("RESULT: " + AuthDemo.passed + " passed, " + AuthDemo.failed + " failed");
        if (AuthDemo.failed > 0) {
            System.exit(1);
        }
    }

    static void expectRejected(AuthDemo.ScenarioBody call, String what) throws Exception {
        try {
            call.run();
        } catch (AccountException e) {
            return;
        }
        throw new IllegalStateException("expected rejection: " + what);
    }
}
