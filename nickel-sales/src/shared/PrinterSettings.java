package shared;

import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.prefs.Preferences;

/**
 * Which printer this computer uses for receipts and for barcode labels (Settings > Printers).
 * A per-computer setting, kept outside the database like the backup folder, so each terminal can
 * have its own printers and a restore never changes them.
 *
 * <p>Which printer is used, in order: the printer chosen in Settings; else the printer named in the
 * NSC_PRINTER_NAME environment variable; else the Windows default printer.
 */
public final class PrinterSettings {

    /** What a printer is used for. */
    public enum Use {
        RECEIPT("receiptPrinter", "receipt"),
        LABEL("labelPrinter", "barcode label");

        private final String key;
        private final String noun;

        Use(String key, String noun) {
            this.key = key;
            this.noun = noun;
        }
    }

    /** Why no printer could be found; the message is ready to show to the user. */
    public static final class NoPrinterException extends Exception {
        public NoPrinterException(String message) {
            super(message);
        }
    }

    private PrinterSettings() { }

    /** The printer chosen in Settings for this use, or null for "use the default". */
    public static String chosen(Use use) {
        String name = node().get(use.key, null);
        return name == null || name.isBlank() ? null : name;
    }

    /** Saves the choice; null or blank = go back to the default printer. */
    public static void save(Use use, String printerName) {
        if (printerName == null || printerName.isBlank()) {
            node().remove(use.key);
        } else {
            node().put(use.key, printerName.trim());
        }
    }

    /** Names of the printers installed on this computer, A-Z. */
    public static List<String> installed() {
        List<String> names = new ArrayList<>();
        for (PrintService s : PrintServiceLookup.lookupPrintServices(null, null)) {
            names.add(s.getName());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** The Windows default printer's name, or null if there is none. */
    public static String systemDefault() {
        PrintService def = PrintServiceLookup.lookupDefaultPrintService();
        return def == null ? null : def.getName();
    }

    /** The printer to print to for this use (see the class comment for the order). */
    public static PrintService find(Use use) throws NoPrinterException {
        String chosen = chosen(use);
        if (chosen != null) {
            PrintService s = byName(chosen);
            if (s == null) {
                throw new NoPrinterException("The " + use.noun + " printer \"" + chosen + "\" chosen in Settings > "
                        + "Printers is not on this computer. Choose another printer in Settings.");
            }
            return s;
        }
        String wanted = System.getenv("NSC_PRINTER_NAME");
        if (wanted != null && !wanted.isBlank()) {
            PrintService s = byName(wanted.trim());
            if (s == null) {
                throw new NoPrinterException("There is no printer named \"" + wanted.trim() + "\" on this computer.");
            }
            return s;
        }
        PrintService def = PrintServiceLookup.lookupDefaultPrintService();
        if (def == null) {
            throw new NoPrinterException("No printer is set up on this computer. Choose one in Settings > Printers, "
                    + "or set a default printer in Windows.");
        }
        return def;
    }

    /** Where the printer for this use comes from, for messages: "chosen in Settings", etc. */
    public static String source(Use use) {
        if (chosen(use) != null) {
            return "chosen in Settings";
        }
        String wanted = System.getenv("NSC_PRINTER_NAME");
        return wanted != null && !wanted.isBlank() ? "from NSC_PRINTER_NAME" : "Windows default printer";
    }

    private static PrintService byName(String name) {
        String wanted = name.toLowerCase(Locale.ROOT);
        for (PrintService s : PrintServiceLookup.lookupPrintServices(null, null)) {
            if (s.getName().toLowerCase(Locale.ROOT).equals(wanted)) {
                return s;
            }
        }
        return null;
    }

    private static Preferences node() {
        return Preferences.userRoot().node("nickelsales/printers");
    }
}
