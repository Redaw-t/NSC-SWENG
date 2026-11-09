package app.install;

import shared.Database;
import system.repository.DatabaseDump;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.zip.GZIPInputStream;

/**
 * The first sync of a new Additional POS: a full copy of the Main POS's store database
 * (NickelSales_Database: users, products, stock, sales, returns, logs ...) loaded into this computer's
 * own. The app's configuration (nickelsales_appconfig: terminal IDs, IP addresses, API key, POS
 * settings) is NEVER copied: each computer keeps its own.
 *
 * <p>The copy is the text {@link DatabaseDump} writes (the same as a backup), sent gzip-compressed by
 * the Main POS's API. It is checked whole before anything is loaded, so a copy cut off on the way
 * changes nothing.
 */
public final class InitialSync {

    /** What was loaded. */
    public record Result(int tables, int statements) { }

    private InitialSync() { }

    /** Main POS: writes the whole store database to {@code out} (not closed). */
    public static void writeStoreData(OutputStream out, String terminalId) throws IOException {
        try (Connection c = Database.getConnection()) {
            Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            DatabaseDump.write(c, w, Instant.now(), terminalId);
            w.flush();
        } catch (SQLException e) {
            throw new IOException("Could not read the store's database", e);
        }
    }

    /**
     * Additional POS: checks the downloaded copy ({@code gzipFile}) is whole, then loads it into this
     * computer's store database, replacing its tables. nickelsales_appconfig is not touched.
     *
     * @throws IllegalArgumentException the copy is incomplete or not a store copy (nothing was changed)
     * @throws IOException              the file could not be read
     * @throws SQLException             loading failed part-way (try the sync again)
     */
    public static Result load(Path gzipFile) throws IOException, SQLException {
        DatabaseDump.Summary summary;
        try (BufferedReader in = reader(gzipFile)) {
            summary = DatabaseDump.validate(in);
        } catch (java.io.EOFException | java.util.zip.ZipException e) {
            throw new IllegalArgumentException("The copy of the store's data was cut off on the way.", e);
        }
        int statements;
        try (Connection c = Database.getConnection(); BufferedReader in = reader(gzipFile)) {
            statements = DatabaseDump.restore(c, in);
        }
        return new Result(summary.tables(), statements);
    }

    private static BufferedReader reader(Path gzipFile) throws IOException {
        InputStream raw = Files.newInputStream(gzipFile);
        return new BufferedReader(new InputStreamReader(new GZIPInputStream(raw, 1 << 16), StandardCharsets.UTF_8));
    }
}
