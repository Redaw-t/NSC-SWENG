package auth.permission;
// Permission enum representing different access rights
public enum Permission {
    PROCESS_SALE,
    MANAGE_PRODUCTS,
    UPDATE_INVENTORY,
    /** Transaction history, sales summary, top products, sales by cashier / terminal (Admin). */
    VIEW_SALES_REPORTS,
    /** Current stock, low-stock and out-of-stock reports (Admin, Inventory staff). */
    VIEW_INVENTORY_REPORTS,
    MANAGE_USERS,
    MANAGE_SYSTEM
}
