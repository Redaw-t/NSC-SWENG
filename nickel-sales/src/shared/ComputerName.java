package shared;

import java.io.IOException;
import java.net.InetAddress;

/** This PC's Windows computer name (e.g. "MAIN-POS"), the same everywhere the app shows or saves it. */
public final class ComputerName {

    private ComputerName() { }

    /** The computer name, or null if it can't be found. */
    public static String get() {
        String name = System.getenv("COMPUTERNAME");
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return null;
        }
    }
}
