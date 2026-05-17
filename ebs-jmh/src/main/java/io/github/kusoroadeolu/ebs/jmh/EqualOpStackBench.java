package io.github.kusoroadeolu.ebs.jmh;

import io.github.kusoroadeolu.ebs.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.JavaFlightRecorderProfiler;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
/*
* 50% pop and 50% search

* Benchmark                (type)   Mode  Cnt   Score   Error   Units
EqualOpStackBench.eightThreads    ELIM  thrpt   30  36.108 ± 2.360  ops/us
EqualOpStackBench.eightThreads    LOCK  thrpt   30  19.791 ± 0.526  ops/us
EqualOpStackBench.eightThreads    TREB  thrpt   30   7.160 ± 0.312  ops/us
EqualOpStackBench.fourThreads     ELIM  thrpt   30  34.292 ± 1.652  ops/us
EqualOpStackBench.fourThreads     LOCK  thrpt   30  20.677 ± 0.246  ops/us
EqualOpStackBench.fourThreads     TREB  thrpt   30   8.365 ± 0.223  ops/us
EqualOpStackBench.twoThreads      ELIM  thrpt   30  37.238 ± 0.883  ops/us
EqualOpStackBench.twoThreads      LOCK  thrpt   30  18.703 ± 1.835  ops/us
EqualOpStackBench.twoThreads      TREB  thrpt   30  14.534 ± 0.688  ops/us
*
*
* Initially these results looked pretty impressive. I did decide to profile to look for any bottlenecks
* While nothing looked suspicious on the CPU side, the memory side told a whole different story.
* Thread info allocations used roughly around 100GB at 8 threads, which meant the GC was under pressure
* Ideally while a simple object creation should not, I added some padding for thread info objects to prevent false sharing
* in the "locations" array under high contention. I did suspect the possibility of high memory usage, just not to this level.
*
*Benchmark                        Mode  Cnt   Score   Error   Units
EqualOpStackBench.eightThreads  thrpt   30  44.535 ± 2.372  ops/us
EqualOpStackBench.fourThreads   thrpt   30  47.844 ± 2.001  ops/us
EqualOpStackBench.twoThreads    thrpt   30  50.270 ± 1.824  ops/us
*
* //Elim array size = NPCU / 2
* Benchmark                                             Mode  Cnt           Score   Error   Units
EqualOpStackBench.eightThreads                       thrpt   30          44.566 ± 2.705  ops/us
EqualOpStackBench.eightThreads:failedCollisions      thrpt   30         194.000               #
EqualOpStackBench.eightThreads:stackSuccesses        thrpt   30  1344001504.000               #
EqualOpStackBench.eightThreads:successfulCollisions  thrpt   30       59734.000               #
EqualOpStackBench.fourThreads                        thrpt   30          46.974 ± 1.698  ops/us
EqualOpStackBench.fourThreads:failedCollisions       thrpt   30         249.000               #
EqualOpStackBench.fourThreads:stackSuccesses         thrpt   30  1419841496.000               #
EqualOpStackBench.fourThreads:successfulCollisions   thrpt   30       19162.000               #
EqualOpStackBench.twoThreads                         thrpt   30          48.411 ± 1.321  ops/us
EqualOpStackBench.twoThreads:failedCollisions        thrpt   30             ≈ 0               #
EqualOpStackBench.twoThreads:stackSuccesses          thrpt   30  1484469671.000               #
EqualOpStackBench.twoThreads:successfulCollisions    thrpt   30             ≈ 0               #
*
* //Elim array size = NPCU / 4
Benchmark                                             Mode  Cnt           Score   Error   Units
EqualOpStackBench.eightThreads                       thrpt   30          45.398 ± 2.029  ops/us
EqualOpStackBench.eightThreads:failedCollisions      thrpt   30         235.000               #
EqualOpStackBench.eightThreads:stackSuccesses        thrpt   30  1367807803.000               #
EqualOpStackBench.eightThreads:successfulCollisions  thrpt   30       76902.000               #
EqualOpStackBench.fourThreads                        thrpt   30          47.463 ± 1.851  ops/us
EqualOpStackBench.fourThreads:failedCollisions       thrpt   30         376.000               #
EqualOpStackBench.fourThreads:stackSuccesses         thrpt   30  1433525126.000               #
EqualOpStackBench.fourThreads:successfulCollisions   thrpt   30       26544.000               #
EqualOpStackBench.twoThreads                         thrpt   30          49.273 ± 1.993  ops/us
EqualOpStackBench.twoThreads:failedCollisions        thrpt   30             ≈ 0               #
EqualOpStackBench.twoThreads:stackSuccesses          thrpt   30  1499237123.000               #
EqualOpStackBench.twoThreads:successfulCollisions    thrpt   30             ≈ 0               #
* */
public class EqualOpStackBench {
    private EliminationStack<Integer> stack;
//    @Param({"ELIM", "LOCK", "TREB"})
//    private String type;

    @State(Scope.Thread)
    @AuxCounters(AuxCounters.Type.EVENTS)
    public static class ThreadState {
        boolean push = true;
        public int successfulCollisions;
        public int failedCollisions;
        public int stackSuccesses;

        static final AtomicInteger threadCounter = new AtomicInteger();

        @Setup
        public void setup() {
            push = (threadCounter.getAndIncrement() % 2) == 0;
        }

        @TearDown(Level.Iteration)
        public void tearDown(EqualOpStackBench bench) {
            ConcurrentStack.Metrics m = bench.stack.getMetrics();
            successfulCollisions = m.successfulCollisions;
            failedCollisions = m.failedCollisions;
            stackSuccesses = m.stackSuccesses;
            m.reset();

        }
    }


    @Setup
    public void setup() {
//        stack = switch (type) {
//            case "ELIM" -> new EliminationStack<>(WaitStrategy.PARK);
//            case "LOCK" -> new LockedStack<>();
//            case "TREB" -> new TreiberStack<>();
//            default -> throw new RuntimeException();
//        };

        stack = new EliminationStack<>(WaitStrategy.PARK);
    }

    @Threads(2)
    @Benchmark
    public void twoThreads(Blackhole bh, ThreadState ts) {
        addOrRemove(bh, ts);
    }

    @Threads(4)
    @Benchmark
    public void fourThreads(Blackhole bh, ThreadState ts) {
        addOrRemove(bh, ts);
    }

    @Threads(8)
    @Benchmark
    public void eightThreads(Blackhole bh, ThreadState ts) {
        addOrRemove(bh, ts);
    }

    void addOrRemove(Blackhole bh, ThreadState ts){
        boolean isPush = ts.push;

        ts.push = !isPush;
        if (isPush) bh.consume(stack.push(42));
        else bh.consume(stack.pop());
    }

    static class Runner {
        static void main() throws RunnerException {
            Options options = new OptionsBuilder()
                    .include(EqualOpStackBench.class.getSimpleName())
                    .addProfiler(JavaFlightRecorderProfiler.class, "dir=C:\\jfr-stk")
                    .build();
            new org.openjdk.jmh.runner.Runner(options).run();
        }
    }
}
