package system;

import auth.contract.User;
import auth.contract.UserRole;
import auth.contract.UserStatus;
import shared.Database;
import system.contract.StatusProvider;
import system.model.ActivityEntry;
import system.model.ActivityFilter;
import system.model.BackupRecord;
import system.model.InventoryLogEntry;
import system.model.InventoryLogFilter;
import system.model.StatusItem;
import system.model.StatusItem.Level;
import system.model.SyncLogEntry;
import system.model.SyncLogFilter;
import system.repository.ActivityLogRepository;
import system.repository.BackupRecordRepository;
import system.repository.DatabaseDump;
import system.repository.MySqlInventoryLogRepository;
import system.repository.SyncLogRepository;
import system.service.BackupService;
import system.service.InventoryLogService;
import system.service.SyncLogService;
import system.service.SystemException;
import system.service.SystemStatusService;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * SELF-TEST for Audit &amp; System phase 2: backup and restore, system status, sync logs and
 * inventory logs.
 *
 * The real database is only ever READ here. Everything that restores or changes data works on
 * two throw-away databases (NickelSales_CheckA / _CheckB), which are dropped at the end.
 *
 * Run after any run-demo build, from nickel-sales/:
 *   java -cp "out;lib\jbcrypt-0.4.jar;lib\mysql-connector-j-9.4.0.jar" system.SystemAdminCheck
 * (macOS/Linux: ':' instead of ';'). Exit code 1 on failure.
 */
public class SystemAdminCheck {

    static int passed = 0;
    static int failed = 0;

    static final Instant NOW = Instant.parse("2026-11-04T02:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final User ADMIN = new User("NSC-00001", "admin", "x", "Test", "Admin",
            EnumSet.of(UserRole.ADMIN), UserStatus.ACTIVE);

    static final String DB_A = "NickelSales_CheckA";
    static final String DB_B = "NickelSales_CheckB";

