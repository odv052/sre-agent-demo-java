/// Runs chaindivide.ChainDivide with the simplest logging: plain text, timestamp, level, message.
/// Event names, key-value data, the trace context and stack traces are dropped. Fine for a human reading one run;
/// with parallel runs nothing tells which line belongs to which run.
///
/// Run from the repository root: java demo/SimpleLogging.java [runs]   (JDK 25, no build step, no dependencies)

import chaindivide.ChainDivide;
import chaindivide.ChainDivide.Log;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

static final Path LOG_FILE = Path.of("logs", "chain-divide-simple.log");

void main(String[] args) throws IOException {
    int runs = args.length > 0 ? Integer.parseInt(args[0]) : 20;
    Files.createDirectories(LOG_FILE.getParent());
    Files.deleteIfExists(LOG_FILE);
    long failed = new ChainDivide(new TextLog()).runInParallel(runs);
    IO.println("%d runs, %d failed -> %s".formatted(runs, failed, LOG_FILE));
}

/// `2026-10-18 17:18:54 INFO Loaded numbers: [100, 5, 0, 2]`
static final class TextLog implements Log {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public void info(String event, String message, Object... keyValues) {
        write("INFO", message);
    }

    @Override
    public void error(String event, String message, Throwable error, Object... keyValues) {
        write("ERROR", message + ": " + error.getMessage());
    }

    private static synchronized void write(String level, String message) {
        String line = TIMESTAMP.format(LocalDateTime.now()) + " " + level + " " + message + "\n";
        try {
            Files.writeString(LOG_FILE, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
