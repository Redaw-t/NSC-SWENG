package shared;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * This computer's NSC_* settings (database, terminal ID, store details, printer ...), from
 * environment variables and a settings file. Which file, and which wins, depends on who runs:
 *
 *   The installed app (app.Main calls {@link #installedApp()} first):
 *     1. the settings file the installer wrote: %ProgramData%\NickelSales\nickelsales.properties
 *        on Windows, else ~/.nickelsales/nickelsales.properties (or the file NSC_SETTINGS_FILE names);
 *     2. environment variables, for names the file doesn't set;
 *     3. the caller's default.
 *   Everything else (run-demo, DevApp, IDE run configurations, self-tests):
 *     1. environment variables;
 *     2. a settings file only if NSC_SETTINGS_FILE names one. The installer's file is NOT read,
 *        so a PC with the app installed doesn't mix its database account into development runs;
 *     3. the caller's default.
 *
 * The file is plain Java properties in UTF-8, one NSC_NAME=value per line, using the
 * same names as the environment variables (see config/nickelsales.properties.example).
 * In values, write a backslash as \\ (properties treat a single \ as an escape).
 *
 * The file holds the database password, so whoever writes it (the installer) must
 * make it readable by the POS's Windows users only. Read once, on first use.
 */
public final class Settings {

    /** Environment variable naming a settings file to use instead of the standard one. */
    public static final String FILE_VARIABLE = "NSC_SETTINGS_FILE";
    public static final String FILE_NAME = "nickelsales.properties";

    private static volatile Map<String, String> all;
    private static volatile boolean installedApp;

    private Settings() { }

    /**
     * This is the installed app: read the installer's settings file, and let it win over
     * environment variables (see the class comment). Call before anything reads a setting;
     * afterwards it is too late and throws.
     */
    public static synchronized void installedApp() {
        if (all != null) {
            throw new IllegalStateException("Settings were already read; call installedApp() first thing in main");
        }
        installedApp = true;
    }

    /** The settings file in use, or null when none is (a development run without NSC_SETTINGS_FILE). */
    static Path fileInUse() {
        String named = System.getenv(FILE_VARIABLE);
        return installedApp || (named != null && !named.isBlank()) ? file() : null;
    }

    /** Every setting, merged as the class comment says. Unmodifiable. */
    public static Map<String, String> all() {
        Map<String, String> loaded = all;
        if (loaded == null) {
            synchronized (Settings.class) {
                if (all == null) {
                    Path file = fileInUse();
                    Map<String, String> fromFile = file == null ? Map.of() : readFile(file);
                    all = Collections.unmodifiableMap(merge(fromFile, System.getenv(), installedApp));
                }
                loaded = all;
            }
        }
        return loaded;
    }

    /** The file's settings and the environment's, the one that wins on top. */
    public static Map<String, String> merge(Map<String, String> fromFile, Map<String, String> fromEnvironment,
                                            boolean fileWins) {
        Map<String, String> merged = new HashMap<>(fileWins ? fromEnvironment : fromFile);
        merged.putAll(fileWins ? fromFile : fromEnvironment);
        return merged;
    }

    /** The setting, or null when it is set nowhere. A blank value is returned as it is. */
    public static String get(String name) {
        return all().get(name);
    }

    /** The setting, or {@code fallback} when it is not set or blank. */
    public static String get(String name, String fallback) {
        String value = get(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Where the installed app looks for the settings file (it may not exist). */
    public static Path file() {
        String named = System.getenv(FILE_VARIABLE);
        if (named != null && !named.isBlank()) {
            return Path.of(named.trim());
        }
        String programData = System.getenv("ProgramData");
        if (programData != null && !programData.isBlank()) {
            return Path.of(programData, "NickelSales", FILE_NAME);
        }
        return Path.of(System.getProperty("user.home"), ".nickelsales", FILE_NAME);
    }

    /** One line for the start-up log: which settings file is used, if any. */
    public static String describe() {
        Path file = fileInUse();
        if (file == null) {
            return "development run: environment variables only (set " + FILE_VARIABLE + " to use a settings file)";
        }
        return Files.isRegularFile(file) ? "settings file " + file : "no settings file at " + file + " (using defaults)";
    }

    /** The settings in {@code file}, values trimmed; empty if it is missing or unreadable (with a warning). */
    public static Map<String, String> readFile(Path file) {
        Map<String, String> values = new HashMap<>();
        if (!Files.isRegularFile(file)) {
            return values;
        }
        Properties p = new Properties();
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.startsWith("﻿")) {
                text = text.substring(1);   // Notepad and the installer may start the file with a BOM
            }
            p.load(new StringReader(text));
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("[CONFIG] Could not read " + file + ": " + e);
            return values;
        }
        for (String name : p.stringPropertyNames()) {
            values.put(name, p.getProperty(name).trim());
        }
        return values;
    }
}
