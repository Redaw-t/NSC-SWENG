package sync.api;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Main POS: writes a full copy of the store's database (NickelSales_Database, never the app's own
 * nickelsales_appconfig) for a new Additional POS, as the text {@code system.repository.DatabaseDump}
 * writes. Wired in the app (it knows the database); the API only streams what it writes.
 */
@FunctionalInterface
public interface SnapshotSource {

    /** Writes the whole copy to {@code out} (do not close it). */
    void writeTo(OutputStream out) throws IOException;
}
