package inventory;

import auth.contract.User;
import auth.contract.UserRole;
import auth.contract.UserStatus;
import inventory.contract.InsufficientStockException;
import inventory.contract.StockLine;
import inventory.contract.StockOverview;
import inventory.contract.StockSnapshot;
import inventory.model.MovementType;
import inventory.model.StockLevel;
import inventory.model.StockMovement;
import inventory.model.StockReason;
import inventory.model.StockRow;
import inventory.model.StockStatus;
import inventory.repository.MySqlInventoryRepository;
import inventory.service.InventoryAccess;
import inventory.service.InventoryException;
import inventory.service.InventoryService;
import inventory.service.InventoryStockLevels;
import shared.Database;
import shared.Quantity;
import stubs.InMemoryInventoryRepository;
import stubs.StubInventory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Self-test for the Inventory module (run by {@code run-demo --scripted}).
 *
 * Part 1 and 2 need nothing: the model rules, and the service rules against an in-memory
 * repository. Part 3 uses MySQL (tables Inventory and StockMovement from
 * database/NickelSales-Database-Config.sql); it is skipped when MySQL is not reachable.
 * Prints "RESULT: N passed, M failed" and exits 1 on any failure.
 */
public final class InventoryCheck {

    private static final String SEED_HASH = "$2a$10$TjKMuyc2JIk0Kx/PfVAjkeGLmg3O2twxiX.3iCLg1OqhS762FzYSW";
    private static final User CLERK = new User("NSC-00003", "MSantaAna", SEED_HASH, "Maria", "Santa Ana",
            EnumSet.of(UserRole.INVENTORY_STAFF), UserStatus.ACTIVE);

    private static int passed;
    private static int failed;

    private InventoryCheck() { }

