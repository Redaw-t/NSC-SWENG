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
 * | GET    | /api/ping                | no   | {"status":"ok","terminalId":"T01","computerName":"MAIN-PC"} |
 * | POST   | /api/terminals/register  | yes  | body {"ipAddress":"192.168.1.21","computerName":"..."} |
 * |        |                          |      | reply {"terminalId":"T02"}                            |
 * | GET    | /api/sync/snapshot       | yes  | the whole store database (gzip of DatabaseDump text), |
 * |        |                          |      | for a new Additional POS's first sync                 |
 * | POST   | /api/sync/push           | yes  | an Additional POS's queued changes (see SyncHub)      |
 * | GET    | /api/sync/pull?after=N   | yes  | every terminal's changes after place N, in order;     |
 * |        |   [&wait=ms]             |      | with wait, held until something new arrives           |
 * | GET    | /api/sync/next-id?kind=K | yes  | a new product / user / category / supplier ID         |
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
    /** The most a batch of changes may be (a batch is at most a few hundred changes). */
    private static final int MAX_SYNC_BODY = 32 * 1024 * 1024;
    /** The most changes one pull returns. */
    private static final int MAX_PULL = 500;
    /** The longest a "wait for news" pull is held open. */
    private static final long MAX_WAIT_MILLIS = 25_000;
    private static final Pattern TERMINAL = Pattern.compile("^T\\d{2,3}$");
    /** Header of the copy for a new Additional POS: where in the sync list the copy starts. */
    public static final String SYNC_SEQ_HEADER = "X-Sync-Seq";

    private final HttpServer server;
    private final ExecutorService threads;

    private ApiServer(HttpServer server, ExecutorService threads) {
        this.server = server;
        this.threads = threads;
    }

    /** The port from NSC_API_PORT (settings file or environment), else {@link #DEFAULT_PORT}. */
    public static int port() {
        String value = shared.Settings.get("NSC_API_PORT");
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
        return start(port, terminalId, registrar, snapshots, null);
    }

    /**
     * As above, also answering the sync calls (/api/sync/push, pull, next-id) with {@code hub}
     * (null = they say sync is not available here). With a hub, the copy for a new Additional POS
     * also says where in the hub's list it starts (header X-Sync-Seq).
     */
    public static ApiServer start(int port, String terminalId, TerminalRegistrar registrar, SnapshotSource snapshots,
                                  SyncHub hub) throws IOException {
        Objects.requireNonNull(registrar, "registrar");
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        // As many threads as calls: every Additional POS keeps one "wait for news" call open, and its
        // pings and sends must not queue behind those.
        ExecutorService threads = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "api-server");
            t.setDaemon(true);   // never keeps the app from closing
            return t;
        });
        server.setExecutor(threads);
        server.createContext("/api/ping", ex -> handle(ex, "GET", false,
                () -> reply(ex, 200, Json.object("status", "ok", "terminalId", terminalId,
                        "computerName", shared.ComputerName.get()))));
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
            if (hub != null) {
                try {
                    // Read BEFORE the copy is written: changes after this place are pulled afterwards
                    // (one already in the copy is skipped when it comes again).
                    ex.getResponseHeaders().set(SYNC_SEQ_HEADER, Long.toString(hub.appliedSeq()));
                } catch (Exception e) {
                    throw new IOException("Could not read the sync position", e);
                }
            }
            ex.getResponseHeaders().set("Content-Type", "application/gzip");
            ex.sendResponseHeaders(200, 0);   // length unknown: sent as it is written
            // A failure part-way just cuts the copy short; the Additional POS sees it is incomplete.
            try (java.util.zip.GZIPOutputStream zip = new java.util.zip.GZIPOutputStream(ex.getResponseBody())) {
                snapshots.writeTo(zip);
            }
            System.out.println("[API] the store's data was sent to " + ex.getRemoteAddress());
        }));
        server.createContext("/api/sync/push", ex -> handle(ex, "POST", true, () -> {
            if (hub == null) {
                reply(ex, 503, Json.object("error", "Sync is not running on this Main POS."));
                return;
            }
            Map<String, Object> body;
            try {
                body = shared.Json.parseObject(readBody(ex, MAX_SYNC_BODY));
            } catch (IllegalArgumentException e) {
                reply(ex, 400, Json.object("error", "The changes are not readable JSON."));
                return;
            }
            String source = shared.Json.text(body, "terminal");
            if (source == null || !TERMINAL.matcher(source).matches()) {
                reply(ex, 400, Json.object("error", "Say which terminal is sending (e.g. T02)."));
                return;
            }
            java.util.List<SyncHub.Sent> changes = new java.util.ArrayList<>();
            for (Object item : shared.Json.list(body, "changes")) {
                if (!(item instanceof Map<?, ?> raw)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> ch = (Map<String, Object>) raw;
                String id = shared.Json.text(ch, "id");
                if (id == null || id.isBlank() || !(ch.get("payload") instanceof Map)) {
                    reply(ex, 400, Json.object("error", "A change came without its ID or rows."));
                    return;
                }
                changes.add(new SyncHub.Sent(id, shared.Json.text(ch, "kind"), shared.Json.text(ch, "record"),
                        shared.Json.text(ch, "at"), shared.Json.write(ch.get("payload"))));
            }
            int taken;
            try {
                taken = hub.accept(source, ex.getRemoteAddress().getAddress().getHostAddress(), changes);
            } catch (Exception e) {
                throw new IOException("Could not take the changes", e);
            }
            reply(ex, 200, "{\"accepted\":" + taken + "}");
        }));
        server.createContext("/api/sync/pull", ex -> handle(ex, "GET", true, () -> {
            if (hub == null) {
                reply(ex, 503, Json.object("error", "Sync is not running on this Main POS."));
                return;
            }
            Map<String, String> query = query(ex);
            long after = number(query.get("after"), 0);
            int limit = (int) Math.max(0, Math.min(MAX_PULL, number(query.get("limit"), MAX_PULL)));
            long wait = Math.max(0, Math.min(MAX_WAIT_MILLIS, number(query.get("wait"), 0)));
            SyncHub.Page page;
            try {
                page = hub.pull(after, limit, wait);   // with a wait: answered as soon as something new arrives
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Stopped while waiting for changes", e);
            } catch (Exception e) {
                throw new IOException("Could not read the list of changes", e);
            }
            java.util.List<Object> list = new java.util.ArrayList<>();
            for (SyncHub.Listed c : page.changes()) {
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("seq", c.seq());
                item.put("id", c.changeId());
                item.put("source", c.source());
                item.put("kind", c.kind());
                item.put("record", c.recordId());
                item.put("at", c.createdAt());
                item.put("payload", new shared.Json.Raw(c.payload()));   // stored as JSON already
                list.add(item);
            }
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("head", page.head());
            out.put("changes", list);
            reply(ex, 200, shared.Json.write(out));
        }));
        server.createContext("/api/sync/next-id", ex -> handle(ex, "GET", true, () -> {
            if (hub == null) {
                reply(ex, 503, Json.object("error", "Sync is not running on this Main POS."));
                return;
            }
            String kind = query(ex).getOrDefault("kind", "");
            if (!java.util.Set.of(shared.MasterIds.PRODUCT, shared.MasterIds.USER, shared.MasterIds.CATEGORY,
                    shared.MasterIds.SUPPLIER).contains(kind)) {
                reply(ex, 400, Json.object("error", "Ask for kind PRODUCT, USER, CATEGORY or SUPPLIER."));
                return;
            }
            String id;
            try {
                id = hub.nextId(kind);
            } catch (Exception e) {
                throw new IOException("Could not make a new ID", e);
            }
            reply(ex, 200, Json.object("id", id));
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
        return readBody(ex, MAX_BODY);
    }

    private static String readBody(HttpExchange ex, int max) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readNBytes(max);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /** The query string's fields (?after=12&limit=200), decoded. */
    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> out = new java.util.HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static long number(String text, long fallback) {
        try {
            return text == null ? fallback : Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
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
