package chaindivide;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

/// Generates five random numbers from 0 to 4 and divides them one by another. Sometimes a divisor is 0.
public final class ChainDivide {

    private final Log log;

    public ChainDivide(Log log) {
        this.log = log;
    }

    /// Several runs in parallel, so log lines of different runs get interleaved. Like OpenTelemetry
    /// instrumentation, every run gets its own trace context; whether it reaches the log is up to the logging setup.
    /// Returns the number of failed runs.
    public long runInParallel(int runs) {
        List<Future<Boolean>> results = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < runs; i++) {
                results.add(executor.submit(() -> ScopedValue.where(Trace.CURRENT, Trace.random()).call(this::run)));
            }
        }
        return results.stream().filter(result -> !result.resultNow()).count();
    }

    boolean run() throws InterruptedException {
        try {
            chainDivide();
            return true;
        } catch (ArithmeticException exception) {
            log.error("chain_divide.failed", "Chain divide failed", exception);
            return false;
        }
    }

    BigDecimal chainDivide() throws InterruptedException {
        List<Integer> numbers = new ArrayList<>();
        for (int position = 1; position <= 5; position++) {
            int number = ThreadLocalRandom.current().nextInt(0, 5);
            log.info("number.generated", "Generated number #%d: %d".formatted(position, number), "position", position, "value", number);
            numbers.add(number);
            Thread.sleep(ThreadLocalRandom.current().nextInt(20));
        }

        BigDecimal result = BigDecimal.valueOf(numbers.getFirst());
        for (int divisor : numbers.subList(1, numbers.size())) {
            result = result.divide(BigDecimal.valueOf(divisor), MathContext.DECIMAL64);
        }
        log.info("chain_divide.completed", "Result: " + result, "result", result);
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
