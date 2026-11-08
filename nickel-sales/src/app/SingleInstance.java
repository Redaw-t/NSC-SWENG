package app;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Lets only one copy of the app run for each Windows user: a second double-click on the
 * shortcut would otherwise open a second POS window on the same register. The first copy
 * holds a lock on a file in the user's temp folder until it exits (Windows releases it even
 * if the app crashes).
 */
final class SingleInstance {

    private static FileChannel channel;   // kept open, and so locked, until the app exits
    private static FileLock lock;

    private SingleInstance() { }

    /** True if this is the only copy (and it now holds the lock); false if another copy is running. */
    static synchronized boolean claim(Path lockFile) {
        if (lock != null) {
            return true;
        }
        try {
            FileChannel opened = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock taken;
            try {
                taken = opened.tryLock();
            } catch (OverlappingFileLockException sameProcess) {
                taken = null;
            }
            if (taken == null) {
                opened.close();
                return false;
            }
            channel = opened;
            lock = taken;
            return true;
        } catch (IOException e) {
            // Can't tell: better a second window than no POS at all.
            System.err.println("[APP] Could not check for another running copy: " + e);
            return true;
        }
    }

    /** The lock file used by the real app. */
    static Path defaultFile() {
        return Path.of(System.getProperty("java.io.tmpdir"), "NickelSales.lock");
    }
}
