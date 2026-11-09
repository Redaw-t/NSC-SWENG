package sync.discovery;

import java.io.IOException;
import java.util.List;

/** Lets the self-test (sync.SyncCheck) reach MainPosScanner's package-private parts. */
public final class MainPosScannerAccess {

    private MainPosScannerAccess() { }

    public static List<String> network(String address, int prefix) throws IOException {
        return MainPosScanner.network(address, prefix);
    }

    public static boolean isMainPos(String ip, int apiPort, String apiKey) {
        return MainPosScanner.isMainPos(ip, apiPort, apiKey);
    }
}
