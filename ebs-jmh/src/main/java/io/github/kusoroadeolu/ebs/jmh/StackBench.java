package io.github.kusoroadeolu.ebs.jmh;

import io.github.kusoroadeolu.ebs.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.JavaFlightRecorderProfiler;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;


/*
* Benchmark                  (type)   Mode  Cnt   Score   Error   Units
StackBench.eightThreads    ELIM  thrpt   30  45.456 ± 1.829  ops/us
StackBench.eightThreads    LOCK  thrpt   30  21.337 ± 0.567  ops/us
StackBench.eightThreads    TREB  thrpt   30   6.778 ± 0.509  ops/us
StackBench.fourThreads     ELIM  thrpt   30  45.887 ± 2.380  ops/us
StackBench.fourThreads     LOCK  thrpt   30  21.489 ± 0.509  ops/us
StackBench.fourThreads     TREB  thrpt   30   8.605 ± 0.170  ops/us
StackBench.twoThreads      ELIM  thrpt   30  51.187 ± 3.394  ops/us
StackBench.twoThreads      LOCK  thrpt   30  15.165 ± 0.802  ops/us
StackBench.twoThreads      TREB  thrpt   30  11.897 ± 0.890  ops/us
* */


/*
* Benchmark                      (type)   Mode  Cnt   Score   Error   Units
StackBench.eightThreads  MANES_ELIM  thrpt   30  11.312 ± 0.230  ops/us
StackBench.fourThreads   MANES_ELIM  thrpt   30  21.983 ± 1.089  ops/us
StackBench.twoThreads    MANES_ELIM  thrpt   30  42.943 ± 1.469  ops/us
* */

// DECS stack, worse thrpt than the elim stack but overall better than treiber and lock based

/* SPIN
Benchmark                  (type)   Mode  Cnt   Score   Error   Units
StackBench.eightThreads    DECS  thrpt   30  15.357 ± 1.105  ops/us
StackBench.fourThreads     DECS  thrpt   30  30.903 ± 2.179  ops/us
StackBench.twoThreads      DECS  thrpt   30  32.140 ± 2.014  ops/us

ADAPTIVE
* Benchmark                  (type)   Mode  Cnt   Score   Error   Units
StackBench.eightThreads    DECS  thrpt   30  35.192 ± 2.002  ops/us
StackBench.fourThreads     DECS  thrpt   30  37.384 ± 0.880  ops/us
StackBench.twoThreads      DECS  thrpt   30  33.017 ± 0.709  ops/us
* */

/* Latency (Park)
* Benchmark                  (type)  Mode  Cnt  Score   Error  Units
StackBench.eightThreads    ELIM  avgt   30  0.183 ± 0.010  us/op
StackBench.eightThreads    TREB  avgt   30  1.169 ± 0.047  us/op
StackBench.eightThreads    DECS  avgt   30  0.236 ± 0.015  us/op
StackBench.fourThreads     ELIM  avgt   30  0.088 ± 0.007  us/op
StackBench.fourThreads     TREB  avgt   30  0.462 ± 0.011  us/op
StackBench.fourThreads     DECS  avgt   30  0.108 ± 0.005  us/op
StackBench.twoThreads      ELIM  avgt   30  0.041 ± 0.004  us/op
StackBench.twoThreads      TREB  avgt   30  0.142 ± 0.006  us/op
StackBench.twoThreads      DECS  avgt   30  0.054 ± 0.007  us/op
* */


/* Latency (Spin)
* Benchmark                  (type)  Mode  Cnt  Score   Error  Units
StackBench.eightThreads    ELIM  avgt   30  0.362 ± 0.008  us/op
StackBench.eightThreads    DECS  avgt   30  0.267 ± 0.010  us/op
StackBench.fourThreads     ELIM  avgt   30  0.164 ± 0.024  us/op
StackBench.fourThreads     DECS  avgt   30  0.126 ± 0.005  us/op
StackBench.twoThreads      ELIM  avgt   30  0.103 ± 0.033  us/op
StackBench.twoThreads      DECS  avgt   30  0.078 ± 0.003  us/op
* */

//No wait policy just a 2000 plain int spin
/*
*  Benchmark                  (type)   Mode  Cnt   Score   Error   Units
StackBench.eightThreads    ELIM  thrpt   30  32.265 ± 0.978  ops/us
StackBench.fourThreads     ELIM  thrpt   30  36.522 ± 0.308  ops/us
StackBench.twoThreads      ELIM  thrpt   30  40.352 ± 2.420  ops/us
* */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(1)
public class StackBench {
    private ConcurrentStack<Integer> stack;
    @Param({"EliminationCombining"})
    private String type;

    private static final Integer TOKEN = 1;


    @Setup
    public void setup() {
        stack = switch (type) {
            case "Manes" -> new ManesEliminationStack<>();
            case "EliminationCombining" -> new EliminationCombiningStack<>();
            default -> throw new RuntimeException();
        };
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
* ╭─ io.github.kusoroadeolu.ebs.jmh.StackBench.ratio_50_50 ──╮
│  Type                 Role       Score  Error    Unit    │
│  -------------------- ---------- ------ -------- ------  │
│  EliminationCombining fifty_pop  23.501 ± 15.862 ops/us  │
│  EliminationCombining fifty_push 9.917  ± 2.738  ops/us  │
│  EliminationCombining popHit     8.834  ± 2.678  ops/us  │
│  EliminationCombining popMiss    14.668 ± 14.089 ops/us  │
│  EliminationCombining aggregate  33.419 ± 17.023 ops/us  │
│  Manes                fifty_pop  23.643 ± 3.874  ops/us  │
│  Manes                fifty_push 11.717 ± 0.333  ops/us  │
│  Manes                popHit     11.765 ± 0.350  ops/us  │
│  Manes                popMiss    11.969 ± 3.860  ops/us  │
│  Manes                aggregate  35.360 ± 3.933  ops/us  │
╰──────────────────────────────────────────────────────────╯

╭──── io.github.kusoroadeolu.ebs.jmh.StackBench.ratio_75_25 ─────╮
│  Type                 Role              Score  Error   Unit    │
│  -------------------- ----------------- ------ ------- ------  │
│  EliminationCombining popHit            10.065 ± 1.592 ops/us  │
│  EliminationCombining popMiss           19.681 ± 6.285 ops/us  │
│  EliminationCombining seventy_five_push 10.037 ± 1.591 ops/us  │
│  EliminationCombining twenty_five_pop   29.744 ± 4.840 ops/us  │
│  EliminationCombining aggregate         39.781 ± 3.514 ops/us  │
│  Manes                popHit            9.834  ± 0.588 ops/us  │
│  Manes                popMiss           8.664  ± 4.935 ops/us  │
│  Manes                seventy_five_push 9.761  ± 0.554 ops/us  │
│  Manes                twenty_five_pop   18.416 ± 4.989 ops/us  │
│  Manes                aggregate         28.177 ± 5.073 ops/us  │
╰────────────────────────────────────────────────────────────────╯
* */