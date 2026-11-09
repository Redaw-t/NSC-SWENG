package install;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * SELF-TEST for what the Windows installer writes (install.InstallTasks): the settings file,
 * MySQL's my.ini and the generated passwords. Nothing is installed; the MySQL and Windows
 * service steps are tested by running the installer (installer/README.md).
 *
 * Run after any run-demo build, from nickel-sales/:
 *   java -cp "out;lib\jbcrypt-0.4.jar;lib\mysql-connector-j-9.4.0.jar" install.InstallCheck
 */
public class InstallCheck {

    static int passed = 0;
    static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=================================================================");
        System.out.println(" NICKEL SALES CENTER - installer checks");
        System.out.println("=================================================================");

        check("Settings: wizard answers and the database account; other lines kept; ADMIN_* never written", () -> {
            Map<String, String> merged = InstallTasks.mergeSettings(
                    Map.of("NSC_PRINTER_NAME", "POS-58", "NSC_DB_USER", "root", "NSC_TERMINAL_ID", "T09"),
                    Map.of("NSC_TERMINAL_ID", "T01", "NSC_STORE_NAME", "Peña Hardware",
                            "ADMIN_PASSWORD", "Secret123"), "pw123");
            expectText(merged.get("NSC_TERMINAL_ID"), "T01");
            expectText(merged.get("NSC_PRINTER_NAME"), "POS-58");
            expectText(merged.get("NSC_DB_USER"), "nickelsales");
            expectText(merged.get("NSC_DB_PASSWORD"), "pw123");
            expectText(merged.get("NSC_DB_URL"),
                    "jdbc:mysql://localhost:3307/NickelSales_Database?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
            expectTrue(!merged.containsKey("ADMIN_PASSWORD"), "the admin's password must not reach the settings file");
        });

        check("Settings file: reads back exactly (UTF-8, backslashes), installer's names first", () -> {
            Map<String, String> settings = Map.of("NSC_STORE_NAME", "Peña Hardware", "NSC_DB_USER", "nickelsales",
                    "NSC_STORE_ADDRESS", "Unit 5\\B, Naga City", "NSC_PRINTER_NAME", "POS-58");
            String text = InstallTasks.settingsText(settings);
            expectTrue(text.indexOf("NSC_DB_USER=") < text.indexOf("NSC_STORE_NAME=")
                    && text.indexOf("NSC_STORE_ADDRESS=") < text.indexOf("NSC_PRINTER_NAME="), "order:\n" + text);
            Path file = Files.createTempFile("nsc-install", ".properties");
            try {
                Files.writeString(file, "\uFEFF" + text, StandardCharsets.UTF_8);   // with a BOM, as Notepad may save it
                expectTrue(shared.Settings.readFile(file).equals(settings), "read back: " + shared.Settings.readFile(file));
            } finally {
                Files.deleteIfExists(file);
            }
        });

        check("my.ini: port 3307, this PC only, no X Protocol port, forward slashes", () -> {
            String ini = InstallTasks.myIni(Path.of("C:\\ProgramData\\NickelSales\\mysql\\data"),
                    Path.of("C:\\ProgramData\\NickelSales\\mysql\\mysqld.err"));
            for (String line : new String[] {"port=3307", "bind-address=127.0.0.1", "mysqlx=OFF",
                    "datadir=C:/ProgramData/NickelSales/mysql/data"}) {
                expectTrue(ini.contains(line + "\r\n"), "missing " + line + " in\n" + ini);
            }
        });

        check("Generated passwords: 24 letters and digits, all different", () -> {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < 200; i++) {
                String password = InstallTasks.newPassword();
                expectTrue(password.matches("[A-Za-z0-9]{24}"), password);
                expectTrue(seen.add(password), "repeated " + password);
            }
        });

        check("Update backup: named after both versions and the time, as a file the Backup screen takes", () -> {
            String name = InstallTasks.updateBackupName("0.1.0", "0.2.0", java.time.LocalDateTime.of(2026, 11, 8, 23, 15, 0));
            expectText(name, "NickelSales-before-update-0.1.0-to-0.2.0-20261108-231500.sql.gz");
            expectTrue(InstallTasks.updateBackupName("re install", "0.2.0", java.time.LocalDateTime.now())
                    .startsWith("NickelSales-before-update-re_install-to-0.2.0-"), "odd characters replaced");
        });

        check("Update backups: the newest 5 are kept; other files in the folder are never touched", () -> {
            Path folder = Files.createTempDirectory("nsc-backups");
            try {
                for (int i = 1; i <= 7; i++) {
                    Path f = Files.writeString(folder.resolve(InstallTasks.UPDATE_BACKUP_PREFIX + i + ".sql.gz"), "x");
                    Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(1_000_000L * i));
                }
                Files.writeString(folder.resolve("NickelSales-20260101-backup.sql.gz"), "the admin's own backup");
                Files.writeString(folder.resolve(InstallTasks.UPDATE_BACKUP_PREFIX + "9.sql.gz.part"), "unfinished");
                InstallTasks.deleteOldUpdateBackups(folder);
                Set<String> left = new HashSet<>();
                try (var files = Files.list(folder)) {
                    files.forEach(f -> left.add(f.getFileName().toString()));
                }
                String p = InstallTasks.UPDATE_BACKUP_PREFIX;
                expectTrue(left.equals(Set.of(p + "3.sql.gz", p + "4.sql.gz", p + "5.sql.gz", p + "6.sql.gz",
                        p + "7.sql.gz", "NickelSales-20260101-backup.sql.gz", p + "9.sql.gz.part")), "left: " + left);
            } finally {
                try (var files = Files.list(folder)) {
                    for (Path f : files.toList()) {
                        Files.delete(f);
                    }
                }
                Files.delete(folder);
            }
        });

        System.out.println();
        System.out.println(" " + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
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
}
