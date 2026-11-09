package sync.discovery;

import sync.api.MainPosClient;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Finds this store's Main POS on the network without typing its IP address, e.g. after the router gave
 * it a new one, or in Installation Setup.
 *
 * It knocks on the sync port (TCP {@code apiPort}, 8765) of every address of this PC's own network
 * (e.g. 192.168.1.1 to .254), many at once, which takes a second or two. Devices that don't run Nickel
 * Sales (phones, printers, laptops) refuse or ignore the knock: nothing is sent to them, and nothing
 * happens on them. An address that accepts is asked {@code /api/ping}; only one that answers as the Main
 * POS (T01) is then checked with this store's API key ({@link MainPosClient#verifyMainPos}), so the key
 * is never sent anywhere else.
 *
 * It needs no port or firewall rule of its own: if the PCs can sync, the scan can find the Main POS. It
 * can't find a Main POS on another network (another router), where sync can't reach it either.
 */
public final class MainPosScanner {

    /** How long to wait for one address to accept: devices on a store network answer well within it. */
    static final int KNOCK_MILLIS = 300;
    /** Addresses knocked on at the same time (a /24 in about two rounds). */
    static final int AT_ONCE = 128;
    /**
     * Network adapters that are not the store network (virtual machines, VPNs, WSL, Docker ...), by
     * name: never scanned, so the scan stays quick and only knocks on the shop's own network.
     */
    static final List<String> NOT_THE_STORE = List.of("vmware", "virtualbox", "hyper-v", "vethernet", "virtual",
            "vpn", "tap", "tun", "wsl", "docker", "loopback", "bluetooth");
    /** Networks larger than this (prefix below /24) are scanned only in this PC's own /24. */
    static final int MAX_PREFIX = 24;

    private MainPosScanner() { }

    /**
     * This store's Main POS on this PC's network(s), checked with {@code apiKey}; empty if none answered
     * within {@code limit}.
     */
    public static Optional<String> find(int apiPort, String apiKey, Duration limit) {
        return find(apiPort, apiKey, limit, null);
    }

    /**
     * As above, trying {@code lastKnown} (the Main POS's saved address, may be null) and its network
     * first.
     */
    public static Optional<String> find(int apiPort, String apiKey, Duration limit, String lastKnown) {
        List<String> addresses = inOrder(addressesToScan(), lastKnown);
        if (addresses.isEmpty() || apiKey == null) {
            return Optional.empty();
        }
        long end = System.nanoTime() + limit.toNanos();
        ExecutorService pool = Executors.newFixedThreadPool(AT_ONCE, r -> {
            Thread t = new Thread(r, "find-main-pos");
            t.setDaemon(true);
            return t;
        });
        try {
            CompletionService<String> done = new ExecutorCompletionService<>(pool);
            List<Future<String>> tasks = new ArrayList<>();
            for (String ip : addresses) {
                tasks.add(done.submit(() -> isMainPos(ip, apiPort, apiKey) ? ip : null));
            }
            for (int i = 0; i < tasks.size(); i++) {
                long left = end - System.nanoTime();
                if (left <= 0) {
                    break;
                }
                Future<String> next = done.poll(left, TimeUnit.NANOSECONDS);
                if (next == null) {
                    break;
                }
                try {
                    String found = next.get();
                    if (found != null) {
                        System.out.println("[FIND] Main POS found at " + found);
                        return Optional.of(found);
                    }
                } catch (Exception e) {
                    // that address failed: keep looking
                }
            }
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            pool.shutdownNow();
        }
    }

    /** True if {@code ip} accepts on the sync port, answers as the Main POS and takes this store's key. */
    static boolean isMainPos(String ip, int apiPort, String apiKey) {
        try (Socket knock = new Socket()) {
            knock.connect(new InetSocketAddress(ip, apiPort), KNOCK_MILLIS);
        } catch (IOException e) {
            return false;   // nothing listens there: almost every address
        }
        MainPosClient client = new MainPosClient(ip, apiPort, apiKey);
        try {
            client.ping();            // answers as T01? (no key sent)
            client.verifyMainPos();   // and accepts this store's key?
            return true;
        } catch (MainPosClient.MainPosException e) {
            System.out.println("[FIND] " + ip + " is not this store's Main POS: " + e.getMessage());
            return false;
        }
    }

