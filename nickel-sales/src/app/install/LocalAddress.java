package app.install;

import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.regex.Pattern;

/** This computer's IPv4 address on the network, and checking a typed one. */
public final class LocalAddress {

    private static final Pattern IPV4 = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");

    private LocalAddress() { }

    /**
     * The address other computers on the store network would reach this one at, e.g. 192.168.1.20;
     * null if none is found (no network). Nothing is sent over the network to find it.
     */
    public static String detect() {
        // The address Windows would use to go out to the network (connect() on UDP sends nothing).
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(new InetSocketAddress("8.8.8.8", 53));
            InetAddress a = socket.getLocalAddress();
            if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isAnyLocalAddress()) {
                return a.getHostAddress();
            }
        } catch (Exception ignored) {
            // no route out: look at the network adapters instead
        }
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                for (InetAddress a : Collections.list(nic.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    /** True for a dotted IPv4 address such as 192.168.1.20 (no host names). */
    public static boolean isValid(String text) {
        return text != null && IPV4.matcher(text.trim()).matches();
    }
}