    public static void main(String[] args) throws Exception {
        System.out.println("=================================================================");
        System.out.println(" NICKEL SALES CENTER - Audit & System checks (backup, status, sync)");
        System.out.println("=================================================================");

        syncChecks();
        statusChecks();
        fileFormatChecks();

        Path temp = Files.createTempDirectory("nsc-backup-check");
        try {
            recreate(DB_A);
            recreate(DB_B);
            backupChecks(temp);
        } finally {
            drop(DB_A);
            drop(DB_B);
            deleteTree(temp);
        }

        check("Inventory log export: a stock decrease stays a number in Excel; formulas are still blocked", () -> {
            InventoryLogEntry out = new InventoryLogEntry(1, NOW, "P-0001", "=SUM(A1)", InventoryLogEntry.STOCK_OUT,
                    new java.math.BigDecimal("-2"), new java.math.BigDecimal("10"), new java.math.BigDecimal("8"),
                    null, "-1+2", "\t=cmd", null);
            InventoryLogService logs = new InventoryLogService(new MySqlInventoryLogRepository(), s -> ADMIN, null);
            Path file = Files.createTempFile("inventory-check", ".csv");
            try {
                logs.exportCsv("s", List.of(out), file, id -> null);
                String csv = Files.readString(file, StandardCharsets.UTF_8);
                expectTrue(csv.contains(",-2,10,8,"), "negative quantity unchanged: " + csv);
                expectTrue(csv.contains(",'=SUM(A1),"), "formula in a name blocked: " + csv);
                expectTrue(csv.contains(",'-1+2,"), "sum is not a plain number: " + csv);
                expectTrue(csv.contains(",'\t=cmd,"), "formula after a tab blocked: " + csv);
            } finally {
                Files.deleteIfExists(file);
            }
        });

        check("Inventory log: reads the real stock movements, filters, and refuses without permission", () -> {
            InventoryLogService logs = new InventoryLogService(new MySqlInventoryLogRepository(), s -> ADMIN, null);
            List<InventoryLogEntry> all = logs.find("s", InventoryLogFilter.all());
            for (InventoryLogEntry e : logs.find("s", new InventoryLogFilter(null, null, InventoryLogEntry.STOCK_IN, null))) {
                expectText(e.type(), InventoryLogEntry.STOCK_IN);
            }
            expectTrue(logs.find("s", new InventoryLogFilter(null, null, null, "no-such-product-zzz")).isEmpty(), "keyword");
            expectTrue(all.size() <= InventoryLogService.MAX_ROWS, "limited");
            expectText(InventoryLogEntry.label("COUNT_CORRECTION"), "Count correction");
            expectText(InventoryLogEntry.label("STOCK_IN"), "Stock In");
            InventoryLogService refused = new InventoryLogService(new MySqlInventoryLogRepository(), s -> {
                throw new SystemException("no");
            }, null);
            expectThrows(() -> refused.find("s", null), SystemException.class);
        });

        System.out.println("-----------------------------------------------------------------");
        System.out.println(" " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /* --------------------------------- sync --------------------------------- */

    static void syncChecks() {
        check("Sync log: attempt, success and failure are recorded; a logging failure never stops a sync", () -> {
            MemorySync repo = new MemorySync();
            SyncLogService sync = syncService(repo, new ArrayList<>());
            long id = sync.attempt("T02", "SALE", "sale-1");
            expectTrue(id == 1, "id returned");
            expectText(repo.rows.get(1L).status(), SyncLogEntry.PENDING);
            expectText(repo.rows.get(1L).sourceTerminal(), "T01");
            sync.failed(id, "Connection refused");
            expectText(repo.rows.get(1L).status(), SyncLogEntry.FAILED);
            expectTrue(repo.rows.get(1L).retryCount() == 1, "retry counted");
            sync.succeeded(id);
            expectText(repo.rows.get(1L).status(), SyncLogEntry.SYNCED);
            expectTrue(repo.rows.get(1L).errorMessage() == null, "error cleared");
            repo.broken = true;
            expectTrue(sync.attempt("T02", "SALE", "sale-2") == 0, "0 when it could not be saved");
            sync.succeeded(1);   // must not throw
            sync.failed(1, "x"); // must not throw
        });

        check("Sync retry: forwards to Sync, only for failed lines, and says when Sync is not connected", () -> {
            MemorySync repo = new MemorySync();
            List<String> audit = new ArrayList<>();
            SyncLogService sync = syncService(repo, audit);
            SyncLogEntry failedLine = entry(SyncLogEntry.FAILED);
            expectMessage(() -> sync.retry("s", failedLine), "Synchronization is not connected on this terminal yet.");
            expectTrue(!sync.syncConnected(), "not connected");

            List<String> sent = new ArrayList<>();
            sync.setRetryHandler((id, type, record) -> sent.add(id + " " + type + " " + record));
            expectTrue(sync.syncConnected(), "connected");
            sync.retry("s", failedLine);
            expectTrue(sent.equals(List.of("7 SALE sale-1")), "forwarded: " + sent);
            expectTrue(audit.equals(List.of("SYNC_RETRY_REQUESTED target=sale-1 by NSC-00001")), "audited: " + audit);
            expectMessage(() -> sync.retry("s", entry(SyncLogEntry.SYNCED)), "Only failed sync attempts can be retried.");

            sync.setRetryHandler((id, type, record) -> {
                throw new IllegalStateException("The other terminal is offline.");
            });
            expectMessage(() -> sync.retry("s", failedLine), "The other terminal is offline.");
        });

        check("Sync log: refused without permission", () -> {
            SyncLogService sync = new SyncLogService(new MemorySync(), s -> {
                throw new SystemException("no");
            }, null, "T01", () -> 0, CLOCK);
            expectThrows(() -> sync.find("s", null), SystemException.class);
            expectThrows(() -> sync.unsyncedSales("s"), SystemException.class);
            expectThrows(() -> sync.retry("s", entry(SyncLogEntry.FAILED)), SystemException.class);
        });
    }

    static SyncLogService syncService(SyncLogRepository repo, List<String> audit) {
        return new SyncLogService(repo, s -> ADMIN, (event, user) -> audit.add(event + " by " + user), "T01", () -> 3, CLOCK);
    }

    static SyncLogEntry entry(String status) {
        return new SyncLogEntry(7, NOW, NOW, "T01", "T02", "SALE", "sale-1", status, 1, "x");
    }

    /* -------------------------------- status -------------------------------- */

    static void statusChecks() {
        check("Status: pending sync explains itself before and after Sync is connected", () -> {
            expectItem(SystemStatusService.pendingSync(() -> 4, () -> false), Level.INFO, "4 unsent");
            expectItem(SystemStatusService.pendingSync(() -> 4, () -> true), Level.WARNING, "4 unsent");
            expectItem(SystemStatusService.pendingSync(() -> 0, () -> true), Level.OK, "None");
        });

        check("Status: last backup warns when there is none or it is over a week old", () -> {
            MemoryBackups records = new MemoryBackups();
            expectItem(SystemStatusService.lastBackup(records, CLOCK), Level.WARNING, "None yet");
            records.add(backupAt(NOW.minus(Duration.ofHours(3))));
            expectItem(SystemStatusService.lastBackup(records, CLOCK), Level.OK, "Today");
            records.rows.clear();
            records.add(backupAt(NOW.minus(Duration.ofDays(8))));
            expectItem(SystemStatusService.lastBackup(records, CLOCK), Level.WARNING, "8 days ago");
        });

        check("Status: log retention warns 5 days ahead and after 30 days", () -> {
            expectItem(SystemStatusService.logRetention(new MemoryActivity(null, 0), CLOCK), Level.OK, "No logs yet");
            expectItem(SystemStatusService.logRetention(new MemoryActivity(daysAgo(10), 0), CLOCK), Level.OK, "10 days old");
            expectItem(SystemStatusService.logRetention(new MemoryActivity(daysAgo(24), 0), CLOCK), Level.OK, "24 days old");
            expectItem(SystemStatusService.logRetention(new MemoryActivity(daysAgo(25), 0), CLOCK), Level.WARNING, "5 days left");
            expectItem(SystemStatusService.logRetention(new MemoryActivity(daysAgo(29), 0), CLOCK), Level.WARNING, "1 day left");
            expectItem(SystemStatusService.logRetention(new MemoryActivity(daysAgo(31), 12), CLOCK), Level.WARNING,
                    "12 past 30 days");
        });

        check("Status: other terminal, database and disk report problems without throwing", () -> {
            expectItem(SystemStatusService.otherTerminal(null), Level.INFO, "Not configured");
            expectItem(SystemStatusService.otherTerminal("  "), Level.INFO, "Not configured");
            expectItem(SystemStatusService.otherTerminal("http://127.0.0.1:1/"), Level.WARNING, "Not reachable");
            expectItem(SystemStatusService.otherTerminal("not a url"), Level.WARNING, "Not reachable");
            expectItem(SystemStatusService.database(() -> {
                throw new SQLException("down");
            }), Level.ERROR, "Not connected");
            expectItem(SystemStatusService.database(Database::getConnection), Level.OK, "Connected");
            StatusItem disk = SystemStatusService.diskSpace(() -> Path.of(System.getProperty("java.io.tmpdir"), "no", "such"))
                    .status();
            expectTrue(disk.value().endsWith(" free"), "nearest existing folder used: " + disk);
        });

        check("Status: one failing check never hides the others; refused without permission", () -> {
            SystemStatusService status = new SystemStatusService(s -> ADMIN, List.of(
                    () -> {
                        throw new IllegalStateException("boom");
                    },
                    SystemStatusService.otherTerminal(null)));
            List<StatusItem> report = status.report("s");
            expectTrue(report.size() == 2, "both reported");
            expectTrue(report.get(0).level() == Level.ERROR, "failure shown as a problem");
            expectTrue(report.get(1).level() == Level.INFO, "other check still there");
            StatusItem named = SystemStatusService.pendingSync(() -> {
                throw new IllegalStateException("database down");
            }, () -> false).status();
            expectText(named.name(), "Pending Sync");
            expectTrue(named.level() == Level.ERROR, "a check that cannot read the database keeps its name");
            SystemStatusService refused = new SystemStatusService(s -> {
                throw new SystemException("no");
            }, List.of());
            expectThrows(() -> refused.report("s"), SystemException.class);
            List<StatusItem> down = new SystemStatusService(s -> {
                throw new IllegalStateException("database down");
            }, List.of(SystemStatusService.otherTerminal(null))).report("s");
            expectTrue(down.size() == 1 && down.get(0).level() == Level.ERROR, "only says the database is down");
            expectText(down.get(0).value(), "Not connected");
        });
    }

    static Instant daysAgo(int days) {
        return NOW.minus(Duration.ofDays(days)).minus(Duration.ofMinutes(1));
    }

    static BackupRecord backupAt(Instant at) {
        return new BackupRecord(0, at, "T01", BackupRecord.BACKUP, "b.sql.gz", "C:\\b", 10L, BackupRecord.SUCCESS, null, "x");
    }

    static void expectItem(StatusProvider provider, Level level, String value) {
        StatusItem item = provider.status();
        expectText(item.value(), value);
        expectTrue(item.level() == level, item.name() + " level was " + item.level());
    }

    /* ------------------------------ file format ------------------------------ */

    static void fileFormatChecks() {
        check("Backup file: a whole file passes; wrong, cut-off and edited files are refused", () -> {
            String good = "-- NICKEL SALES CENTER BACKUP v1 created=2026-11-04T02:00:00Z terminal=T01\n"
                    + "SET FOREIGN_KEY_CHECKS=0;\nDROP TABLE IF EXISTS `A`;\nSET FOREIGN_KEY_CHECKS=1;\n"
                    + "-- END OF BACKUP tables=1 statements=3\n";
            DatabaseDump.Summary summary = DatabaseDump.validate(new BufferedReader(new StringReader(good)));
            expectTrue(summary.statements() == 3 && summary.tables() == 1, "summary read");
            expectText(summary.terminal(), "T01");

            expectRefused("hello\n", "not a Nickel Sales Center backup");
            expectRefused("", "not a Nickel Sales Center backup");
            expectRefused(good.substring(0, good.indexOf("-- END")), "incomplete");
            expectRefused(good.replace("DROP TABLE IF EXISTS `A`;\n", ""), "changed");
            expectRefused(good + "DROP TABLE `Users`;\n", "changed");
            expectRefused(good.replace("created=2026-11-04T02:00:00Z", "created=yesterday"), "changed");
            expectRefused(good.replace("statements=3", "statements=99999999999"), "changed");
        });
    }

    static void expectRefused(String file, String words) throws IOException {
        try {
            DatabaseDump.validate(new BufferedReader(new StringReader(file)));
            throw new AssertionError("expected it to be refused: " + words);
        } catch (IllegalArgumentException e) {
            expectTrue(e.getMessage().contains(words), e.getMessage());
        }
    }

    /* ----------------------------- backup / restore ----------------------------- */

    static void backupChecks(Path temp) throws Exception {
        check("Dump: awkward values come back exactly (quotes, backslash, new lines, accents, NULL, times, blobs)", () -> {
            try (Connection a = open(DB_A); Statement s = a.createStatement()) {
                s.execute("SET time_zone='+00:00'");
                s.execute("CREATE TABLE Tricky (Id INT AUTO_INCREMENT PRIMARY KEY, Name VARCHAR(100) NULL, "
                        + "Price DECIMAL(12,2) NULL, Qty DECIMAL(12,3) NULL, Flag BOOLEAN NOT NULL, At DATETIME(3) NULL, "
                        + "Stamp TIMESTAMP NULL, Data BLOB NULL, Note TEXT NULL)");
                s.execute("INSERT INTO Tricky (Name, Price, Qty, Flag, At, Stamp, Data, Note) VALUES "
                        + "('O''Brien \\\\ \"x\"; -- DROP', 12.50, 100.500, TRUE, '2026-11-04 01:02:03.456', "
                        + "'2026-11-04 01:02:03', X'00FF10', 'line1\\nline2\\r\\n-- not a comment'), "
                        + "('Pe\u00F1a \u20B1', 0.00, 0.000, FALSE, NULL, NULL, NULL, NULL), "
                        + "(NULL, NULL, NULL, FALSE, NULL, NULL, NULL, '')");
            }
            // Columns with a default time keep their value; computed columns are left to MySQL to work out.
            exec(DB_A, "CREATE TABLE Dated (Id INT PRIMARY KEY, Qty INT NOT NULL, "
                    + "Made TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "Changed TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                    + "Doubled INT GENERATED ALWAYS AS (Qty * 2) STORED, Tripled INT GENERATED ALWAYS AS (Qty * 3) VIRTUAL)");
            exec(DB_A, "INSERT INTO Dated (Id, Qty, Made, Changed) VALUES (1, 5, '2020-01-02 03:04:05', '2021-06-07 08:09:10')");
            copy(DB_A, DB_B);
            expectText(rowsOf(DB_B, "Tricky"), rowsOf(DB_A, "Tricky"));
            expectText(rowsOf(DB_B, "Dated"), rowsOf(DB_A, "Dated"));
            expectTrue(rowsOf(DB_B, "Dated").contains("|10|15"), "computed columns worked out again");
            expectTrue(rowsOf(DB_B, "Tricky").contains("O'Brien \\ \"x\"; -- DROP"), "quotes kept");
            expectTrue(rowsOf(DB_B, "Tricky").contains("Pe\u00F1a \u20B1"), "accents kept");
            expectTrue(rowsOf(DB_B, "Tricky").contains("00FF10"), "blob kept");
            try (Connection b = open(DB_B); Statement s = b.createStatement()) {
                s.execute("INSERT INTO Tricky (Flag) VALUES (TRUE)");   // AUTO_INCREMENT carries on
                try (ResultSet rs = s.executeQuery("SELECT MAX(Id) FROM Tricky")) {
                    rs.next();
                    expectTrue(rs.getInt(1) == 4, "auto-increment continues");
                }
            }
        });

        check("Dump: a real backup of the live database restores to an identical copy (live data only read)", () -> {
            recreate(DB_B);
            StringWriter text = new StringWriter();
            try (Connection live = Database.getConnection()) {
                DatabaseDump.write(live, text, NOW, "T01");
            }
            try (Connection b = open(DB_B)) {
                DatabaseDump.restore(b, new BufferedReader(new StringReader(text.toString())));
            }
            Map<String, Long> live = new HashMap<>();
            try (Connection c = Database.getConnection()) {
                live = checksums(c);
            }
            Map<String, Long> copy;
            try (Connection c = open(DB_B)) {
                copy = checksums(c);
            }
            expectTrue(!live.isEmpty(), "tables found");
            expectTrue(live.equals(copy), "every table identical:\n live " + live + "\n copy " + copy);
            try (Connection c = open(DB_B); Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT @@FOREIGN_KEY_CHECKS")) {
                rs.next();
                expectTrue(rs.getInt(1) == 1, "foreign key checks are on");
            }
        });

        recreate(DB_A);
        try (Connection a = open(DB_A); Statement s = a.createStatement()) {
            s.execute("CREATE TABLE Stock (Id INT PRIMARY KEY, Qty INT NOT NULL)");
            s.execute("INSERT INTO Stock VALUES (1, 10), (2, 20)");
        }
        Path folder = temp.resolve("backups");
        MemoryBackups records = new MemoryBackups();
        List<String> audit = new ArrayList<>();
        List<Path> savedFolders = new ArrayList<>();
        BackupService service = new BackupService(() -> open(DB_A), records, s -> ADMIN,
                (event, user) -> audit.add(event), "T01", folder, savedFolders::add, CLOCK);
        Path[] goodBackup = new Path[1];

        check("Backup: creates the folder and a complete file, with no .part left, recorded and audited", () -> {
            BackupRecord record = service.backup("s");
            expectText(record.fileName(), "nickelsales-T01-20261104-100000.sql.gz");   // Philippine time
            goodBackup[0] = folder.resolve(record.fileName());
            expectTrue(Files.size(goodBackup[0]) == record.sizeBytes(), "size recorded");
            expectTrue(Stream.of(folder.toFile().list()).noneMatch(n -> n.endsWith(".part")), "no .part left");
            expectTrue(records.rows.size() == 1 && records.rows.get(0).succeeded(), "recorded");
            expectTrue(audit.get(0).equals("BACKUP_CREATED target=" + record.fileName()), "audited: " + audit);
            expectTrue(service.check("s", goodBackup[0]).tables() == 1, "the file checks out");
        });

        check("Restore: brings the data back, keeps a safety backup, and is recorded and audited", () -> {
            exec(DB_A, "UPDATE Stock SET Qty = 99 WHERE Id = 1");
            exec(DB_A, "DELETE FROM Stock WHERE Id = 2");
            records.rows.clear();
            audit.clear();
            service.restore("s", goodBackup[0]);
            expectText(rowsOf(DB_A, "Stock"), "1|10\n2|20\n");
            Path safety = folder.resolve("nickelsales-safety-T01-20261104-100000.sql.gz");
            expectTrue(Files.exists(safety), "safety backup kept");
            expectTrue(text(safety).contains("(1,99)"), "the safety backup holds the data from before the restore");
            expectTrue(records.rows.size() == 2, "two records: " + records.rows);
            expectText(records.rows.get(0).kind(), BackupRecord.SAFETY);
            expectText(records.rows.get(1).kind(), BackupRecord.RESTORE);
            expectTrue(audit.contains("RESTORE_COMPLETED target=" + goodBackup[0].getFileName()), "audited: " + audit);
        });

        check("Restore: a damaged or wrong file is refused before anything is touched", () -> {
            exec(DB_A, "UPDATE Stock SET Qty = 11 WHERE Id = 1");
            String before = rowsOf(DB_A, "Stock");
            Path cut = temp.resolve("cut.sql.gz");
            byte[] bytes = Files.readAllBytes(goodBackup[0]);
            Files.write(cut, java.util.Arrays.copyOf(bytes, bytes.length / 2));
            Path text = temp.resolve("notes.sql.gz");
            Files.writeString(text, "just some text");
            Path other = temp.resolve("other.sql.gz");
            gzip(other, "SELECT 1;\n");
            records.rows.clear();
            for (Path bad : List.of(cut, text, other, temp.resolve("missing.sql.gz"))) {
                expectThrows(() -> service.check("s", bad), SystemException.class);
                expectThrows(() -> service.restore("s", bad), SystemException.class);
            }
            expectText(rowsOf(DB_A, "Stock"), before);
            expectTrue(records.rows.isEmpty(), "no safety backup was even started: " + records.rows);
        });

        check("Restore: a failure part-way puts the previous data back", () -> {
            String before = rowsOf(DB_A, "Stock");
            Path bad = temp.resolve("fails-midway.sql.gz");
            gzip(bad, "-- NICKEL SALES CENTER BACKUP v1 created=2026-11-04T02:00:00Z terminal=T01\n"
                    + "DROP TABLE IF EXISTS `Stock`;\nTHIS IS NOT SQL;\n-- END OF BACKUP tables=1 statements=2\n");
            records.rows.clear();
            audit.clear();
            expectMessage(() -> service.restore("s", bad), "The restore failed, so the previous data was put back. No data was lost.");
            expectText(rowsOf(DB_A, "Stock"), before);
            expectTrue(audit.contains("RESTORE_FAILED target=fails-midway.sql.gz"), "audited: " + audit);
            BackupRecord last = records.rows.get(records.rows.size() - 1);
            expectText(last.result(), BackupRecord.FAILED);
        });

        check("Restore: stops, changing nothing, if the safety backup cannot be made", () -> {
            String before = rowsOf(DB_A, "Stock");
            Path notAFolder = temp.resolve("a-file");
            Files.writeString(notAFolder, "x");
            BackupService noFolder = new BackupService(() -> open(DB_A), new MemoryBackups(), s -> ADMIN, null, "T01",
                    notAFolder, p -> { }, CLOCK);
            try {
                noFolder.restore("s", goodBackup[0]);
                throw new AssertionError("expected it to stop");
            } catch (SystemException e) {
                expectTrue(e.getMessage().startsWith("The restore was cancelled"), e.getMessage());
                expectTrue(e.getMessage().contains("Nothing was changed"), e.getMessage());
            }
            expectText(rowsOf(DB_A, "Stock"), before);
        });

        check("Backup: a bad folder or a stopped database gives a clear message, a FAILED record, no crash", () -> {
            Path notAFolder = temp.resolve("a-file-2");
            Files.writeString(notAFolder, "x");
            MemoryBackups history = new MemoryBackups();
            List<String> events = new ArrayList<>();
            BackupService bad = new BackupService(() -> open(DB_A), history, s -> ADMIN, (e, u) -> events.add(e), "T01",
                    notAFolder, p -> { }, CLOCK);
            expectMessage(() -> bad.backup("s"), "The backup folder cannot be used. Check that the drive is connected.");
            expectTrue(history.rows.size() == 1 && !history.rows.get(0).succeeded(), "FAILED recorded");
            expectTrue(events.equals(List.of("BACKUP_FAILED")), "audited: " + events);

            Path empty = temp.resolve("empty");
            BackupService down = new BackupService(() -> {
                throw new SQLException("down");
            }, new MemoryBackups(), s -> ADMIN, null, "T01", empty, p -> { }, CLOCK);
            expectMessage(() -> down.backup("s"), "The database could not be read. Check that MySQL is running and try again.");
            expectTrue(empty.toFile().list().length == 0, "no half-written file left");
        });

        check("Backup: history failing never fails the backup; folder change is saved; permission is required", () -> {
            MemoryBackups broken = new MemoryBackups();
            broken.broken = true;
            Path other = temp.resolve("other-folder");
            BackupService service2 = new BackupService(() -> open(DB_A), broken, s -> ADMIN, null, "T01", other,
                    p -> { }, Clock.fixed(NOW.plusSeconds(5), ZoneOffset.UTC));
            expectTrue(service2.backup("s").succeeded(), "backup still made");

            service.changeFolder("s", other);
            expectTrue(service.folder().equals(other) && savedFolders.equals(List.of(other)), "folder remembered");

            BackupService refused = new BackupService(() -> open(DB_A), new MemoryBackups(), s -> {
                throw new SystemException("no");
            }, null, "T01", other, p -> { }, CLOCK);
            expectThrows(() -> refused.backup("s"), SystemException.class);
            expectThrows(() -> refused.restore("s", goodBackup[0]), SystemException.class);
            expectThrows(() -> refused.check("s", goodBackup[0]), SystemException.class);
            expectThrows(() -> refused.changeFolder("s", other), SystemException.class);
            expectThrows(() -> refused.history("s"), SystemException.class);
        });
    }

    /* --------------------------- scratch database helpers --------------------------- */

    /** A connection to scratch database {@code name}, with the app's own server and settings. */
    static Connection open(String name) throws SQLException {
        String url = shared.Settings.get("NSC_DB_URL", Database.DEFAULT_URL);
        return DriverManager.getConnection(url.replace("/NickelSales_Database", "/" + name),
                shared.Settings.get("NSC_DB_USER", "root"), shared.Settings.get("NSC_DB_PASSWORD", ""));
    }

    static void recreate(String name) throws SQLException {
        drop(name);
        try (Connection c = Database.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE `" + name + "`");
        }
    }

    /** Only ever called with this test's own two scratch names. */
    static void drop(String name) throws SQLException {
        if (!name.equals(DB_A) && !name.equals(DB_B)) {
            throw new IllegalArgumentException(name);
        }
        try (Connection c = Database.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS `" + name + "`");
        }
    }

    static void copy(String from, String to) throws Exception {
        StringWriter text = new StringWriter();
        try (Connection a = open(from)) {
            DatabaseDump.write(a, text, NOW, "T01");
        }
        DatabaseDump.validate(new BufferedReader(new StringReader(text.toString())));
        try (Connection b = open(to)) {
            DatabaseDump.restore(b, new BufferedReader(new StringReader(text.toString())));
        }
    }

    static void exec(String database, String sql) throws SQLException {
        try (Connection c = open(database); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    /** Every row of {@code table} as text ("1|10\n"), with binary columns in hex. */
    static String rowsOf(String database, String table) throws SQLException {
        try (Connection c = open(database); Statement s = c.createStatement()) {
            s.execute("SET time_zone='+00:00'");
            try (ResultSet rs = s.executeQuery("SELECT * FROM " + table + " ORDER BY 1")) {
                int columns = rs.getMetaData().getColumnCount();
                StringBuilder out = new StringBuilder();
                while (rs.next()) {
                    for (int i = 1; i <= columns; i++) {
                        boolean binary = rs.getMetaData().getColumnType(i) == java.sql.Types.LONGVARBINARY
                                || rs.getMetaData().getColumnType(i) == java.sql.Types.VARBINARY
                                || rs.getMetaData().getColumnType(i) == java.sql.Types.BLOB;
                        byte[] bytes = binary ? rs.getBytes(i) : null;
                        out.append(i > 1 ? "|" : "")
                                .append(bytes != null ? java.util.HexFormat.of().withUpperCase().formatHex(bytes) : rs.getString(i));
                    }
                    out.append('\n');
                }
                return out.toString();
            }
        }
    }

    /** CHECKSUM TABLE of every table in {@code c}'s database. */
    static Map<String, Long> checksums(Connection c) throws SQLException {
        Map<String, Long> out = new HashMap<>();
        List<String> tables = new ArrayList<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT TABLE_NAME FROM information_schema.TABLES "
                     + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        for (String table : tables) {
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("CHECKSUM TABLE `" + table + "`")) {
                rs.next();
                out.put(table, rs.getLong(2));
            }
        }
        return out;
    }

    static void gzip(Path file, String content) throws IOException {
        try (Writer out = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(file)),
                StandardCharsets.UTF_8))) {
            out.write(content);
        }
    }

