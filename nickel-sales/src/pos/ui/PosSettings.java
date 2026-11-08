package pos.ui;

import app.install.AppSettings;

import java.util.Optional;
import java.util.prefs.Preferences;

/**
 * This terminal's POS settings (menu > POS Settings), kept in nickelsales_appconfig.AppSettings with
 * this computer's other settings (its terminal ID, the API key). So they belong to the computer, not to
 * whoever is signed in to Windows, and a restore of the store's database never changes them (backups
 * cover NickelSales_Database only).
 *
 * <p>Older versions kept auto-print in the Windows registry; the first time it is read here and is not
 * in AppSettings yet, that old value is copied over, so each till keeps the choice it had.
 */
final class PosSettings {

    /** Where older versions kept it (Java Preferences = the Windows registry, per Windows user). */
    private static final String OLD_REGISTRY_NODE = "nickelsales/pos";
    private static final String OLD_REGISTRY_KEY = "autoPrintReceipt";

    private static final AppSettings SETTINGS = new AppSettings();

    private PosSettings() { }

    /**
     * True = the receipt is printed straight away, with no preview: after payment, F6 on the sale just
     * paid, and a refund's invoice and RETURN slip. Off by default, and off if the setting can't be read.
     */
    static boolean autoPrintReceipt() {
        try {
            Optional<String> saved = SETTINGS.get(AppSettings.AUTO_PRINT_RECEIPT);
            if (saved.isPresent()) {
                return Boolean.parseBoolean(saved.get());
            }
            boolean old = oldRegistryValue();   // carried over once from where older versions kept it
            SETTINGS.set(AppSettings.AUTO_PRINT_RECEIPT, Boolean.toString(old));
            return old;
        } catch (RuntimeException e) {
            System.err.println("[POS SETTINGS] " + e + (e.getCause() == null ? "" : " / " + e.getCause()));
            return false;   // can't read: fall back to the preview
        }
    }

    /** @throws RuntimeException the setting could not be saved (e.g. MySQL is not running) */
    static void setAutoPrintReceipt(boolean on) {
        SETTINGS.set(AppSettings.AUTO_PRINT_RECEIPT, Boolean.toString(on));
    }

    private static boolean oldRegistryValue() {
        try {
            return Preferences.userRoot().node(OLD_REGISTRY_NODE).getBoolean(OLD_REGISTRY_KEY, false);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
