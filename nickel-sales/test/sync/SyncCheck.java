package sync;

import shared.ApiKey;
import shared.Json;
import shared.MasterIds;
import shared.SyncOutbox;
import shared.SyncTables;
import sync.api.ApiServer;
import sync.api.MainPosClient;
import sync.api.MainPosLink;
import sync.api.SyncHub;
import system.contract.SyncLogWriter;

import java.math.BigDecimal;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SELF-TEST for syncing every terminal's changes through the Main POS (sync.SyncEngine).
 *
 * Part 1, no database: the JSON the terminals send each other.
 * Part 2, MySQL: TWO terminals, each with its own pair of scratch databases (store + app config) made
 * from the real SQL scripts, synced end to end: a Main POS (T01) and an Additional POS (T02). Changes
 * are made the way the app makes them (rows written, then queued with shared.SyncOutbox in the same
 * transaction). The scratch databases are dropped afterwards. Skipped if MySQL can't be reached.
 * Part 3 (with NSC_TEST_API_KEY set to the developer key): the same over the real API on a free port.
 *
 * Run after any run-demo build, from nickel-sales/:
 *   java -cp "out;lib\jbcrypt-0.4.jar;lib\mysql-connector-j-9.4.0.jar" sync.SyncCheck
 */
public class SyncCheck {

    static int passed = 0;
    static int failed = 0;
    static int skipped = 0;

    static final String PREFIX = "nsc_synctest_";

