package nickelsales.authz;

import nickelsales.auth.contract.User;
import nickelsales.auth.contract.UserRole;

import java.util.EnumSet;
import java.util.Set;
import java.util.Map;

public class AuthorizationService {
    
    //defines the permission matrix for each user role
    private static final Map<UserRole, Set<Permission>> MATRIX = Map.of(
        UserRole.ADMIN, EnumSet.allOf(Permission.class),
        UserRole.CASHIER, EnumSet.of(Permission.PROCESS_SALE),
        UserRole.INVENTORY_STAFF, EnumSet.of(Permission.UPDATE_INVENTORY, Permission.MANAGE_PRODUCTS, Permission.VIEW_REPORTS));

    //returns the set of permissions for a given set of user roles
    public Set<Permission> permissionsForUser(Set<UserRole> roles) {
        Set<Permission> result = EnumSet.noneOf(Permission.class);
        if (roles == null || roles.isEmpty()) {
            return result;
        }
        for (UserRole role : roles) {
            result.addAll(MATRIX.getOrDefault(role, Set.of()));
        }
        return result;
}

    //returns true if the user has the specified permission, false otherwise
    public boolean hasPermission (Set<UserRole> roles, Permission permission) {
        return permission != null && permissionsForUser(roles).contains(permission);
    }

    //returns true if the user is active and has the specified permission, false otherwise
    public boolean checkAccess(User user, Permission permmission) {
        return user != null && user.isActive() && hasPermission(user.getRoles(), permmission);
    }
}