package chaindivide;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

/// Reads number sets from config/chain-divide.properties, divides the numbers of each set one by another
/// and uses the result as the key of a label from the same config.
public final class ChainDivide {

    static final Path CONFIG_FILE = Path.of("config", "chain-divide.properties");

    private final Log log;
    private final Properties config = new Properties();
    private final Map<Integer, String> labels = new HashMap<>();

    public ChainDivide(Log log) {
        this.log = log;
    }

    /// Several runs in parallel, each with its own number set (round-robin), so log lines of different runs get
    /// interleaved. Like OpenTelemetry instrumentation, every run gets its own trace context; whether it reaches
    /// the log is up to the logging setup. Returns the number of failed runs.
    public long runInParallel(int runs) throws IOException {
        loadConfig();
        List<String> sets = config.stringPropertyNames().stream()
                .filter(name -> name.startsWith("set."))
                .sorted(Comparator.comparingInt(name -> Integer.parseInt(name.substring("set.".length()))))
                .toList();
        List<Future<Boolean>> results = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < runs; i++) {
                String set = sets.get(i % sets.size());
                results.add(executor.submit(() -> ScopedValue.where(Trace.CURRENT, Trace.random()).call(() -> run(set))));
            }
        }
        return results.stream().filter(result -> !result.resultNow()).count();
    }

    private void loadConfig() throws IOException {
        try (Reader reader = Files.newBufferedReader(CONFIG_FILE)) {
            config.load(reader);
        }
        for (String name : config.stringPropertyNames()) {
            if (name.startsWith("label.")) {
                labels.put(Integer.parseInt(name.substring("label.".length())), config.getProperty(name));
            }
        }
    }

    boolean run(String set) throws InterruptedException {
        try {
            label(set);
            return true;
        } catch (RuntimeException exception) {
            log.error("chain_divide.failed", "Chain divide failed", exception, "config.set", set);
            return false;
        }
    }

    /// Divides the numbers of the set and returns the label for the result.
    String label(String set) throws InterruptedException {
        List<Integer> numbers = Arrays.stream(config.getProperty(set).split(",")).map(String::trim).map(Integer::parseInt).toList();
        log.info("numbers.loaded", "Loaded numbers: " + numbers, "config.set", set, "numbers", numbers);
        Thread.sleep(ThreadLocalRandom.current().nextInt(20));

        int key = chainDivide(numbers);
        log.info("label.lookup", "Looking up label for " + key, "key", key);
        Thread.sleep(ThreadLocalRandom.current().nextInt(20));

        String name = labels.get(key);
        String label = name.toUpperCase();
        log.info("label.found", "Label: " + label, "key", key, "label", label);
        return label;
    }

    /// Divides the numbers one by another.
    int chainDivide(List<Integer> numbers) {
        int result = numbers.getFirst();
        for (int divisor : numbers.subList(1, numbers.size())) {
            result = result / divisor;
        }
        return result;
    }

    /// What the logic needs from logging: an event name, a human-readable message and the data as key-value pairs.
    /// How (and whether) each part ends up in the log is up to the logging setup, see SimpleLogging and ImprovedLogging.
    public interface Log {

        void info(String event, String message, Object... keyValues);

        void error(String event, String message, Throwable error, Object... keyValues);
    }

    /// trace_id / span_id of the current run (W3C Trace Context sizes). In a real service OpenTelemetry creates them,
    /// propagates them to other services and puts them into the logging MDC.
    public record Trace(String traceId, String spanId) {

        public static final ScopedValue<Trace> CURRENT = ScopedValue.newInstance();

        static Trace random() {
            return new Trace(randomHex(16), randomHex(8));
        }

        private static String randomHex(int bytes) {
            byte[] random = new byte[bytes];
            ThreadLocalRandom.current().nextBytes(random);
            return HexFormat.of().formatHex(random);
        }
    }
}
