package shared;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Copies everything the app prints (System.out and System.err, so also uncaught
 * exceptions) into a log file, each line with the time. The installed app has no
 * console window, so this file is the only record of what went wrong at a store.
 *
 * Folder: the NSC_LOG_DIR setting; else "logs" next to the settings file
 * (%ProgramData%\NickelSales\logs); if that can't be written, %LOCALAPPDATA%\NickelSales\logs
 * (else ~/.nickelsales/logs). One file per day the app is started,
 * nickelsales-YYYY-MM-DD.log, appended to; files older than {@link #KEEP_DAYS} days are deleted.
 * The console still gets everything, as before.
 */
public final class LogFile {

    public static final String FOLDER_SETTING = "NSC_LOG_DIR";
    static final int KEEP_DAYS = 30;
    private static final String PREFIX = "nickelsales-";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static Path current;

    private LogFile() { }

    /**
     * Starts copying System.out and System.err into today's log file. Call once, first
     * thing in main. Returns the file, or null when no folder could be written (the app
     * still runs, it just has no log file; the reason is printed).
     */
    public static synchronized Path start() {
        if (current != null) {
            return current;
        }
        String day = LocalDate.now().toString();
        for (Path folder : candidateFolders()) {
            try {
                Files.createDirectories(folder);
                Path file = folder.resolve(PREFIX + day + ".log");
                OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                Object lock = new Object();
                System.setOut(tee(System.out, out, lock));
                System.setErr(tee(System.err, out, lock));
                current = file;
                deleteOld(folder);
                return file;
            } catch (IOException | RuntimeException e) {
                System.err.println("[LOG] Can't write a log file in " + folder + ": " + e);
            }
        }
        return null;
    }

    /** The folders tried, in order. */
    static Path[] candidateFolders() {
        String named = Settings.get(FOLDER_SETTING);
        if (named != null && !named.isBlank()) {
            return new Path[] { Path.of(named.trim()) };
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        Path personal = localAppData != null && !localAppData.isBlank()
                ? Path.of(localAppData, "NickelSales", "logs")
                : Path.of(System.getProperty("user.home"), ".nickelsales", "logs");
        return new Path[] { Settings.file().resolveSibling("logs"), personal };
    }

    /** A stream that writes to {@code console} as it is, and to {@code file} line by line with the time. */
    public static PrintStream tee(PrintStream console, OutputStream file, Object lock) {
        return new PrintStream(new TimedLines(console, file, lock), true, StandardCharsets.UTF_8);
    }

    private static void deleteOld(Path folder) {
        LocalDate oldest = LocalDate.now().minus(KEEP_DAYS, ChronoUnit.DAYS);
        try (DirectoryStream<Path> logs = Files.newDirectoryStream(folder, PREFIX + "*.log")) {
            for (Path log : logs) {
                String name = log.getFileName().toString();
                try {
                    LocalDate day = LocalDate.parse(name.substring(PREFIX.length(), name.length() - ".log".length()));
                    if (day.isBefore(oldest)) {
                        Files.deleteIfExists(log);
                    }
                } catch (RuntimeException | IOException notOurs) {
                    // not a dated log file, or in use: leave it
                }
            }
        } catch (IOException e) {
            System.err.println("[LOG] Can't clean up old logs in " + folder + ": " + e);
        }
    }

    /**
     * Passes bytes to the console unchanged; collects them into lines for the file, each
     * written whole (time first) so lines from System.out and System.err don't mix.
     */
    private static final class TimedLines extends OutputStream {
        private final PrintStream console;
        private final OutputStream file;
        private final Object lock;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        private boolean fileFailed;

        TimedLines(PrintStream console, OutputStream file, Object lock) {
            this.console = console;
            this.file = file;
            this.lock = lock;
        }

        @Override
        public void write(int b) {
            write(new byte[] { (byte) b }, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            console.write(bytes, offset, length);
            synchronized (lock) {
                for (int i = offset; i < offset + length; i++) {
                    if (bytes[i] == '\n') {
                        writeLine();
                    } else if (bytes[i] != '\r') {
                        line.write(bytes[i]);
                    }
                }
            }
        }

        @Override
        public void flush() {
            console.flush();
        }

        private void writeLine() {
            if (!fileFailed) {
                try {
                    file.write((LocalTime.now().format(TIME) + " ").getBytes(StandardCharsets.UTF_8));
                    line.writeTo(file);
                    file.write(System.lineSeparator().getBytes(StandardCharsets.UTF_8));
                    file.flush();
                } catch (IOException e) {
                    fileFailed = true;   // disk full or removed: keep the app running, console only
                    console.println("[LOG] Stopped writing the log file: " + e);
                }
            }
            line.reset();
        }
    }
}
