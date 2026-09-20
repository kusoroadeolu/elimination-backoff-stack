package io.github.kusoroadeolu.ebs.jmh;

import io.github.kusoroadeolu.ebs.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.JavaFlightRecorderProfiler;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;


@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(2)
public class StackBench {
    private ConcurrentStack<Integer> stack;
    @Param({"EliminationCombining", "Manes"})
    private String type;

    private static final Integer TOKEN = 1;
    private static final int FILL_SIZE = 10_000_000;


    @Setup
    public void setup() {
        stack = switch (type) {
            case "Manes" -> new ManesEliminationStack<>();
            case "EliminationCombining" -> new EliminationCombiningStack<>();
            default -> throw new RuntimeException();
        };

        for (int i = 0; i < FILL_SIZE; ++i) {
            stack.push(TOKEN);
        }
    }

    @AuxCounters(AuxCounters.Type.OPERATIONS)
    @State(Scope.Thread)
    public static class PopCounters {
        public long popHit;
        public long popMiss;

        @Setup(Level.Iteration)
        public void reset() {
            popHit = 0;
            popMiss = 0;
        }
    }

    @Group("ratio_75_25")
    @GroupThreads(6)
    @Benchmark
    public void seventy_five_push(Blackhole bh) {
        bh.consume(stack.push(TOKEN));
    }

    @Group("ratio_75_25")
    @GroupThreads(2)
    @Benchmark
    public void twenty_five_pop(Blackhole bh, PopCounters counters) {
        Integer result = stack.pop();
        bh.consume(result);
        if (result == null) {
            counters.popMiss++;
        } else {
            counters.popHit++;
        }
    }

    @Group("ratio_50_50")
    @GroupThreads(4)
    @Benchmark
    public void fifty_push(Blackhole bh) {
        bh.consume(stack.push(TOKEN));
    }

    @Group("ratio_50_50")
    @GroupThreads(4)
    @Benchmark
    public void fifty_pop(Blackhole bh, PopCounters counters) {
        Integer result = stack.pop();
        bh.consume(result);
        if (result == null) {
            counters.popMiss++;
        } else {
            counters.popHit++;
        }
    }

    static class Runner {
        static void main() throws RunnerException {
            Options options = new OptionsBuilder()
                    .include(StackBench.class.getSimpleName())
                    .addProfiler(JavaFlightRecorderProfiler.class, "dir=C:\\jfr-stk")
                    .build();
            new org.openjdk.jmh.runner.Runner(options).run();
        }
    }
}

/*
╭─ io.github.kusoroadeolu.ebs.jmh.StackBench.ratio_50_50 ─╮
│  Type                 Role       Score  Error   Unit    │
│  -------------------- ---------- ------ ------- ------  │
│  EliminationCombining fifty_pop  21.033 ± 7.479 ops/us  │
│  EliminationCombining fifty_push 15.532 ± 1.808 ops/us  │
│  EliminationCombining popHit     15.098 ± 2.418 ops/us  │
│  EliminationCombining popMiss    5.956  ± 5.390 ops/us  │
│  EliminationCombining aggregate  36.565 ± 9.120 ops/us  │
│  Manes                fifty_pop  17.836 ± 8.777 ops/us  │
│  Manes                fifty_push 12.263 ± 0.313 ops/us  │
│  Manes                popHit     12.888 ± 0.459 ops/us  │
│  Manes                popMiss    5.054  ± 9.081 ops/us  │
│  Manes                aggregate  30.099 ± 8.808 ops/us  │
╰─────────────────────────────────────────────────────────╯

╭──── io.github.kusoroadeolu.ebs.jmh.StackBench.ratio_75_25 ─────╮
│  Type                 Role              Score  Error   Unit    │
│  -------------------- ----------------- ------ ------- ------  │
│  EliminationCombining popHit            11.466 ± 2.075 ops/us  │
│  EliminationCombining popMiss           0.000  ± 0.000 ops/us  │
│  EliminationCombining seventy_five_push 13.195 ± 1.414 ops/us  │
│  EliminationCombining twenty_five_pop   11.455 ± 2.073 ops/us  │
│  EliminationCombining aggregate         24.649 ± 3.415 ops/us  │
│  Manes                popHit            10.896 ± 0.952 ops/us  │
│  Manes                popMiss           0.000  ± 0.000 ops/us  │
│  Manes                seventy_five_push 10.608 ± 0.798 ops/us  │
│  Manes                twenty_five_pop   10.824 ± 0.973 ops/us  │
│  Manes                aggregate         21.432 ± 1.302 ops/us  │
╰────────────────────────────────────────────────────────────────╯

* */