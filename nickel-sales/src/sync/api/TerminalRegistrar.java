package sync.api;

/**
 * CONTRACT, implemented on the Main POS (app.install.PosTerminalRepository): hands out terminal IDs
 * to Additional POS terminals that ask through the API.
 */
@FunctionalInterface
public interface TerminalRegistrar {

    /**
     * The terminal ID for the computer at {@code ipAddress} (T02, T03 ...): the one it already has if
     * it asked before, else the next free one, which is saved.
     *
     * @param computerName the Windows computer name; may be null
     * @throws RuntimeException the ID could not be saved
     */
    String register(String ipAddress, String computerName);
}