    public static void main(String[] args) {
        modelRules();
        serviceRules();
        mysqlRules();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---- part 1: model -------------------------------------------------------

    private static void modelRules() {
        section("Model");
        check("status: nothing left is Out of Stock", StockStatus.of(q("0"), q("5")) == StockStatus.OUT_OF_STOCK);
        check("status: at the reorder level is Low Stock", StockStatus.of(q("5"), q("5")) == StockStatus.LOW_STOCK);
        check("status: 4 of 8 is Low Stock (mockup row 2)", StockStatus.of(q("4"), q("8")) == StockStatus.LOW_STOCK);
        check("status: 24 of 10 is In Stock (mockup row 1)", StockStatus.of(q("24"), q("10")) == StockStatus.IN_STOCK);
        check("status: reorder level 0 never shows Low Stock", StockStatus.of(q("1"), q("0")) == StockStatus.IN_STOCK);

        Instant now = Instant.now();
        check("movement: before + quantity must equal after",
                throwsIllegal(() -> new StockMovement(0, "P", MovementType.STOCK_IN, q("5"), q("1"), q("9"),
                        null, null, null, "U", now)));
        check("movement: a stock-in cannot be negative",
                throwsIllegal(() -> new StockMovement(0, "P", MovementType.STOCK_IN, q("-5"), q("10"), q("5"),
                        null, null, null, "U", now)));
        check("movement: a sale cannot be positive",
                throwsIllegal(() -> new StockMovement(0, "P", MovementType.SALE, q("2"), q("1"), q("3"),
                        null, "T01-2026-000001", null, null, now)));
        check("movement: zero is refused",
                throwsIllegal(() -> new StockMovement(0, "P", MovementType.ADJUSTMENT, q("0"), q("1"), q("1"),
                        StockReason.OTHER, null, "x", "U", now)));
        check("level: negative stock is refused",
                throwsIllegal(() -> new StockLevel("P", q("-1"), q("0"), now)));
        check("reasons: stock out offers Sale, Damage, Transfer, Other",
                StockReason.forStockOut().equals(List.of(StockReason.SALE, StockReason.DAMAGE,
                        StockReason.TRANSFER, StockReason.OTHER)));
        check("reasons: adjust offers Count correction, Damage, Other",
                StockReason.forAdjustment().equals(List.of(StockReason.COUNT_CORRECTION, StockReason.DAMAGE,
                        StockReason.OTHER)));
    }

    // ---- part 2: service rules, no database ----------------------------------

    private static final class Fixture {
        final StubInventory products = new StubInventory();
        final InMemoryInventoryRepository repo = new InMemoryInventoryRepository();
        final List<String> audit = new ArrayList<>();
        final InventoryService service;

        Fixture() {
            InventoryAccess access = sessionId -> {
                if ("expired".equals(sessionId)) {
                    throw new InventoryException("Your session has expired. Please log in again.");
                }
                if ("cashier".equals(sessionId)) {
                    throw new InventoryException("You do not have permission to manage inventory.");
                }
                return CLERK;
            };
            service = new InventoryService(repo, products, access,
                    (event, userId) -> audit.add(event + " by " + userId));
        }

        BigDecimal onHand(String productId) {
            return repo.find(productId).map(StockLevel::quantityOnHand).orElse(BigDecimal.ZERO);
        }
    }

    private static void serviceRules() {
        section("Service rules (in-memory)");
        try {
            Fixture f = new Fixture();

            // stock in
            StockLevel in = f.service.stockIn("s", "P-0001", q("20"), "  PO-001  ");
            check("stock in adds to stock", in.quantityOnHand().compareTo(q("20")) == 0
                    && f.onHand("P-0001").compareTo(q("20")) == 0);
            StockMovement m = f.repo.movements().get(0);
            check("stock in writes a movement: +20, before 0, after 20, note trimmed, who did it",
                    m.type() == MovementType.STOCK_IN && m.quantity().compareTo(q("20")) == 0
                            && m.quantityBefore().signum() == 0 && m.quantityAfter().compareTo(q("20")) == 0
                            && "PO-001".equals(m.note()) && "NSC-00003".equals(m.performedBy()));
            check("stock in is written to the audit log", f.audit.size() == 1 && f.audit.get(0).startsWith("STOCK_IN P-0001"));
            f.service.stockIn("s", "P-0004", q("1.5"), null);
            check("stock in accepts a fraction for a product sold by kg", f.onHand("P-0004").compareTo(q("1.5")) == 0);

            int movementsBefore = f.repo.movements().size();
            check("stock in refuses zero", rejects(() -> f.service.stockIn("s", "P-0001", q("0"), null), "more than zero"));
            check("stock in refuses a negative quantity", rejects(() -> f.service.stockIn("s", "P-0001", q("-5"), null), "more than zero"));
            check("stock in refuses more than 3 decimals", rejects(() -> f.service.stockIn("s", "P-0001", new BigDecimal("1.2345"), null), "decimal"));
            check("stock in refuses an empty quantity", rejects(() -> f.service.stockIn("s", "P-0001", null, null), "Enter"));
            check("stock in refuses an unknown product", rejects(() -> f.service.stockIn("s", "NOPE", q("1"), null), "no longer exists"));
            check("stock in refuses a blank product", rejects(() -> f.service.stockIn("s", " ", q("1"), null), "Select a product"));
            check("stock in refuses a note over 200 characters", rejects(() -> f.service.stockIn("s", "P-0001", q("1"), "x".repeat(201)), "200"));
            check("refused stock-ins changed nothing", f.onHand("P-0001").compareTo(q("20")) == 0
                    && f.repo.movements().size() == movementsBefore);

            // permission and session
            check("an expired session is refused", rejects(() -> f.service.stockIn("expired", "P-0001", q("1"), null), "expired"));
            check("a user without permission is refused", rejects(() -> f.service.stockOut("cashier", "P-0001", q("1"), StockReason.DAMAGE, null), "permission"));
            check("refused users changed nothing", f.onHand("P-0001").compareTo(q("20")) == 0
                    && f.repo.movements().size() == movementsBefore);

            // stock out
            StockLevel out = f.service.stockOut("s", "P-0001", q("5"), StockReason.DAMAGE, "wet");
            check("stock out subtracts", out.quantityOnHand().compareTo(q("15")) == 0);
            StockMovement o = f.repo.movements().get(f.repo.movements().size() - 1);
            check("stock out writes a negative movement with its reason",
                    o.type() == MovementType.STOCK_OUT && o.quantity().compareTo(q("-5")) == 0
                            && o.reason() == StockReason.DAMAGE && o.quantityAfter().compareTo(q("15")) == 0);
            int beforeFail = f.repo.movements().size();
            check("stock out refuses more than is in stock",
                    rejects(() -> f.service.stockOut("s", "P-0001", q("16"), StockReason.DAMAGE, null),
                            "Not enough stock for Bond Paper A4 (ream): 15 left, 16 needed."));
            check("stock out can take everything", f.service.stockOut("s", "P-0001", q("15"), StockReason.TRANSFER, null)
                    .quantityOnHand().signum() == 0);
            check("stock out needs a reason", rejects(() -> f.service.stockOut("s", "P-0004", q("1"), null, null), "reason"));
            check("stock out refuses the adjustment-only reason",
                    rejects(() -> f.service.stockOut("s", "P-0004", q("1"), StockReason.COUNT_CORRECTION, null), "reason"));
            check("stock out with reason Other needs a note",
                    rejects(() -> f.service.stockOut("s", "P-0004", q("1"), StockReason.OTHER, "  "), "note"));
            check("stock out accepts the Sale reason (a sale not rung up through POS)",
                    f.service.stockOut("s", "P-0004", q("0.5"), StockReason.SALE, null).quantityOnHand().compareTo(q("1")) == 0);
            check("the refused stock-out wrote no movement", f.repo.movements().size() == beforeFail + 1 + 1 /* 15 out, then 0.5 */);
            check("stock out of a never-stocked product is refused and leaves no empty row",
                    rejects(() -> f.service.stockOut("s", "P-0002", q("1"), StockReason.DAMAGE, null), "0 left")
                            && !f.repo.hasRow("P-0002"));

            // adjust
            f.service.stockIn("s", "P-0003", q("15"), null);
            StockLevel adj = f.service.adjust("s", "P-0003", q("12"), StockReason.COUNT_CORRECTION, null);
            StockMovement a = f.repo.movements().get(f.repo.movements().size() - 1);
            check("adjust sets the counted quantity", adj.quantityOnHand().compareTo(q("12")) == 0);
            check("adjust records the difference (-3) with before 15 and after 12",
                    a.type() == MovementType.ADJUSTMENT && a.quantity().compareTo(q("-3")) == 0
                            && a.quantityBefore().compareTo(q("15")) == 0 && a.quantityAfter().compareTo(q("12")) == 0);
            check("adjust can go up as well", f.service.adjust("s", "P-0003", q("20"), StockReason.COUNT_CORRECTION, null)
                    .quantityOnHand().compareTo(q("20")) == 0
                    && f.repo.movements().get(f.repo.movements().size() - 1).quantity().compareTo(q("8")) == 0);
            check("adjust to the same quantity is refused", rejects(() -> f.service.adjust("s", "P-0003", q("20"), StockReason.OTHER, "x"), "same as"));
            check("adjust to a negative quantity is refused", rejects(() -> f.service.adjust("s", "P-0003", q("-1"), StockReason.OTHER, "x"), "negative"));
            check("adjust needs a reason", rejects(() -> f.service.adjust("s", "P-0003", q("5"), null, null), "reason"));
            check("adjust refuses a stock-out-only reason", rejects(() -> f.service.adjust("s", "P-0003", q("5"), StockReason.TRANSFER, null), "reason"));
            check("adjust with reason Other needs a note", rejects(() -> f.service.adjust("s", "P-0003", q("5"), StockReason.OTHER, null), "note"));
            check("adjust can set the stock to zero", f.service.adjust("s", "P-0003", q("0"), StockReason.DAMAGE, null)
                    .status() == StockStatus.OUT_OF_STOCK);

            // reorder level
            f.service.stockIn("s", "P-0002", q("4"), null);
            StockLevel low = f.service.setReorderLevel("s", "P-0002", q("8"));
            check("reorder level makes 4 of 8 Low Stock", low.status() == StockStatus.LOW_STOCK);
            int movementsNow = f.repo.movements().size();
            f.service.setReorderLevel("s", "P-0002", q("2"));
            check("reorder level 2 makes it In Stock again, and is not a stock movement",
                    f.repo.find("P-0002").orElseThrow().status() == StockStatus.IN_STOCK
                            && f.repo.movements().size() == movementsNow);
            check("a negative reorder level is refused", rejects(() -> f.service.setReorderLevel("s", "P-0002", q("-1")), "negative"));

            // StockLevels contract
            InventoryStockLevels levels = new InventoryStockLevels(f.repo);
            check("StockLevels.available reads the stock", levels.available("P-0002").compareTo(q("4")) == 0);
            check("StockLevels.available is 0 for an unknown or null product",
                    levels.available("NOPE").signum() == 0 && levels.available(null).signum() == 0);

            salesRules();
            stockOverviewRules();
        } catch (Exception e) {
            failed++;
            System.out.println("  FAIL  service rules stopped on an unexpected error: " + e);
            e.printStackTrace(System.out);
        }
    }

    /** StockRecorder: what POS calls inside the checkout transaction. */
    /** The StockOverview contract (read by Reports): the Current Stock rows, with the level worked out here. */
    private static void stockOverviewRules() throws Exception {
        Fixture f = new Fixture();
        f.service.stockIn("s", "P-0001", q("20"), null);
        f.service.setReorderLevel("s", "P-0001", q("5"));    // 20 of 5: In Stock
        f.service.stockIn("s", "P-0002", q("4"), null);
        f.service.setReorderLevel("s", "P-0002", q("8"));    // 4 of 8: Low Stock
        f.service.setReorderLevel("s", "P-0003", q("5"));    // never stocked, 0 of 5: Out of Stock
        f.service.stockIn("s", "P-0004", q("1"), null);      // 1 of 0: In Stock (no threshold set)

        StockOverview overview = f.service;   // no session needed: the caller checks its own permission
        Map<String, StockSnapshot> byId = new HashMap<>();
        for (StockSnapshot s : overview.currentStock()) {
            byId.put(s.productId(), s);
        }
        check("StockOverview: IN_STOCK above the reorder level", byId.get("P-0001").level() == StockSnapshot.Level.IN_STOCK
                && byId.get("P-0001").onHand().compareTo(q("20")) == 0
                && byId.get("P-0001").reorderLevel().compareTo(q("5")) == 0);
        check("StockOverview: LOW_STOCK at or below the reorder level", byId.get("P-0002").level() == StockSnapshot.Level.LOW_STOCK);
        check("StockOverview: OUT_OF_STOCK with nothing left", byId.get("P-0003").level() == StockSnapshot.Level.OUT_OF_STOCK
                && byId.get("P-0003").onHand().signum() == 0);
        check("StockOverview: reorder level 0 is never LOW_STOCK", byId.get("P-0004").level() == StockSnapshot.Level.IN_STOCK
                && byId.get("P-0004").reorderLevel().signum() == 0);
        check("StockOverview: a product never stocked shows 0, Out of Stock", byId.containsKey("P-0007")
                && byId.get("P-0007").onHand().signum() == 0 && byId.get("P-0007").level() == StockSnapshot.Level.OUT_OF_STOCK);
        check("StockOverview: the same rows, in the same order, as the Current Stock screen",
                overview.currentStock().stream().map(StockSnapshot::productId).toList()
                        .equals(f.service.listStock("s").stream().map(StockRow::productId).toList()));
    }

    private static void salesRules() throws Exception {
        Fixture f = new Fixture();
        f.service.stockIn("s", "P-0001", q("10"), null);
        f.service.stockIn("s", "P-0002", q("3"), null);
        int before = f.repo.movements().size();

        f.repo.<Void, Exception>inTransaction(tx -> {
            f.service.recordSale("T01-2026-000001", List.of(
                    new StockLine("P-0001", q("2")),
                    new StockLine("P-0002", q("1")),
                    new StockLine("P-0001", q("3"))), tx);
            return null;
        });
        check("a completed sale deducts stock (lines of one product added up)",
                f.onHand("P-0001").compareTo(q("5")) == 0 && f.onHand("P-0002").compareTo(q("2")) == 0);
        List<StockMovement> sales = f.repo.movements().subList(before, f.repo.movements().size());
        check("a completed sale writes ONE Sale movement per product, with the invoice number",
                sales.size() == 2 && sales.stream().allMatch(s -> s.type() == MovementType.SALE
                        && "T01-2026-000001".equals(s.referenceNo())));
        StockMovement paper = sales.stream().filter(s -> s.productId().equals("P-0001")).findFirst().orElseThrow();
        check("the Sale movement for P-0001 is -5, before 10, after 5",
                paper.quantity().compareTo(q("-5")) == 0 && paper.quantityBefore().compareTo(q("10")) == 0
                        && paper.quantityAfter().compareTo(q("5")) == 0);

        // a short second line must undo the first line too (all or nothing)
        int movementsBefore = f.repo.movements().size();
        InsufficientStockException caught = null;
        try {
            f.repo.<Void, InsufficientStockException>inTransaction(tx -> {
                f.service.recordSale("T01-2026-000002", List.of(
                        new StockLine("P-0001", q("1")),
                        new StockLine("P-0002", q("9"))), tx);
                return null;
            });
        } catch (InsufficientStockException e) {
            caught = e;
        }
        check("a short product stops the sale and names it",
                caught != null && "P-0002".equals(caught.getProductId())
                        && caught.getMessage().contains("Ballpen Black") && caught.getMessage().contains("2 left, 9 needed"));
        check("a failed sale changes nothing: stock and history are exactly as before",
                f.onHand("P-0001").compareTo(q("5")) == 0 && f.onHand("P-0002").compareTo(q("2")) == 0
                        && f.repo.movements().size() == movementsBefore);

        InsufficientStockException never = null;
        try {
            f.repo.<Void, InsufficientStockException>inTransaction(tx -> {
                f.service.recordSale("T01-2026-000003", List.of(new StockLine("P-0003", q("1"))), tx);
                return null;
            });
        } catch (InsufficientStockException e) {
            never = e;
        }
        check("selling a product that was never stocked is refused", never != null);
    }

    // ---- part 3: MySQL -------------------------------------------------------

    private static void mysqlRules() {
        section("MySQL (needs the Inventory tables)");
        try (Connection probe = Database.getConnection()) {
            probe.getCatalog();
        } catch (SQLException e) {
            System.out.println("  skip  MySQL is not reachable (" + firstLine(e) + "): database checks skipped");
            return;
        }
        StubInventory products = new StubInventory();
        products.add("ZZ-INV-1", null, "Inventory Check Item 1", "pc", "10.00", null, true, true, "0");
        products.add("ZZ-INV-2", null, "Inventory Check Item 2", "pc", "10.00", null, true, true, "0");
        MySqlInventoryRepository repo = new MySqlInventoryRepository();
        InventoryService service = new InventoryService(repo, products, sessionId -> CLERK, null);
        try {
            cleanUp();
            service.stockIn("s", "ZZ-INV-1", q("10"), "PO-TEST");
            service.stockOut("s", "ZZ-INV-1", q("3"), StockReason.DAMAGE, null);
            check("MySQL: stock in then out leaves 7 on hand", repo.find("ZZ-INV-1").orElseThrow()
                    .quantityOnHand().compareTo(q("7")) == 0);
            check("MySQL: both changes are in the history, before/after correct",
                    movementRows("ZZ-INV-1") == 2 && lastAfter("ZZ-INV-1").compareTo(q("7")) == 0);

            service.stockIn("s", "ZZ-INV-2", q("4"), null);
            Database.<Void, Exception>inTransaction(tx -> {
                service.recordSale("ZZ-T01-2026-000001", List.of(new StockLine("ZZ-INV-1", q("2")),
                        new StockLine("ZZ-INV-1", q("1"))), tx);
                return null;
            });
            check("MySQL: a sale deducts once, as one Sale movement",
                    repo.find("ZZ-INV-1").orElseThrow().quantityOnHand().compareTo(q("4")) == 0
                            && saleRows("ZZ-T01-2026-000001") == 1);

            boolean refused = false;
            try {
                Database.<Void, InsufficientStockException>inTransaction(tx -> {
                    service.recordSale("ZZ-T01-2026-000002", List.of(new StockLine("ZZ-INV-1", q("1")),
                            new StockLine("ZZ-INV-2", q("99"))), tx);
                    return null;
                });
            } catch (InsufficientStockException e) {
                refused = true;
            }
            check("MySQL: a short product rolls the whole sale back (stock and history unchanged)",
                    refused && repo.find("ZZ-INV-1").orElseThrow().quantityOnHand().compareTo(q("4")) == 0
                            && repo.find("ZZ-INV-2").orElseThrow().quantityOnHand().compareTo(q("4")) == 0
                            && saleRows("ZZ-T01-2026-000002") == 0);

            boolean blocked = false;
            try (Connection c = Database.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "UPDATE Inventory SET QuantityOnHand = -1 WHERE ProductID = 'ZZ-INV-1'")) {
                ps.executeUpdate();
            } catch (SQLException e) {
                blocked = true;
            }
            check("MySQL: the database itself refuses negative stock", blocked);
        } catch (Exception e) {
            failed++;
            String hint = String.valueOf(e.getMessage()).contains("doesn't exist")
                    ? "  (the Inventory tables are missing: add database/inventory-tables.sql to the script and run setup)" : "";
            System.out.println("  FAIL  MySQL checks stopped on an error: " + e + hint);
        } finally {
            try {
                cleanUp();
            } catch (Exception ignored) {
                // nothing more to do
            }
        }
    }

