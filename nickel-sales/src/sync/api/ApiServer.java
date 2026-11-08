package sync.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import shared.ApiKey;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * The Main POS's API, for the other terminals on the store network (HTTP + JSON, JDK only).
 *
 * | Method | Path                     | Key? | Does                                                  |
 * | GET    | /api/ping                | no   | {"status":"ok","terminalId":"T01"}: the Main POS is up |
 * | POST   | /api/terminals/register  | yes  | body {"ipAddress":"192.168.1.21","computerName":"..."} |
 * |        |                          |      | reply {"terminalId":"T02"}                            |
 * | GET    | /api/sync/snapshot       | yes  | the whole store database (gzip of DatabaseDump text), |
 * |        |                          |      | for a new Additional POS's first sync                 |
 *
 * Calls that need the key must send the developer's key in the X-API-Key header ({@link ApiKey});
 * without it they get 401. Runs on background threads; the app can close while it is running.
 */
public final class ApiServer {

    /** The port the terminals talk on; NSC_API_PORT overrides it (the same on every terminal). */
    public static final int DEFAULT_PORT = 8765;

    private static final Pattern IPV4 = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
    private static final int MAX_BODY = 4096;

    private final HttpServer server;
    private final ExecutorService threads;

    private ApiServer(HttpServer server, ExecutorService threads) {
        this.server = server;
        this.threads = threads;
    }

    /** The port from NSC_API_PORT, else {@link #DEFAULT_PORT}. */
    public static int port() {
        String value = System.getenv("NSC_API_PORT");
        try {
            int p = value == null || value.isBlank() ? DEFAULT_PORT : Integer.parseInt(value.trim());
            return p > 0 && p < 65536 ? p : DEFAULT_PORT;
        } catch (NumberFormatException e) {
            return DEFAULT_PORT;
        }
    }

    /**
     * Starts listening on {@code port} on every network address of this computer.
     *
     * @param terminalId this terminal's ID, reported by /api/ping
     * @throws IOException the port is taken (another copy of the app, or another program)
     */
    public static ApiServer start(int port, String terminalId, TerminalRegistrar registrar) throws IOException {
        return start(port, terminalId, registrar, null);
    }

    /**
     * As above, also answering /api/sync/snapshot with {@code snapshots} (null = that call says it is
     * not available).
     */
    public static ApiServer start(int port, String terminalId, TerminalRegistrar registrar, SnapshotSource snapshots)
            throws IOException {
        Objects.requireNonNull(registrar, "registrar");
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        ExecutorService threads = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "api-server");
            t.setDaemon(true);   // never keeps the app from closing
            return t;
        });
        server.setExecutor(threads);
        server.createContext("/api/ping", ex -> handle(ex, "GET", false,
                () -> reply(ex, 200, Json.object("status", "ok", "terminalId", terminalId))));
        server.createContext("/api/terminals/register", ex -> handle(ex, "POST", true, () -> {
            Map<String, String> body = Json.fields(readBody(ex));
            String ip = body.getOrDefault("ipAddress", "").trim();
            String name = body.get("computerName");
            if (!IPV4.matcher(ip).matches()) {
                reply(ex, 400, Json.object("error", "Send ipAddress as four numbers, e.g. 192.168.1.21."));
                return;
            }
            String assigned = registrar.register(ip, name == null || name.isBlank() ? null : name.trim());
            System.out.println("[API] " + ip + (name == null ? "" : " (" + name + ")") + " registered as " + assigned);
            reply(ex, 200, Json.object("terminalId", assigned));
        }));
        server.createContext("/api/sync/snapshot", ex -> handle(ex, "GET", true, () -> {
            if (snapshots == null) {
                reply(ex, 503, Json.object("error", "This Main POS can't send its data."));
                return;
            }
            System.out.println("[API] sending the store's data to " + ex.getRemoteAddress());
            ex.getResponseHeaders().set("Content-Type", "application/gzip");
            ex.sendResponseHeaders(200, 0);   // length unknown: sent as it is written
            // A failure part-way just cuts the copy short; the Additional POS sees it is incomplete.
            try (java.util.zip.GZIPOutputStream zip = new java.util.zip.GZIPOutputStream(ex.getResponseBody())) {
                snapshots.writeTo(zip);
            }
            System.out.println("[API] the store's data was sent to " + ex.getRemoteAddress());
        }));
        server.start();
        System.out.println("[API] Main POS API listening on port " + port);
        return new ApiServer(server, threads);
    }

    public void stop() {
        server.stop(0);
        threads.shutdownNow();
    }

    /* ------------------------------- helpers ------------------------------- */

    private interface Action {
        void run() throws IOException;
    }

    /** Checks the method and (if needed) the key, runs the action, and turns failures into JSON errors. */
    private static void handle(HttpExchange ex, String method, boolean needsKey, Action action) throws IOException {
        try (ex) {
            if (!method.equalsIgnoreCase(ex.getRequestMethod())) {
                reply(ex, 405, Json.object("error", "Use " + method + "."));
                return;
            }
            if (needsKey && !ApiKey.isValid(ex.getRequestHeaders().getFirst(ApiKey.HEADER))) {
                System.out.println("[API] refused " + ex.getRequestURI() + " from " + ex.getRemoteAddress() + ": bad API key");
                reply(ex, 401, Json.object("error", "The API key is missing or wrong."));
                return;
            }
            action.run();
        } catch (RuntimeException e) {
            System.err.println("[API] " + ex.getRequestURI() + " failed: " + e + (e.getCause() == null ? "" : " / " + e.getCause()));
            try {
                reply(ex, 500, Json.object("error", "The Main POS could not do that. Check its database, then try again."));
            } catch (IOException ignored) {
                // the caller has gone
            }
        }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static void reply(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