    /**
     * Every other address of each network this PC is on (IPv4, at most a /24 around this PC), the
     * network this PC goes out to the network through first. Virtual adapters are left out.
     */
    static List<String> addressesToScan() {
        Set<String> out = new LinkedHashSet<>();
        Set<String> mine = new LinkedHashSet<>();
        String main = outgoingAddress();
        try {
            List<NetworkInterface> nics = Collections.list(NetworkInterface.getNetworkInterfaces());
            // the adapter Windows uses to go out first: that is the store network
            nics.sort((a, b) -> Boolean.compare(!hasAddress(a, main), !hasAddress(b, main)));
            for (NetworkInterface nic : nics) {
                if (!nic.isUp() || nic.isLoopback() || nic.isPointToPoint() || isVirtual(nic)) {
                    continue;
                }
                for (InterfaceAddress a : nic.getInterfaceAddresses()) {
                    if (a.getAddress() instanceof Inet4Address v4 && v4.isSiteLocalAddress()) {
                        mine.add(v4.getHostAddress());
                        out.addAll(network(v4, a.getNetworkPrefixLength()));
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[FIND] could not read this PC's networks: " + e.getMessage());
        }
        out.removeAll(mine);
        return new ArrayList<>(out);
    }

    /** {@code addresses} with {@code first} (if it is one of them, or on one of these networks) and its /24 at the front. */
    static List<String> inOrder(List<String> addresses, String first) {
        if (first == null || first.isBlank()) {
            return addresses;
        }
        String prefix = first.substring(0, first.lastIndexOf('.') + 1);
        List<String> out = new ArrayList<>();
        out.add(first);
        for (String a : addresses) {
            if (a.startsWith(prefix) && !a.equals(first)) {
                out.add(a);
            }
        }
        for (String a : addresses) {
            if (!a.startsWith(prefix)) {
                out.add(a);
            }
        }
        return out;
    }

    /** A virtual machine / VPN / container adapter, by its name. */
    static boolean isVirtual(NetworkInterface nic) {
        String name = ((nic.getDisplayName() == null ? "" : nic.getDisplayName()) + " " + nic.getName())
                .toLowerCase(java.util.Locale.ROOT);
        for (String word : NOT_THE_STORE) {
            if (name.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAddress(NetworkInterface nic, String ip) {
        if (ip == null) {
            return false;
        }
        for (InterfaceAddress a : nic.getInterfaceAddresses()) {
            if (ip.equals(a.getAddress().getHostAddress())) {
                return true;
            }
        }
        return false;
    }

    /** The address Windows would use to go out to the network (connect() on UDP sends nothing); null if none. */
    private static String outgoingAddress() {
        try (java.net.DatagramSocket s = new java.net.DatagramSocket()) {
            s.connect(InetAddress.getByName("8.8.8.8"), 53);
            InetAddress a = s.getLocalAddress();
            return a instanceof Inet4Address && !a.isAnyLocalAddress() ? a.getHostAddress() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** The host addresses of {@code address}'s network, e.g. 192.168.1.1 to 192.168.1.254 for a /24. */
    static List<String> network(Inet4Address address, int prefix) {
        int bits = Math.max(prefix, MAX_PREFIX);
        if (bits >= 31) {
            return List.of();
        }
        byte[] b = address.getAddress();
        int ip = ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
        int mask = -1 << (32 - bits);
        int base = ip & mask;
        int size = 1 << (32 - bits);
        List<String> out = new ArrayList<>(size - 2);
        for (int i = 1; i < size - 1; i++) {   // not the network or broadcast address
            int host = base + i;
            out.add(((host >>> 24) & 0xFF) + "." + ((host >>> 16) & 0xFF) + "." + ((host >>> 8) & 0xFF) + "." + (host & 0xFF));
        }
        return out;
    }

    /** For the self-test: {@link #network} of a typed address. */
    static List<String> network(String address, int prefix) throws IOException {
        return network((Inet4Address) InetAddress.getByName(address), prefix);
    }
}
