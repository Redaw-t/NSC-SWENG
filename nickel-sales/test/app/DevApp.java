package app;

/**
 * DEVELOPMENT launcher: what double-clicking run-demo.bat starts (and the IDE's "Nickel Sales
 * (DevApp)" run configuration). It starts the real app through {@link Main#start}: the same wiring
 * (real MySQL users, products, inventory and sales), and the same terminal ID choice. Unlike the
 * installed app it reads environment variables, not the store's settings file.
 *
 * <p>That matters: the terminal ID Installation Setup saved (nickelsales_appconfig, e.g. T02 on an
 * Additional POS) must win over NSC_TERMINAL_ID, and right after a first-time setup the app must be
 * rebuilt for the ID it was given. An earlier version built its own config from NSC_TERMINAL_ID only,
 * so a second PC sold with T01 invoice numbers and showed T01 in the title.
 */
public final class DevApp {

    private DevApp() { }

    public static void main(String[] args) {
        // Not the installed app: settings come from environment variables (run-demo / setup.bat),
        // never the store settings file, and no log file or single-copy check.
        Main.start("[DEV]");
    }
}