    private static void cleanUp() throws SQLException {
        try (Connection c = Database.getConnection()) {
            for (String sql : new String[] {
                    "DELETE FROM StockMovement WHERE ProductID LIKE 'ZZ-INV-%'",
                    "DELETE FROM Inventory WHERE ProductID LIKE 'ZZ-INV-%'"}) {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.executeUpdate();
                }
            }
        }
    }

    private static int movementRows(String productId) throws SQLException {
        return count("SELECT COUNT(*) FROM StockMovement WHERE ProductID = ?", productId);
    }

    private static int saleRows(String reference) throws SQLException {
        return count("SELECT COUNT(*) FROM StockMovement WHERE MovementType = 'SALE' AND ReferenceNo = ?", reference);
    }

    private static int count(String sql, String arg) throws SQLException {
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static BigDecimal lastAfter(String productId) throws SQLException {
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT QuantityAfter FROM StockMovement WHERE ProductID = ? ORDER BY MovementID DESC LIMIT 1")) {
            ps.setString(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    // ---- tiny test helpers ----------------------------------------------------

    private interface Action {
        void run() throws Exception;
    }

    /** True when the action throws an InventoryException whose message contains {@code text}. */
    private static boolean rejects(Action action, String text) {
        try {
            action.run();
            return false;
        } catch (InventoryException e) {
            return e.getMessage().contains(text);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean throwsIllegal(Runnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static BigDecimal q(String value) {
        return Quantity.of(value);
    }

    private static String firstLine(Exception e) {
        String m = String.valueOf(e.getMessage());
        int nl = m.indexOf('\n');
        return nl < 0 ? m : m.substring(0, nl);
    }

    private static void section(String title) {
        System.out.println("== " + title);
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name);
        }
    }
}
