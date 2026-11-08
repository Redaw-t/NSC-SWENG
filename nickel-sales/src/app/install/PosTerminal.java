package app.install;

import java.util.Objects;

/**
 * One row of nickelsales_appconfig.PosTerminals.
 *
 * @param terminalId e.g. T01 (the same code as NSC_TERMINAL_ID and the invoice numbers)
 * @param ipAddress  the terminal's address on the store network; null = not set up yet
 */
public record PosTerminal(String terminalId, String ipAddress) {

    public PosTerminal {
        Objects.requireNonNull(terminalId, "terminalId");
        ipAddress = ipAddress == null || ipAddress.isBlank() ? null : ipAddress.trim();
    }

    /** False until Installation Setup has saved this terminal's IP address. */
    public boolean isSetUp() {
        return ipAddress != null;
    }
}
