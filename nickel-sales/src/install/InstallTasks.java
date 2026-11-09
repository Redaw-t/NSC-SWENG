package install;

import shared.LogFile;
import shared.Settings;
import system.repository.DatabaseDump;
import system.service.BackupService;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * What the Windows installer (installer/NickelSales.iss) runs after it has copied the files,
 * with the app's own Java runtime:
 *
 *   java -cp <app jars> install.InstallTasks setup <appDir> <dataDir> <answers> <result>
 *
 * appDir  = the install folder (app\, runtime\, mysql\, database\)
 * dataDir = %ProgramData%\NickelSales: the settings file, logs\, and mysql\ (the database files,
 *           my.ini and root.cnf, readable by administrators only)
 * answers = the wizard's answers (properties, UTF-8): the NSC_* settings to write (store details,
 *           receipt width). An update has none, except BACKUP_FIRST=<new version> when it installs
 *           over kept data with no program left to back up with (then it backs up before the schema runs).
 * result  = written here for the installer: BACKUP_FILE, or ERROR with the reason.
 *
 * Safe to run again (that is what an update does): it keeps the database, its passwords and
 * the settings, and only adds what is missing.
 *
 *   1. MySQL (the ZIP edition in appDir\mysql) as its own Windows service, NickelSalesDB, on
 *      port 3307 and reachable from this PC only, so it never clashes with another MySQL;
 *   2. a random root password (kept in dataDir\mysql\root.cnf, for support);
 *   3. the schemas: database\NickelSales-Database-Config.sql (the store's records; never the
 *      demo data) and database\NickelSales-AppConfig.sql (this installation's terminals and settings);
 *   4. the app's own MySQL user, nickelsales, with a random password and rights on those two
 *      databases only;
 *   5. the settings file, readable by this PC's users, changeable by administrators only.
 *
 * Main POS or Additional POS, the terminal ID and the first admin account are not done here: the
 * app's Installation Setup screen asks for them the first time it starts.
 *
 * Before an update replaces any file, the installer also runs (the NEW version's jars, with the
 * Java runtime already installed):
 *
 *   java -cp <new jars> install.InstallTasks backup <dataDir> <oldVersion> <newVersion> <result>
 *
 * which backs the whole database up to dataDir\backups, in the app's own backup format, so an
 * administrator can put it back from Settings &gt; Backup &amp; Restore. Result: BACKUP_FILE, or ERROR
 * (then setup stops before changing anything).
 *
 * Everything is also written to dataDir\logs\install.log.
 */
public final class InstallTasks {

    public static final String SERVICE = "NickelSalesDB";
    public static final int PORT = 3307;
    static final String DATABASE = "NickelSales_Database";
    static final String APPCONFIG_DATABASE = "nickelsales_appconfig";
    static final String APP_USER = "nickelsales";
    private static final int ACCESS_DENIED = 1045;   // MySQL: wrong user or password
    static final String JDBC_OPTIONS = "useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";

    // Windows' built-in accounts, by SID so it works in every Windows language
    private static final String SYSTEM = "*S-1-5-18";
    private static final String ADMINISTRATORS = "*S-1-5-32-544";
    private static final String USERS = "*S-1-5-32-545";

    /** Settings the installer owns, in the order they are written. */
    static final List<String> SETTING_ORDER = List.of("NSC_DB_URL", "NSC_DB_USER", "NSC_DB_PASSWORD",
            "NSC_TERMINAL_ID", "NSC_STORE_NAME", "NSC_STORE_ADDRESS", "NSC_STORE_PROPRIETOR",
            "NSC_STORE_TIN", "NSC_STORE_PHONE", "NSC_RECEIPT_WIDTH");

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    private final Path appDir;
    private final Path dataDir;
    private final Map<String, String> result = new LinkedHashMap<>();

    InstallTasks(Path appDir, Path dataDir) {
        this.appDir = appDir;
        this.dataDir = dataDir;
    }

    public static void main(String[] args) {
        if (args.length == 5 && args[0].equals("setup")) {
            Path dataDir = Path.of(args[2]);
            Path resultFile = Path.of(args[4]);
            startLog(dataDir.resolve("logs"));
            InstallTasks tasks = new InstallTasks(Path.of(args[1]), dataDir);
            System.exit(tasks.runAndReport(() -> tasks.setup(Settings.readFile(Path.of(args[3]))), resultFile));
        } else if (args.length == 5 && args[0].equals("backup")) {
            Path dataDir = Path.of(args[1]);
            startLog(dataDir.resolve("logs"));
            InstallTasks tasks = new InstallTasks(null, dataDir);
            System.exit(tasks.runAndReport(() -> tasks.backupBeforeUpdate(args[2], args[3]), Path.of(args[4])));
        } else {
            System.err.println("Usage: install.InstallTasks setup <appDir> <dataDir> <answers> <result>");
            System.err.println("       install.InstallTasks backup <dataDir> <oldVersion> <newVersion> <result>");
            System.exit(2);
        }
    }

    private interface Task {
        void run() throws Exception;
    }

    /** Runs the task; writes the result file (ERROR=... on failure); returns the exit code. */
    private int runAndReport(Task task, Path resultFile) {
        int code = 0;
        try {
            task.run();
            System.out.println("[INSTALL] Done.");
        } catch (Exception e) {
            System.out.println("[INSTALL] FAILED: " + e.getMessage());
            e.printStackTrace(System.out);
            result.put("ERROR", e.getMessage() == null ? e.toString() : e.getMessage());
            code = 1;
        }
        try {
            writeResult(resultFile, result);
        } catch (IOException e) {
            System.out.println("[INSTALL] Could not write " + resultFile + ": " + e);
            code = 1;
        }
        return code;
    }

    // ------------------------------------------------------------------ setup

    void setup(Map<String, String> answers) throws Exception {
        System.out.println("[INSTALL] " + LocalDateTime.now() + " setting up " + appDir + ", data in " + dataDir);
        Path mysqlDir = dataDir.resolve("mysql");
        Path logs = dataDir.resolve("logs");
        Files.createDirectories(mysqlDir);
        Files.createDirectories(logs);
        icacls(mysqlDir, "/inheritance:r", "/grant:r", SYSTEM + ":(OI)(CI)F", ADMINISTRATORS + ":(OI)(CI)F");
        icacls(logs, "/grant", USERS + ":(OI)(CI)M");   // every cashier's Windows account writes the app's log
        Path backups = dataDir.resolve("backups");       // before-update backups (the Start menu links here)
        Files.createDirectories(backups);
        icacls(backups, "/grant", USERS + ":(OI)(CI)RX");

        Path myIni = mysqlDir.resolve("my.ini");
        Path dataFiles = mysqlDir.resolve("data");
        Path rootCnf = mysqlDir.resolve("root.cnf");
        Path mysqld = appDir.resolve("mysql\\bin\\mysqld.exe");

        boolean serviceExists = run(List.of("sc.exe", "query", SERVICE)) == 0;
        if (serviceExists) {
            step("Stopping the database service");
            run(List.of("net", "stop", SERVICE));   // fails harmlessly when it is already stopped
        } else if (portInUse(PORT)) {
            throw new IllegalStateException("Port " + PORT + " is already used by another program on this PC. "
                    + "Close it (or ask support) and run setup again.");
        }

        if (!Files.exists(myIni)) {
            Files.writeString(myIni, myIni(dataFiles, mysqlDir.resolve("mysqld.err")), StandardCharsets.UTF_8);
        }
        boolean fresh = !Files.isDirectory(dataFiles) || isEmpty(dataFiles);
        if (fresh) {
            step("Creating the database files (this takes a minute)");
            mustRun(List.of(mysqld.toString(), "--defaults-file=" + myIni, "--initialize-insecure", "--console"));
        } else if (!Files.exists(rootCnf)) {
            throw new IllegalStateException("The database files exist but " + rootCnf
                    + " is missing, so setup can't sign in to the database. Ask support.");
        }

        step("Registering and starting the database service " + SERVICE);
        if (serviceExists) {
            run(List.of(mysqld.toString(), "--remove", SERVICE));   // re-registered below: the folder may have moved
        }
        mustRun(List.of(mysqld.toString(), "--install", SERVICE, "--defaults-file=" + myIni));
        mustRun(List.of("net", "start", SERVICE));

        String rootPassword = rootPassword(rootCnf, fresh);
        if (!fresh && !isBlank(answers.get("BACKUP_FIRST"))) {
            // Installing over data whose program was uninstalled: there was no old program to
            // back up with before the files were copied, so back up now, before the schema runs.
            backupBeforeUpdate("reinstall", answers.get("BACKUP_FIRST"));
        }

        step("Creating or updating the tables");
        for (String schema : List.of("NickelSales-Database-Config.sql", "NickelSales-AppConfig.sql")) {
            mustRun(List.of(appDir.resolve("mysql\\bin\\mysql.exe").toString(), "--defaults-file=" + rootCnf,
                    "--default-character-set=utf8mb4"), appDir.resolve("database").resolve(schema));
        }

        Path settingsFile = dataDir.resolve(Settings.FILE_NAME);
        Map<String, String> settings = Settings.readFile(settingsFile);
        String appPassword = APP_USER.equals(settings.get("NSC_DB_USER")) && !isBlank(settings.get("NSC_DB_PASSWORD"))
                ? settings.get("NSC_DB_PASSWORD") : newPassword();
        step("Giving the app its own database account (" + APP_USER + ")");
        try (Connection root = waitForDatabase("root", rootPassword); Statement s = root.createStatement()) {
            String user = "'" + APP_USER + "'@'localhost'";
            s.execute("CREATE USER IF NOT EXISTS " + user + " IDENTIFIED BY '" + appPassword + "'");
            s.execute("ALTER USER " + user + " IDENTIFIED BY '" + appPassword + "'");
            s.execute("GRANT ALL PRIVILEGES ON `" + DATABASE + "`.* TO " + user);
            s.execute("GRANT ALL PRIVILEGES ON `" + APPCONFIG_DATABASE + "`.* TO " + user);
        }

        step("Writing the settings file " + settingsFile);
        Map<String, String> merged = mergeSettings(settings, answers, appPassword);
        boolean newFile = !Files.exists(settingsFile);
        Files.writeString(settingsFile, settingsText(merged), StandardCharsets.UTF_8);
        if (newFile) {
            icacls(settingsFile, "/inheritance:r", "/grant:r", SYSTEM + ":F", ADMINISTRATORS + ":F", USERS + ":R");
        }
    }

    /** my.ini for the NickelSalesDB service. */
    static String myIni(Path dataFiles, Path errorLog) {
        return String.join("\r\n",
                "# Nickel Sales Center database (service " + SERVICE + "). Written by the installer.",
                "[mysqld]",
                "port=" + PORT,
                "bind-address=127.0.0.1",
                "mysqlx=OFF",
                "datadir=" + slashes(dataFiles),
                "log-error=" + slashes(errorLog),
                "character-set-server=utf8mb4",
                "default-storage-engine=InnoDB",
                "innodb_buffer_pool_size=256M",
                "max_connections=50",
                "",
                "[client]",
                "port=" + PORT,
                "");
    }

    static String rootCnf(String password) {
        return String.join("\r\n", "[client]", "user=root", "password=" + password,
                "host=localhost", "port=" + PORT, "");
    }

    /**
     * The settings to write: what the file had, the wizard's NSC_* answers on top, and the
     * database account. Other keys in the file (added by support) are kept.
     */
    static Map<String, String> mergeSettings(Map<String, String> existing, Map<String, String> answers,
                                             String appPassword) {
        Map<String, String> merged = new LinkedHashMap<>(existing);
        answers.forEach((name, value) -> {
            if (name.startsWith("NSC_")) {
                merged.put(name, value);
            }
        });
        merged.put("NSC_DB_URL", "jdbc:mysql://localhost:" + PORT + "/" + DATABASE + "?" + JDBC_OPTIONS);
        merged.put("NSC_DB_USER", APP_USER);
        merged.put("NSC_DB_PASSWORD", appPassword);
        return merged;
    }

    /** The settings file's text: the installer's settings in a fixed order, then any others by name. */
    static String settingsText(Map<String, String> settings) {
        StringBuilder text = new StringBuilder()
                .append("# Nickel Sales Center settings, written by the installer.\r\n")
                .append("# Holds the database password: only administrators can change this file.\r\n")
                .append("# Names and meanings: see nickelsales.properties.example in the app's source.\r\n");
        Map<String, String> rest = new TreeMap<>(settings);
        for (String name : SETTING_ORDER) {
            if (rest.containsKey(name)) {
                text.append(name).append('=').append(escape(rest.remove(name))).append("\r\n");
            }
        }
        rest.forEach((name, value) -> text.append(name).append('=').append(escape(value)).append("\r\n"));
        return text.toString();
    }

    /** A value as a properties file needs it (backslashes doubled; line breaks can't appear). */
    static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\r", "").replace("\n", " ");
    }

    // ------------------------------------------------------ backup before update

    /** Before-update backups kept; older ones are deleted. */
    static final int KEEP_UPDATE_BACKUPS = 5;
    static final String UPDATE_BACKUP_PREFIX = "NickelSales-before-update-";

    /**
     * Backs the database up to dataDir\backups as a file the app's Backup screen can restore
     * (gzip, {@link DatabaseDump} format), written as .part, checked, then renamed, as the app does.
     */
    void backupBeforeUpdate(String fromVersion, String toVersion) throws Exception {
        System.out.println("[INSTALL] " + LocalDateTime.now() + " updating " + fromVersion + " to " + toVersion);
        step("Backing up the store's data before updating");
        run(List.of("net", "start", SERVICE));   // fails harmlessly when it is already running
        String rootPassword = Settings.readFile(dataDir.resolve("mysql\\root.cnf")).get("password");
        String terminal = Settings.readFile(dataDir.resolve(Settings.FILE_NAME)).getOrDefault("NSC_TERMINAL_ID", "T01");

        Path folder = dataDir.resolve("backups");
        Files.createDirectories(folder);
        icacls(folder, "/grant", USERS + ":(OI)(CI)RX");   // so an administrator can restore it from the app
        Path file = folder.resolve(updateBackupName(fromVersion, toVersion, LocalDateTime.now()));
        Path part = file.resolveSibling(file.getFileName() + ".part");
        try (Connection c = waitForDatabase("root", rootPassword)) {
            c.setCatalog(DATABASE);
            DatabaseDump.Summary written;
            try (Writer out = new BufferedWriter(new OutputStreamWriter(
                    new GZIPOutputStream(Files.newOutputStream(part)), StandardCharsets.UTF_8))) {
                written = DatabaseDump.write(c, out, Instant.now(), terminal);
            }
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    new GZIPInputStream(Files.newInputStream(part)), StandardCharsets.UTF_8))) {
                DatabaseDump.validate(in);
            }
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[INSTALL] Backed up " + written.tables() + " tables to " + file);
        } finally {
            Files.deleteIfExists(part);
        }
        result.put("BACKUP_FILE", file.toString());
        deleteOldUpdateBackups(folder);
    }

    /** NickelSales-before-update-0.1.0-to-0.2.0-20261108-231500.sql.gz (sorts by date within a version pair). */
    static String updateBackupName(String fromVersion, String toVersion, LocalDateTime at) {
        return UPDATE_BACKUP_PREFIX + safe(fromVersion) + "-to-" + safe(toVersion) + "-"
                + at.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + BackupService.EXTENSION;
    }

    private static String safe(String version) {
        return version.replaceAll("[^0-9A-Za-z.]", "_");
    }

    /** Keeps the newest {@link #KEEP_UPDATE_BACKUPS} before-update backups (by file time). */
    static void deleteOldUpdateBackups(Path folder) throws IOException {
        List<Path> backups;
        try (var files = Files.list(folder)) {
            backups = files.filter(p -> p.getFileName().toString().startsWith(UPDATE_BACKUP_PREFIX)
                            && p.getFileName().toString().endsWith(BackupService.EXTENSION))
                    .sorted(Comparator.comparing(InstallTasks::modified).reversed())
                    .toList();
        }
        for (Path old : backups.subList(Math.min(KEEP_UPDATE_BACKUPS, backups.size()), backups.size())) {
            System.out.println("[INSTALL] Deleting an old update backup: " + old.getFileName());
            Files.deleteIfExists(old);
        }
    }

    private static FileTime modified(Path file) {
        try {
            return Files.getLastModifiedTime(file);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }

    // ----------------------------------------------------------- first admin

    // --------------------------------------------------------------- helpers

    private static void step(String text) {
        System.out.println("[INSTALL] " + text + "...");
    }

    /** A 24-character random password of letters and digits (no look-alikes), safe in SQL quotes. */
    static String newPassword() {
        StringBuilder password = new StringBuilder();
        for (int i = 0; i < 24; i++) {
            password.append(PASSWORD_CHARS.charAt(RANDOM.nextInt(PASSWORD_CHARS.length())));
        }
        return password.toString();
    }

    /**
     * The root password, from root.cnf. A new database's root has none yet: it gets a random one,
     * saved to root.cnf first (so it is never lost), then set. A run that stopped between the two
     * is finished off the same way.
     */
    private String rootPassword(Path rootCnf, boolean fresh) throws Exception {
        if (!fresh) {
            String saved = Settings.readFile(rootCnf).get("password");
            try (Connection c = waitForDatabase("root", saved)) {
                return saved;
            } catch (SQLException e) {
                if (e.getErrorCode() != ACCESS_DENIED) {
                    throw e;
                }
                System.out.println("[INSTALL] The saved root password is not set yet; setting it.");
            }
        }
        step("Setting the database administrator (root) password");
        String password = fresh ? newPassword() : Settings.readFile(rootCnf).get("password");
        Files.writeString(rootCnf, rootCnf(password), StandardCharsets.UTF_8);
        try (Connection c = waitForDatabase("root", ""); Statement s = c.createStatement()) {
            s.execute("ALTER USER 'root'@'localhost' IDENTIFIED BY '" + password + "'");
        }
        return password;
    }

    /** Connects, waiting up to a minute for the service to start; a wrong password fails at once. */
    private Connection waitForDatabase(String user, String password) throws Exception {
        String url = "jdbc:mysql://localhost:" + PORT + "/?" + JDBC_OPTIONS;
        SQLException last = null;
        for (int attempt = 0; attempt < 60; attempt++) {
            try {
                return DriverManager.getConnection(url, user, password);
            } catch (SQLException e) {
                if (e.getErrorCode() == ACCESS_DENIED) {
                    throw e;
                }
                last = e;
                Thread.sleep(1000);
            }
        }
        throw new IllegalStateException("The database service did not answer within a minute (see "
                + dataDir.resolve("mysql\\mysqld.err") + ")", last);
    }

    /** Something answers on the port, or it can't be taken. */
    private static boolean portInUse(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1000);
            return true;
        } catch (IOException nobodyThere) {
            // free so far; also try to take it (another program may hold it without answering)
        }
        try (ServerSocket socket = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private static boolean isEmpty(Path dir) throws IOException {
        try (var entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        }
    }

    private static void icacls(Path path, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("icacls", path.toString()));
        command.addAll(List.of(arguments));
        mustRun(command);
    }

    private static void mustRun(List<String> command) throws Exception {
        mustRun(command, null);
    }

    private static void mustRun(List<String> command, Path stdin) throws Exception {
        int code = runProcess(new ProcessBuilder(command), stdin);
        if (code != 0) {
            throw new IllegalStateException(Path.of(command.get(0)).getFileName() + " failed (exit code " + code + ")");
        }
    }

    private static int run(List<String> command) throws Exception {
        return runProcess(new ProcessBuilder(command), null);
    }

    /** Runs a program, copying its output into the log; returns its exit code. */
    private static int runProcess(ProcessBuilder builder, Path stdin) throws Exception {
        System.out.println("[INSTALL] > " + String.join(" ", builder.command()));
        builder.redirectErrorStream(true);
        if (stdin != null) {
            builder.redirectInput(stdin.toFile());
        }
        Process process = builder.start();
        Charset console = Charset.forName(System.getProperty("native.encoding", "UTF-8"));
        try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), console))) {
            for (String line; (line = out.readLine()) != null; ) {
                System.out.println("    " + line);
            }
        }
        return process.waitFor();
    }

    private static String slashes(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** UTF-8 with a BOM, so the installer's LoadStringsFromFile reads names like Peña correctly. */
    private static void writeResult(Path file, Map<String, String> values) throws IOException {
        StringBuilder text = new StringBuilder("﻿");
        values.forEach((name, value) -> text.append(name).append('=').append(value.replace("\r", " ").replace("\n", " "))
                .append("\r\n"));
        Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
    }

    private static void startLog(Path logs) {
        try {
            Files.createDirectories(logs);
            OutputStream file = Files.newOutputStream(logs.resolve("install.log"),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Object lock = new Object();
            PrintStream console = System.out;
            System.setOut(LogFile.tee(console, file, lock));
            System.setErr(LogFile.tee(System.err, file, lock));
        } catch (IOException e) {
            System.err.println("[INSTALL] No install log: " + e);
        }
    }
}
