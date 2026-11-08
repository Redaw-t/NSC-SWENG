package sync.api;

import shared.ApiKey;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Calls the Main POS's API ({@link ApiServer}) from an Additional POS. Every problem comes out as a
 * {@link MainPosException} whose message is ready to show to the person setting the terminal up.
 */
public final class MainPosClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(6);

    /** Something went wrong talking to the Main POS; the message says what to check. */
    public static final class MainPosException extends Exception {
        public MainPosException(String message) {
            super(message);
        }
    }

    private final String mainIp;
    private final int port;
    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    /**
     * @param mainIp the Main POS's IP address, e.g. 192.168.1.20
     * @param apiKey the developer's key, sent in X-API-Key
     */
    public MainPosClient(String mainIp, int port, String apiKey) {
        this.mainIp = mainIp.trim();
        this.port = port;
        this.apiKey = apiKey;
    }

    /** Asks the Main POS for this computer's terminal ID (T02, T03 ...). */
    public String requestTerminalId(String myIp, String computerName) throws MainPosException {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/terminals/register"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header(ApiKey.HEADER, apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(Json.object("ipAddress", myIp, "computerName", computerName),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(request);
        Map<String, String> body = Json.fields(response.body());
        switch (response.statusCode()) {
            case 200 -> {
                String id = body.get("terminalId");
                if (id == null || id.isBlank()) {
                    throw new MainPosException("The Main POS answered, but did not give a terminal ID. Try again.");
                }
                return id.trim();
            }
            case 401 -> throw new MainPosException("The Main POS did not accept the API key. Check that both "
                    + "computers were set up with the key the developer provided.");
            case 404 -> throw new MainPosException("Something at " + mainIp + " answered, but it is not a Nickel Sales "
                    + "Main POS. Check the IP address.");
            default -> throw new MainPosException(body.getOrDefault("error",
                    "The Main POS could not give a terminal ID (error " + response.statusCode() + "). Try again."));
        }
    }

    private URI uri(String path) throws MainPosException {
        try {
            return URI.create("http://" + mainIp + ":" + port + path);
        } catch (IllegalArgumentException e) {
            throw new MainPosException("That is not a valid IP address.");
        }
    }

    private HttpResponse<String> send(HttpRequest request) throws MainPosException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpConnectTimeoutException | ConnectException e) {
            throw new MainPosException("Can't reach the Main POS at " + mainIp + ". Check that it is turned on with "
                    + "the Nickel Sales app open, that both computers are on the same network, and that Windows "
                    + "Firewall on the Main POS allows the app.");
        } catch (HttpTimeoutException e) {
            throw new MainPosException("The Main POS at " + mainIp + " took too long to answer. Try again.");
        } catch (IOException e) {
            throw new MainPosException("Can't talk to the Main POS at " + mainIp + " (" + e.getMessage() + "). Try again.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MainPosException("Stopped before the Main POS answered.");
        }
    }
}
