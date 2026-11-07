package pos.ui;

import java.util.prefs.Preferences;

/**
 * This terminal's POS settings (menu > POS Settings). Per-computer, kept outside the database like
 * the printer choice (shared.PrinterSettings), so each till can be set up on its own and a restore
 * never changes them.
 */
final class PosSettings {

    private static final String AUTO_PRINT = "autoPrintReceipt";

    private PosSettings() { }

    /**
     * True = after payment the receipt is printed straight away, with no preview. Only for new
     * sales; a reprint (F6) always shows the preview first. Off by default.
     */
    static boolean autoPrintReceipt() {
        try {
            return node().getBoolean(AUTO_PRINT, false);
        } catch (RuntimeException e) {
            System.err.println("[POS SETTINGS] " + e);
            return false;   // can't read: fall back to the preview
        }
    }

    /** @throws RuntimeException the setting could not be saved */
    static void setAutoPrintReceipt(boolean on) {
        node().putBoolean(AUTO_PRINT, on);
    }

    private static Preferences node() {
        return Preferences.userRoot().node("nickelsales/pos");
    }
}