    public static void main(String[] args) throws Exception {
        System.out.println("=================================================================");
        System.out.println(" NICKEL SALES CENTER - sync between terminals");
        System.out.println("=================================================================");

        jsonChecks();
        databaseChecks();

        System.out.println("-----------------------------------------------------------------");
        System.out.println(" RESULT: " + passed + " passed, " + failed + " failed, " + skipped + " skipped");
        System.out.println("=================================================================");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /* ------------------------------- part 1: JSON ------------------------------- */

    static void jsonChecks() {
        check("JSON: objects, lists, text, numbers, true/false/null read back exactly", () -> {
            String text = "{\"a\":\"Peña \\\"x\\\"\\n\",\"n\":12.50,\"big\":123456789012345678,\"t\":true,"
                    + "\"z\":null,\"list\":[1,\"two\",{\"k\":false}]}";
            Map<String, Object> o = Json.parseObject(text);
            expectText(Json.text(o, "a"), "Peña \"x\"\n");
            expectTrue(new BigDecimal("12.50").equals(o.get("n")), "12.50 kept as written");
            expectText(Json.text(o, "big"), "123456789012345678");
            expectTrue(Boolean.TRUE.equals(o.get("t")) && o.containsKey("z") && o.get("z") == null, "true / null");
            expectTrue(Json.list(o, "list").size() == 3, "list");
            expectTrue(Json.parseObject(Json.write(o)).equals(o), "written and read back the same");
        });
        check("JSON: broken text is refused, not half read", () -> {
            for (String bad : new String[] {"{", "{\"a\":}", "[1,2", "{\"a\":1} x", "\"open", "{a:1}"}) {
                try {
                    Json.parse(bad);
                    throw new AssertionError("accepted " + bad);
                } catch (IllegalArgumentException expected) {
                    // refused
                }
            }
        });
        check("Synced tables: sales and logs only added, products and users edited, per-PC columns kept out", () -> {
            expectTrue(SyncTables.of("sale").orElseThrow().op() == SyncTables.Op.INSERT, "Sale added only");
            expectTrue(SyncTables.of("Product").orElseThrow().op() == SyncTables.Op.UPSERT, "Product edited");
            expectTrue(SyncTables.of("Users").orElseThrow().localOnly().contains("LastSignIn"), "LastSignIn per PC");
            expectTrue(SyncTables.of("StockMovement").orElseThrow().key().equals(List.of("GlobalID")), "movement by GlobalID");
            expectTrue(SyncTables.of("InvoiceSequence").isEmpty() && SyncTables.of("BackupRecord").isEmpty()
                    && SyncTables.of("SyncLog").isEmpty(), "per-PC tables never synced");
        });
    }

    /* --------------------------- part 2: two terminals --------------------------- */

    /** One terminal's databases. */
    record Side(String name, String store, String cfg, SyncDb db) {
        Connection open() throws SQLException {
            return connect(store);
        }
    }

    static String server;
    static String user;
    static String password;

    static void databaseChecks() throws Exception {
        String url = shared.Settings.get("NSC_DB_URL", shared.Database.DEFAULT_URL);
        int slash = url.indexOf('/', url.indexOf("//") + 2);
        server = url.substring(0, slash);
        user = shared.Settings.get("NSC_DB_USER", "root");
        password = shared.Settings.get("NSC_DB_PASSWORD", "");
        try (Connection c = DriverManager.getConnection(server + "/?useSSL=false&allowPublicKeyRetrieval=true",
                user, password)) {
            c.isValid(2);
        } catch (SQLException e) {
            skip("MySQL two-terminal checks", "MySQL can't be reached (" + e.getMessage() + ")");
            return;
        }

        Side main = side("main");
        Side t02 = side("t02");
        Log log = new Log();
        Clock clock = Clock.systemUTC();
        SyncEngine mainEngine = SyncEngine.mainPos(main.db(), "T01", (t, ip) -> log.seen.add(t + "@" + ip), log, clock);
        boolean[] down = {false};
        MainPosLink direct = new MainPosLink() {
            @Override
            public int push(String terminalId, List<SyncHub.Sent> changes) throws MainPosClient.MainPosException {
                if (down[0]) {
                    throw new MainPosClient.MainPosException("Can't reach the Main POS (test: it is off).");
                }
                try {
                    return mainEngine.accept(terminalId, "192.168.1.21", changes);
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public SyncHub.Page pull(long after, int limit) throws MainPosClient.MainPosException {
                if (down[0]) {
                    throw new MainPosClient.MainPosException("Can't reach the Main POS (test: it is off).");
                }
                try {
                    return mainEngine.pull(after, limit);
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public String mainIp() {
                return "192.168.1.20";
            }
        };
        SyncEngine t02Engine = SyncEngine.additional(t02.db(), "T02", () -> direct, ip -> { }, null, log, clock);
        Runnable round = () -> {   // the order sync runs in on a real store: T02 sends, the Main POS applies, T02 gets
            t02Engine.cycle();
            mainEngine.cycle();
            t02Engine.cycle();
        };

        try {
            check("Made on the Main POS (user, product, stock-in) reaches the Additional POS", () -> {
                addUser(main, "NSC-00001", "JCubillan");
                addProduct(main, "P-0001", "4800000000017", "10.00");
                moveStock(main, "P-0001", "STOCK_IN", "5", null);
                round.run();
                expectTrue(count(t02, "SELECT COUNT(*) FROM Users WHERE UserID = 'NSC-00001'") == 1, "user on T02");
                expectText(text(t02, "SELECT RetailPrice FROM Product WHERE ProductID = 'P-0001'"), "10.00");
                expectText(stock(t02, "P-0001"), "5.000");
                expectText(stock(main, "P-0001"), "5.000");   // its own movement came back, but did not count twice
            });

            check("A sale on the Additional POS reaches the Main POS; stock follows; nothing counts twice", () -> {
                sale(t02, "T02-2026-000001", "P-0001", "2");
                expectText(stock(t02, "P-0001"), "3.000");
                round.run();
                expectTrue(count(main, "SELECT COUNT(*) FROM Sale WHERE InvoiceNo = 'T02-2026-000001'") == 1, "sale on Main");
                expectTrue(count(main, "SELECT COUNT(*) FROM SaleLine l JOIN Sale s ON s.SaleID = l.SaleID "
                        + "WHERE s.InvoiceNo = 'T02-2026-000001'") == 1, "its line");
                expectTrue(count(main, "SELECT COUNT(*) FROM Payment p JOIN Sale s ON s.SaleID = p.SaleID "
                        + "WHERE s.InvoiceNo = 'T02-2026-000001'") == 1, "its payment");
                expectText(stock(main, "P-0001"), "3.000");
                expectText(stock(t02, "P-0001"), "3.000");
                expectTrue(count(t02, "SELECT COUNT(*) FROM Sale WHERE InvoiceNo = 'T02-2026-000001' AND SyncedAt IS NOT NULL") == 1,
                        "marked synced on T02");
                expectTrue(t02.db().pendingCount() == 0, "nothing waiting");
            });

            check("The same changes sent again are taken once", () -> {
                long feed = count(main, "SELECT COUNT(*) FROM `" + main.cfg() + "`.SyncFeed");
                exec(t02, "UPDATE `" + t02.cfg() + "`.SyncOutbox SET SentAt = NULL");   // as if the reply got lost
                round.run();
                expectTrue(count(main, "SELECT COUNT(*) FROM `" + main.cfg() + "`.SyncFeed") == feed, "list unchanged");
                expectTrue(count(main, "SELECT COUNT(*) FROM Sale") == 1, "one sale");
                expectText(stock(main, "P-0001"), "3.000");
            });

            check("Main POS off: the Additional POS keeps selling and sends it all when it's back", () -> {
                down[0] = true;
                sale(t02, "T02-2026-000002", "P-0001", "1");
                t02Engine.cycle();
                expectTrue(Boolean.FALSE.equals(t02Engine.status().mainReachable()), "shows the Main POS offline");
                expectTrue(t02.db().pendingCount() == 2, "the sale and its stock change wait: " + t02.db().pendingCount());
                expectTrue(log.has("LINK", "FAILED"), "the outage is in the Sync log");
                down[0] = false;
                round.run();
                expectTrue(Boolean.TRUE.equals(t02Engine.status().mainReachable()), "back online");
                expectTrue(t02.db().pendingCount() == 0, "all sent");
                expectTrue(count(main, "SELECT COUNT(*) FROM Sale WHERE InvoiceNo = 'T02-2026-000002'") == 1, "sale on Main");
                expectText(stock(main, "P-0001"), "2.000");
            });

            check("Both sell the last items while apart: both sales kept, stock below zero is flagged", () -> {
                sale(main, "T01-2026-000001", "P-0001", "2");       // Main: 2 -> 0
                down[0] = true;
                sale(t02, "T02-2026-000003", "P-0001", "2");        // T02, offline: 2 -> 0
                t02Engine.cycle();
                down[0] = false;
                round.run();
                expectText(stock(main, "P-0001"), "-2.000");
                expectText(stock(t02, "P-0001"), "-2.000");
                expectTrue(count(main, "SELECT COUNT(*) FROM Sale") == 4 && count(t02, "SELECT COUNT(*) FROM Sale") == 4,
                        "all 4 sales on both");
                expectTrue(log.has(SyncTables.STOCK, "FAILED"), "flagged in the Sync log");
            });

            check("An edit reaches every terminal; the newest edit wins", () -> {
                editPrice(main, "P-0001", "12.00");
                round.run();
                expectText(text(t02, "SELECT RetailPrice FROM Product WHERE ProductID = 'P-0001'"), "12.00");
                editPrice(t02, "P-0001", "13.00");
                editPrice(main, "P-0001", "14.00");   // later in the Main POS's list: wins everywhere
                t02Engine.cycle();
                mainEngine.cycle();
                t02Engine.cycle();
                expectText(text(main, "SELECT RetailPrice FROM Product WHERE ProductID = 'P-0001'"),
                        text(t02, "SELECT RetailPrice FROM Product WHERE ProductID = 'P-0001'"));
            });

            check("New IDs come from the Main POS and are never handed out twice", () -> {
                expectText(mainEngine.nextId(MasterIds.PRODUCT), "P-0002");
                expectText(mainEngine.nextId(MasterIds.PRODUCT), "P-0003");   // P-0002 handed out but not saved yet
                expectText(mainEngine.nextId(MasterIds.USER), "NSC-00002");
                exec(main, "INSERT INTO Category (CategoryID, CategoryName) VALUES (5, 'Drinks')");
                expectText(mainEngine.nextId(MasterIds.CATEGORY), "6");
            });

            check("A change this PC can't take is logged and skipped; Retry applies it once fixed", () -> {
                addProduct(main, "P-0010", "999", "5.00");     // not sent yet
                addProduct(t02, "P-0011", "999", "6.00");      // same barcode on the other terminal
                round.run();
                expectTrue(count(main, "SELECT COUNT(*) FROM Product WHERE ProductID = 'P-0011'") == 0, "refused on Main");
                long failedId = count(main, "SELECT MAX(SyncLogID) FROM `" + main.cfg() + "`.SyncFailed");
                expectTrue(failedId != 0 || count(main, "SELECT COUNT(*) FROM `" + main.cfg() + "`.SyncFailed") == 1,
                        "kept for Retry");
                expectTrue(log.has(SyncTables.PRODUCT, "FAILED"), "in the Sync log");
                sale(main, "T01-2026-000002", "P-0001", "1");  // the list goes on after a refused change
                round.run();
                expectTrue(count(t02, "SELECT COUNT(*) FROM Sale WHERE InvoiceNo = 'T01-2026-000002'") == 1, "later change applied");
                editBarcode(main, "P-0010", "998");             // the admin fixes the clash
                expectTrue(mainEngine.retry(failedId), "retried");
                expectTrue(count(main, "SELECT COUNT(*) FROM Product WHERE ProductID = 'P-0011'") == 1, "now on Main");
                expectTrue(count(main, "SELECT COUNT(*) FROM `" + main.cfg() + "`.SyncFailed") == 0, "no longer failed");
            });

            check("Only synced tables and existing columns are written, whatever a change says", () -> {
                ChangeApplier applier = new ChangeApplier();
                try (Connection c = main.open()) {
                    try {
                        applier.apply(c, "{\"rows\":[{\"t\":\"InvoiceSequence\",\"c\":{\"TerminalID\":\"T09\"}}]}");
                        throw new AssertionError("wrote a table that is not synced");
                    } catch (ChangeApplier.RejectedException expected) {
                        // refused
                    }
                    applier.apply(c, "{\"rows\":[{\"t\":\"Category\",\"c\":{\"CategoryID\":\"7\",\"CategoryName\":\"Snacks\","
                            + "\"IsActive\":\"1\",\"NoSuchColumn`; DROP TABLE Sale; --\":\"x\"}}]}");
                }
                expectTrue(count(main, "SELECT COUNT(*) FROM Category WHERE CategoryID = 7") == 1, "known columns written");
                expectTrue(count(main, "SELECT COUNT(*) FROM Sale") > 0, "nothing else touched");
            });

            String key = System.getenv("NSC_TEST_API_KEY");
            if (key == null || !ApiKey.isValid(key)) {
                skip("Over the real API", "set NSC_TEST_API_KEY to the developer key to run it");
            } else {
                check("Over the real API: new ID, send, get, the key is checked, the terminal's address is noted", () -> {
                    int port;
                    try (ServerSocket free = new ServerSocket(0)) {
                        port = free.getLocalPort();
                    }
                    ApiServer api = ApiServer.start(port, "T01", (ip, name) -> "T02", null, mainEngine);
                    try {
                        MainPosClient client = new MainPosClient("127.0.0.1", port, key);
                        client.verifyMainPos();
                        String id = client.nextId(MasterIds.USER);
                        expectText(id, "NSC-00003");
                        addUser(t02, id, "MSantos");
                        SyncEngine overApi = SyncEngine.additional(t02.db(), "T02", () -> client, ip -> { }, null, log, clock);
                        overApi.cycle();
                        mainEngine.cycle();
                        expectTrue(count(main, "SELECT COUNT(*) FROM Users WHERE UserID = '" + id + "'") == 1, "user on Main");
                        expectTrue(log.seen.contains("T02@127.0.0.1"), "T02's address noted: " + log.seen);
                        try {
                            new MainPosClient("127.0.0.1", port, "WRONG-KEY").pull(0, 1);
                            throw new AssertionError("a wrong key was accepted");
                        } catch (MainPosClient.MainPosException expected) {
                            // refused
                        }
                    } finally {
                        api.stop();
                    }
                });
            }
        } finally {
            for (Side s : List.of(main, t02)) {
                try (Connection c = connect(null); Statement st = c.createStatement()) {
                    st.execute("DROP DATABASE IF EXISTS `" + s.store() + "`");
                    st.execute("DROP DATABASE IF EXISTS `" + s.cfg() + "`");
                } catch (SQLException e) {
                    System.out.println("  (could not drop the scratch databases of " + s.name() + ": " + e.getMessage() + ")");
                }
            }
        }
    }

    /** A terminal's scratch databases, made from the real SQL scripts with the names changed. */
    static Side side(String name) throws Exception {
        String store = PREFIX + name;
        String cfg = PREFIX + name + "_cfg";
        Path dir = Path.of("..", "database");
        String storeSql = Files.readString(dir.resolve("NickelSales-Database-Config.sql"), StandardCharsets.UTF_8)
                .replace("NickelSales_Database", store);
        String cfgSql = Files.readString(dir.resolve("NickelSales-AppConfig.sql"), StandardCharsets.UTF_8)
                .replace("nickelsales_appconfig", cfg);
        try (Connection c = DriverManager.getConnection(server + "/?useSSL=false&allowPublicKeyRetrieval=true"
                + "&allowMultiQueries=true&serverTimezone=UTC", user, password); Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS `" + store + "`");
            st.execute("DROP DATABASE IF EXISTS `" + cfg + "`");
            st.execute(storeSql);
            st.execute(cfgSql);
        }
        return new Side(name, store, cfg, new SyncDb(() -> connect(store), cfg));
    }

    static Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(server + "/" + (database == null ? "" : database)
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC", user, password);
    }

    /* ----------------- changes made the way the app makes them ----------------- */

    interface Work {
        void run(Connection tx) throws SQLException;
    }

    /** Writes and queues in one transaction, like the repositories. */
    static void change(Side s, Work work) throws SQLException {
        try (Connection c = s.open()) {
            c.setAutoCommit(false);
            work.run(c);
            c.commit();
        }
    }

    static void queue(Connection c, Side s, String kind, String record, String table, String where, Object... args)
            throws SQLException {
        SyncOutbox.record(c, s.cfg(), kind, record, SyncOutbox.rows(c, table, where, args));
    }

    static void addUser(Side s, String id, String login) throws SQLException {
        change(s, c -> {
            run(c, "INSERT INTO Users (UserID, UserLoginName, UserPassword, UserName, UserLastname, UserRole, UserStatus) "
                    + "VALUES (?, ?, 'x', 'Test', 'User', 'A', 'Active')", id, login);
            queue(c, s, SyncTables.USER, id, "Users", "UserID = ?", id);
        });
    }

    static void addProduct(Side s, String id, String barcode, String price) throws SQLException {
        change(s, c -> {
            run(c, "INSERT INTO Product (ProductID, Barcode, ProductName, Unit, RetailPrice) VALUES (?, ?, ?, 'pc', ?)",
                    id, barcode, "Item " + id, price);
            queue(c, s, SyncTables.PRODUCT, id, "Product", "ProductID = ?", id);
        });
    }

    static void editPrice(Side s, String id, String price) throws SQLException {
        change(s, c -> {
            run(c, "UPDATE Product SET RetailPrice = ? WHERE ProductID = ?", price, id);
            queue(c, s, SyncTables.PRODUCT, id, "Product", "ProductID = ?", id);
        });
    }

    static void editBarcode(Side s, String id, String barcode) throws SQLException {
        change(s, c -> {
            run(c, "UPDATE Product SET Barcode = ? WHERE ProductID = ?", barcode, id);
            queue(c, s, SyncTables.PRODUCT, id, "Product", "ProductID = ?", id);
        });
    }

    /** A stock movement on this terminal, as InventoryService writes it, in {@code c}'s transaction. */
    static void movement(Connection c, Side s, String productId, String type, String quantity, String reference)
            throws SQLException {
        BigDecimal before = new BigDecimal(stockIn(c, productId));
        BigDecimal after = before.add(new BigDecimal(quantity));
        run(c, "INSERT INTO Inventory (ProductID, QuantityOnHand, ReorderLevel, UpdatedAt) VALUES (?, ?, 0, ?) "
                + "ON DUPLICATE KEY UPDATE QuantityOnHand = VALUES(QuantityOnHand), UpdatedAt = VALUES(UpdatedAt)",
                productId, after.toPlainString(), now());
        String globalId = SyncOutbox.newId();
        run(c, "INSERT INTO StockMovement (GlobalID, ProductID, MovementType, Quantity, QuantityBefore, QuantityAfter, "
                + "ReferenceNo, CreatedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", globalId, productId, type, quantity,
                before.toPlainString(), after.toPlainString(), reference, now());
        queue(c, s, SyncTables.STOCK, productId, "StockMovement", "GlobalID = ?", globalId);
    }

    static void moveStock(Side s, String productId, String type, String quantity, String reference) throws SQLException {
        change(s, c -> movement(c, s, productId, type, quantity, reference));
    }

    /** A cash sale of one line, with its stock-out, as checkout saves it. */
    static void sale(Side s, String invoice, String productId, String quantity) throws SQLException {
        change(s, c -> {
            String id = SyncOutbox.newId();
            run(c, "INSERT INTO Sale (SaleID, InvoiceNo, TerminalID, CashierUserID, CompletedAt, VatableSales, "
                    + "VatExemptSales, VatAmount, Total) VALUES (?, ?, ?, 'NSC-00001', ?, 0, 0, 0, 10)",
                    id, invoice, invoice.substring(0, 3), now());
            run(c, "INSERT INTO SaleLine (SaleID, LineNo, ProductID, ProductName, Unit, Quantity, UnitPrice, PriceType, "
                    + "Vatable, LineTotal) VALUES (?, 1, ?, 'Item', 'pc', ?, 10, 'RETAIL', 1, 10)", id, productId, quantity);
            run(c, "INSERT INTO Payment (SaleID, PaymentNo, Method, AmountTendered, AmountApplied, ChangeGiven) "
                    + "VALUES (?, 1, 'CASH', 10, 10, 0)", id);
            List<Map<String, Object>> rows = new ArrayList<>(SyncOutbox.rows(c, "Sale", "SaleID = ?", id));
            rows.addAll(SyncOutbox.rows(c, "SaleLine", "SaleID = ?", id));
            rows.addAll(SyncOutbox.rows(c, "Payment", "SaleID = ?", id));
            SyncOutbox.record(c, s.cfg(), SyncTables.SALE, invoice, rows);
            movement(c, s, productId, "SALE", "-" + quantity, invoice);
        });
    }

    /* --------------------------------- helpers --------------------------------- */

    static String stockIn(Connection c, String productId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT QuantityOnHand FROM Inventory WHERE ProductID = ?")) {
            ps.setString(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBigDecimal(1).toPlainString() : "0";
            }
        }
    }

    static String stock(Side s, String productId) throws SQLException {
        try (Connection c = s.open()) {
            return stockIn(c, productId);
        }
    }

    static String now() {
        return LocalDateTime.now(ZoneOffset.UTC).toString().replace('T', ' ').substring(0, 23);
    }

    static void run(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    static void exec(Side s, String sql) throws SQLException {
        try (Connection c = s.open(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    static long count(Side s, String sql) throws SQLException {
        try (Connection c = s.open(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    static String text(Side s, String sql) throws SQLException {
        try (Connection c = s.open(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** The Sync log in memory. */
    static final class Log implements SyncLogWriter {
        final List<String[]> lines = new ArrayList<>();   // source, target, type, record, status, error
        final List<String> seen = new ArrayList<>();

        @Override
        public synchronized long attempt(String targetTerminal, String dataType, String recordId) {
            return attempt("?", targetTerminal, dataType, recordId);
        }

        @Override
        public synchronized long attempt(String source, String target, String type, String record) {
            lines.add(new String[] {source, target, type, record, "PENDING", null});
            return lines.size();
        }

        @Override
        public synchronized void succeeded(long logId) {
            lines.get((int) logId - 1)[4] = "SYNCED";
        }

        @Override
        public synchronized void failed(long logId, String errorMessage) {
            lines.get((int) logId - 1)[4] = "FAILED";
            lines.get((int) logId - 1)[5] = errorMessage;
        }

        synchronized boolean has(String type, String status) {
            return lines.stream().anyMatch(l -> l[2].equals(type) && l[4].equals(status));
        }
    }

    interface Body {
        void run() throws Exception;
    }

    static void check(String name, Body body) {
        try {
            body.run();
            passed++;
            System.out.println(" [PASS] " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println(" [FAIL] " + name + "  ->  " + t);
            if (!(t instanceof AssertionError)) {
                t.printStackTrace(System.out);
            }
        }
    }

    static void skip(String name, String why) {
        skipped++;
        System.out.println(" [SKIP] " + name + "  (" + why + ")");
    }

    static void expectTrue(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }

    static void expectText(String actual, String expected) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("expected \"" + expected + "\" but was \"" + actual + "\"");
        }
    }
}
