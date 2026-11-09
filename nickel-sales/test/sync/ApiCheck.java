package sync;

import shared.ApiKey;
import sync.api.ApiServer;
import sync.api.MainPosClient;
import sync.api.TerminalRegistrar;

import java.net.ServerSocket;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SELF-TEST for the terminals' API (no database, no screen): a real ApiServer on a free port, called
 * by the real MainPosClient, with an in-memory TerminalRegistrar in place of the Main POS's table.
 *
 * Run after a build, from nickel-sales/:
 *   java -cp "out;lib\jbcrypt-0.4.jar;lib\mysql-connector-j-9.4.0.jar" sync.ApiCheck
 * Exit code 1 on failure. It needs the developer key: set NSC_TEST_API_KEY to it (else the checks
 * that need a valid key are skipped).
 */
public class ApiCheck {

    static int passed = 0;
    static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=================================================================");
        System.out.println(" NICKEL SALES CENTER - terminal API checks");
        System.out.println("=================================================================");
        String key = System.getenv("NSC_TEST_API_KEY");
        boolean haveKey = key != null && ApiKey.isValid(key);

        // In-memory stand-in for PosTerminalRepository.register: same computer = same ID.
        Map<String, String> byComputer = new LinkedHashMap<>();
        TerminalRegistrar registrar = (ip, name) -> byComputer.computeIfAbsent(name == null ? ip : name,
                k -> String.format("T%02d", byComputer.size() + 2));

        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        ApiServer server = ApiServer.start(port, "T01", registrar);
        try {
            check("API key: wrong or empty keys are refused", () -> {
                expect(!ApiKey.isValid(null) && !ApiKey.isValid("") && !ApiKey.isValid("NSC-AAAAA-AAAAA-AAAAA-AAAAA"),
                        "bad keys rejected");
            });

            if (haveKey) {
                check("API key: the developer's key is accepted, ignoring spaces and letter case", () -> {
                    expect(ApiKey.isValid(key) && ApiKey.isValid("  " + key.toLowerCase() + " "), "key accepted");
                });
                check("Register: first Additional POS gets T02, the next T03, asking again keeps the same ID", () -> {
                    MainPosClient client = new MainPosClient("127.0.0.1", port, key);
                    expectText(client.requestTerminalId("192.168.1.21", "TILL-2"), "T02");
                    expectText(client.requestTerminalId("192.168.1.22", "TILL-3"), "T03");
                    expectText(client.requestTerminalId("192.168.1.99", "TILL-2"), "T02");   // same computer, new IP
                });
                check("Register: a bad IP address is refused with a message", () -> {
                    expectMessage(() -> new MainPosClient("127.0.0.1", port, key).requestTerminalId("not-an-ip", "X"),
                            "Send ipAddress as four numbers, e.g. 192.168.1.21.");
                });
            } else {
                System.out.println(" [SKIP] checks that need the developer key: set NSC_TEST_API_KEY");
            }

            check("Register: a wrong API key gets 401 and nothing is registered", () -> {
                int before = byComputer.size();
                expectMessage(() -> new MainPosClient("127.0.0.1", port, "NSC-WRONG-KEY")
                                .requestTerminalId("192.168.1.30", "INTRUDER"),
                        "The Main POS did not accept the API key. Check that both computers were set up with the "
                                + "key the developer provided.");
                expect(byComputer.size() == before && !byComputer.containsKey("INTRUDER"), "nothing registered");
            });

            check("Register: no Main POS listening gives a clear message", () -> {
                int closed;
                try (ServerSocket s = new ServerSocket(0)) {
                    closed = s.getLocalPort();
                }
                try {
                    new MainPosClient("127.0.0.1", closed, "x").requestTerminalId("192.168.1.40", "Y");
                    throw new AssertionError("expected an error");
                } catch (MainPosClient.MainPosException e) {
                    expect(e.getMessage().startsWith("Can't reach the Main POS at 127.0.0.1"), e.getMessage());
                }
            });
        } finally {
            server.stop();
        }

        System.out.println("-----------------------------------------------------------------");
        System.out.println(" RESULT: " + passed + " passed, " + failed + " failed");
        System.out.println("=================================================================");
        if (failed > 0) {
            System.exit(1);
        }
    }

    interface Body {
        void run() throws Exception;
    }

    static void check(String name, Body body) {
        try {
            body.run();
            passed++;
            System.out.println(" [PASS] " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println(" [FAIL] " + name + "  ->  " + t);
        }
    }

    static void expect(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    static void expectText(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }

    static void expectMessage(Body body, String message) {
        try {
            body.run();
        } catch (Exception e) {
            expectText(e.getMessage(), message);
            return;
        }
        throw new AssertionError("expected: " + message);
    }
}
