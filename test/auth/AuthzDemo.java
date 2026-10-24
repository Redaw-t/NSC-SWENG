package auth;

import auth.contract.User;
import auth.contract.UserRepository;
import auth.permission.AuthorizationService;
import auth.permission.Permission;

public class AuthzDemo {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        UserRepository userRepo = new AuthDemo.StubUserRepository();
        AuthorizationService authz = new AuthorizationService();

        String[] ids = {"NSC-00001", "NSC-00002", "NSC-00003", "NSC-00004", "NSC-00005"};
        for (String id : ids){
            User user = userRepo.findById(id);
            System.out.println(user.getUserId() + " has roles: " + user.getRoles() + " status: " + user.getStatus());
            for (Permission p : Permission.values()) {
                String result = authz.checkAccess(user, p) ? "ALLOWED" : "DENIED";
                System.out.println("  " + p + " = " + result);
            }
        }

        System.out.println();
        User admin = userRepo.findById("NSC-00001");
        User cashier = userRepo.findById("NSC-00002");
        User inventory = userRepo.findById("NSC-00003");
        User inactive = userRepo.findById("NSC-00004");
        User locked = userRepo.findById("NSC-00005");

        check("Admin can manage the system", authz.checkAccess(admin, Permission.MANAGE_SYSTEM));
        check("Admin (multi-role) can also process sales", authz.checkAccess(admin, Permission.PROCESS_SALE));
        check("Cashier can process sales", authz.checkAccess(cashier, Permission.PROCESS_SALE));
        check("Cashier is DENIED managing users", !authz.checkAccess(cashier, Permission.MANAGE_USERS));
        check("Cashier is DENIED updating inventory", !authz.checkAccess(cashier, Permission.UPDATE_INVENTORY));
        check("Inventory staff can update inventory", authz.checkAccess(inventory, Permission.UPDATE_INVENTORY));
        check("Inventory staff is DENIED processing sales", !authz.checkAccess(inventory, Permission.PROCESS_SALE));
        check("Inactive cashier is DENIED even PROCESS_SALE", !authz.checkAccess(inactive, Permission.PROCESS_SALE));
        check("Locked cashier is DENIED even PROCESS_SALE", !authz.checkAccess(locked, Permission.PROCESS_SALE));
        check("No user (null) is DENIED", !authz.checkAccess(null, Permission.PROCESS_SALE));

        System.out.println("RESULTS: " + passed + " tests passed, " + failed + " tests failed.");
        if (failed > 0) {
            System.exit(1);
        }
    }

       private static void check(String description, boolean condition) {
        System.out.println((condition ? "[PASS] " : "[FAIL] ") + description);
        if (condition) {
            passed++;
        } else {
            failed++;
        }
    }

}
