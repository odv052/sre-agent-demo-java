/// Runs chaindivide.ChainDivide with agent-ready logging: one JSON object per line with a stable event name,
/// trace_id/span_id, the service version, file:line of the log call, the data as key-value pairs
/// and the exception with its cause chain and stack trace.
///
/// Run from the repository root: java demo/ImprovedLogging.java [runs]   (JDK 25, no build step, no dependencies)

import chaindivide.ChainDivide;
import chaindivide.ChainDivide.Log;
import chaindivide.ChainDivide.Trace;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

static final String SERVICE = "chain-divide";
static final String VERSION = "1.4.2";
static final Path LOG_FILE = Path.of("logs", "chain-divide.log");

void main(String[] args) throws IOException {
    int runs = args.length > 0 ? Integer.parseInt(args[0]) : 20;
    Files.createDirectories(LOG_FILE.getParent());
    Files.deleteIfExists(LOG_FILE);
    long failed = new ChainDivide(new JsonLog()).runInParallel(runs);
    IO.println("%d runs, %d failed -> %s".formatted(runs, failed, LOG_FILE));
}

/// One JSON object per line. Field names follow the OpenTelemetry semantic conventions where one exists.
static final class JsonLog implements Log {

    // Fixed width, UTC: log lines sort as plain strings
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
            .withZone(ZoneOffset.UTC);

    @Override
    public void info(String event, String message, Object... keyValues) {
        write("INFO", event, message, null, keyValues);
    }

    @Override
    public void error(String event, String message, Throwable error, Object... keyValues) {
        write("ERROR", event, message + ": " + error, error, keyValues);
    }

    private static void write(String level, String event, String message, Throwable error, Object... keyValues) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("timestamp", TIMESTAMP.format(Instant.now()));
        json.put("level", level);
        json.put("service.name", SERVICE);
        json.put("service.version", VERSION);
        if (Trace.CURRENT.isBound()) {
            json.put("trace_id", Trace.CURRENT.get().traceId());
            json.put("span_id", Trace.CURRENT.get().spanId());
        }
        json.put("event", event);
        json.put("message", message);
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            json.put((String) keyValues[i], keyValues[i + 1]);
        }
        // file:line of the log call: the first stack frame outside this class
        StackWalker.getInstance()
                .walk(frames -> frames.filter(frame -> !frame.getClassName().equals(JsonLog.class.getName())).findFirst())
                .ifPresent(caller -> {
                    json.put("code.file.path", caller.getFileName());
                    json.put("code.line.number", caller.getLineNumber());
                    json.put("code.function.name", caller.getClassName() + "." + caller.getMethodName());
                });
        if (error != null) {
            json.put("exception.type", error.getClass().getName());
            json.put("exception.message", error.getMessage());
            json.put("exception.cause_chain", causeChain(error));
            StringWriter stacktrace = new StringWriter();
            error.printStackTrace(new PrintWriter(stacktrace));
            json.put("exception.stacktrace", stacktrace.toString());
        }
        String line = toJson(json) + "\n";
        synchronized (JsonLog.class) {
            try {
                Files.writeString(LOG_FILE, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }

    /// "type: message" of the exception and each of its causes, outermost first, root cause last.
    private static List<String> causeChain(Throwable error) {
        List<String> chain = new ArrayList<>();
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            chain.add(cause.getClass().getName() + ": " + cause.getMessage());
        }
        return chain;
    }

    private static String toJson(Object value) {
        return switch (value) {
            case null -> "null";
            case Number number -> number.toString();
            case Map<?, ?> map -> map.entrySet().stream()
                    .map(entry -> toJson(entry.getKey().toString()) + ":" + toJson(entry.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
            case List<?> list -> list.stream().map(JsonLog::toJson).collect(Collectors.joining(",", "[", "]"));
            default -> quote(value.toString());
        };
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c < 0x20 ? "\\u%04x".formatted((int) c) : String.valueOf(c));
            }
        }
        return out.append('"').toString();
    }
}
