package system.service;

import java.nio.file.Path;
import java.util.prefs.Preferences;

/**
 * Remembers this terminal's backup folder between runs (a per-computer setting, so it is kept
 * outside the database: a restore must not change where backups go).
 */
public final class BackupFolderSetting {

    private static final String KEY = "backupFolder";

    private BackupFolderSetting() { }

    /** The saved folder, or Documents\NickelSales-Backups. */
    public static Path load() {
        String saved = node().get(KEY, null);
        return saved != null ? Path.of(saved)
                : Path.of(System.getProperty("user.home"), "Documents", "NickelSales-Backups");
    }

    public static void save(Path folder) {
        node().put(KEY, folder.toString());
    }

    private static Preferences node() {
        return Preferences.userRoot().node("nickelsales/system");
    }
}