    static String text(Path gz) throws IOException {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(gz)),
                StandardCharsets.UTF_8))) {
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                out.append(line).append('\n');
            }
            return out.toString();
        }
    }

    static void deleteTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    /* ------------------------------- stand-ins ------------------------------- */

    static final class MemorySync implements SyncLogRepository {
        final Map<Long, SyncLogEntry> rows = new HashMap<>();
        boolean broken;
        long next = 1;

        private void guard() {
            if (broken) {
                throw new RuntimeException("Communications link failure");
            }
        }

        @Override
        public long add(SyncLogEntry e) {
            guard();
            rows.put(next, e);
            return next++;
        }

        @Override
        public void markSynced(long id, Instant at) {
            guard();
            SyncLogEntry e = rows.get(id);
            rows.put(id, new SyncLogEntry(id, e.createdAt(), at, e.sourceTerminal(), e.targetTerminal(), e.dataType(),
                    e.recordId(), SyncLogEntry.SYNCED, e.retryCount(), null));
        }

        @Override
        public void markFailed(long id, String error, Instant at) {
            guard();
            SyncLogEntry e = rows.get(id);
            rows.put(id, new SyncLogEntry(id, e.createdAt(), at, e.sourceTerminal(), e.targetTerminal(), e.dataType(),
                    e.recordId(), SyncLogEntry.FAILED, e.retryCount() + 1, error));
        }

        @Override
        public List<SyncLogEntry> find(SyncLogFilter filter, int limit) {
            guard();
            return List.copyOf(rows.values());
        }

        @Override
        public Optional<Instant> lastSuccess() {
            guard();
            return Optional.empty();
        }
    }

    static final class MemoryBackups implements BackupRecordRepository {
        final List<BackupRecord> rows = new ArrayList<>();
        boolean broken;

        @Override
        public void add(BackupRecord record) {
            if (broken) {
                throw new RuntimeException("Communications link failure");
            }
            rows.add(record);
        }

        @Override
        public List<BackupRecord> findRecent(int limit) {
            return List.copyOf(rows);
        }

        @Override
        public Optional<BackupRecord> latestBackup() {
            return rows.stream().filter(r -> BackupRecord.BACKUP.equals(r.kind()) && r.succeeded())
                    .reduce((a, b) -> b);
        }
    }

    /** Only what the retention check reads. */
    record MemoryActivity(Instant oldestEntry, long older) implements ActivityLogRepository {
        @Override
        public void append(ActivityEntry entry) { }

        @Override
        public List<ActivityEntry> find(ActivityFilter filter, int limit) {
            return List.of();
        }

        @Override
        public List<String> userIds() {
            return List.of();
        }

        @Override
        public Optional<Instant> oldest() {
            return Optional.ofNullable(oldestEntry);
        }

        @Override
        public long countOlderThan(Instant time) {
            return older;
        }
    }

    /* ------------------------------ helpers ------------------------------ */

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
        }
    }

    static void expectText(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected \"" + expected + "\" but was \"" + actual + "\"");
        }
    }

    static void expectTrue(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }

    static void expectThrows(Body body, Class<? extends Throwable> type) {
        try {
            body.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                return;
            }
            throw new AssertionError("expected " + type.getSimpleName() + " but got " + t);
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }

    static void expectMessage(Body body, String message) {
        try {
            body.run();
        } catch (SystemException e) {
            expectText(e.getMessage(), message);
            return;
        } catch (Exception e) {
            throw new AssertionError("expected SystemException but got " + e);
        }
        throw new AssertionError("expected: " + message);
    }
}
