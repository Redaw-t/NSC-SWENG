package shared;

/**
 * The application's version, the ONE place it is defined. The window title and the
 * start-up log show it, and installer/build-installer.ps1 reads it from this file for
 * the packaged app and the installer (which uses it to tell an update from a new install).
 *
 * Three numbers, MAJOR.MINOR.PATCH (Windows installers accept nothing else).
 * Raise it for every build given to a store.
 */
public final class AppVersion {

    public static final String VERSION = "0.1.3";

    private AppVersion() { }
}
