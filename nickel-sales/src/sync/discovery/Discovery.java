package sync.discovery;

import sync.api.MainPosClient;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Finding the Main POS on the store network without typing its IP address, e.g. after the router gave
 * it a new one, or in Installation Setup.
 *
 * The Main POS answers on UDP port {@link #PORT} ({@link Responder}). Another PC sends one broadcast
 * ("is there a Nickel Sales Main POS?") to every network it is on and collects the answers for a
 * moment ({@link #find}). An answer is only trusted after the API check: the address must answer as
 * T01 and accept this store's API key ({@link MainPosClient#verifyMainPos}).
 *
 * Works when the PCs are on the same network (one router, the usual small store). Windows Firewall
 * must allow UDP {@link #PORT} (the installer adds the rule); where broadcasts are blocked, the IP
 * address can still be typed in.
 */
public final class Discovery {

    /** The UDP port the Main POS answers on (next to the API's TCP 8765). */
    public static final int PORT = 8766;

    static final String QUESTION = "NICKELSALES-DISCOVER 1";
    static final String ANSWER = "NICKELSALES-MAIN 1 ";

    private Discovery() { }

    /* ------------------------------- Main POS: answering ------------------------------- */

    /** The Main POS's side: answers every question on {@link #PORT} while it runs. */
    public static final class Responder {

        private final String terminalId;
        private final int apiPort;
        private DatagramSocket socket;
        private Thread thread;

        public Responder(String terminalId, int apiPort) {
            this.terminalId = terminalId;
            this.apiPort = apiPort;
        }

        /** @throws SocketException the port is taken (the app is open twice, or another program) */
        public synchronized void start() throws SocketException {
            if (socket != null) {
                return;
            }
            DatagramSocket s = new DatagramSocket(null);
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(PORT));
            socket = s;
            thread = new Thread(this::answer, "discovery");
            thread.setDaemon(true);
            thread.start();
            System.out.println("[DISCOVERY] answering on UDP port " + PORT);
        }

        private void answer() {
            byte[] buffer = new byte[256];
            byte[] reply = (ANSWER + terminalId + " " + apiPort).getBytes(StandardCharsets.US_ASCII);
            DatagramSocket s = socket;
            while (s != null && !s.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    s.receive(packet);
                    String text = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.US_ASCII).trim();
                    if (QUESTION.equals(text)) {
                        s.send(new DatagramPacket(reply, reply.length, packet.getSocketAddress()));
                    }
                } catch (IOException e) {
                    if (s.isClosed()) {
                        return;
                    }
                    System.err.println("[DISCOVERY] " + e.getMessage());
                }
            }
        }

        public synchronized void stop() {
            if (socket != null) {
                socket.close();
                socket = null;
            }
        }
    }

    /* ------------------------------- another PC: asking ------------------------------- */

    /**
     * The Main POS's address on this network, checked with the API ({@link MainPosClient#verifyMainPos});
     * empty if none answered within {@code wait} or none passed the check.
     */
    public static Optional<String> find(int apiPort, String apiKey, Duration wait) {
        for (String ip : candidates(wait)) {
            try {
                new MainPosClient(ip, apiPort, apiKey).verifyMainPos();
                return Optional.of(ip);
            } catch (MainPosClient.MainPosException e) {
                System.out.println("[DISCOVERY] " + ip + " answered but is not this store's Main POS: " + e.getMessage());
            }
        }
        return Optional.empty();
    }

    /** Every address that answered the broadcast as a Main POS within {@code wait}, first answer first. */
    public static List<String> candidates(Duration wait) {
        Set<String> found = new LinkedHashSet<>();
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            byte[] question = QUESTION.getBytes(StandardCharsets.US_ASCII);
            for (InetAddress target : broadcastAddresses()) {
                try {
                    s.send(new DatagramPacket(question, question.length, target, PORT));
                } catch (IOException e) {
                    // that network can't be reached: try the others
                }
            }
            long end = System.nanoTime() + wait.toNanos();
            byte[] buffer = new byte[256];
            while (true) {
                long left = (end - System.nanoTime()) / 1_000_000;
                if (left <= 0) {
                    break;
                }
                s.setSoTimeout((int) Math.max(1, left));
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    s.receive(packet);
                } catch (SocketTimeoutException e) {
                    break;
                }
                String text = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.US_ASCII).trim();
                if (text.startsWith(ANSWER + "T01")) {
                    found.add(packet.getAddress().getHostAddress());
                }
            }
        } catch (IOException e) {
            System.err.println("[DISCOVERY] could not ask the network: " + e.getMessage());
        }
        return new ArrayList<>(found);
    }

    /** 255.255.255.255 and the broadcast address of every network this PC is on. */
    private static List<InetAddress> broadcastAddresses() {
        Set<InetAddress> out = new LinkedHashSet<>();
        try {
            out.add(InetAddress.getByName("255.255.255.255"));
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress address : nic.getInterfaceAddresses()) {
                    if (address.getBroadcast() != null) {
                        out.add(address.getBroadcast());
                    }
                }
            }
        } catch (IOException e) {
            // fall back to the general broadcast address only
        }
        return new ArrayList<>(out);
    }
}
