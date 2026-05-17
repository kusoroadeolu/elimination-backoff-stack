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
* Ideally while a simple object creation should not, I added some padding for thread info objects to prevent false sharing initially
* in the "locations" array under high contention. I did suspect the possibility of high memory usage, just not to this level.
*  Due to the high memory usage I removed the padding and the thrpt increased significantly
*
*Benchmark                        Mode  Cnt   Score   Error   Units
EqualOpStackBench.eightThreads  thrpt   30  44.535 ± 2.372  ops/us
EqualOpStackBench.fourThreads   thrpt   30  47.844 ± 2.001  ops/us
EqualOpStackBench.twoThreads    thrpt   30  50.270 ± 1.824  ops/us
*
* //Elim array size = NPCU / 2
Benchmark                                             Mode  Cnt           Score   Error   Units
EqualOpStackBench.eightThreads                       thrpt   30          44.035 ± 2.125  ops/us
EqualOpStackBench.eightThreads:failedCollisions      thrpt   30         190.000               #
EqualOpStackBench.eightThreads:similarOps            thrpt   30      123711.000               #
EqualOpStackBench.eightThreads:stackSuccesses        thrpt   30  1330838907.000               #
EqualOpStackBench.eightThreads:successfulCollisions  thrpt   30       57820.000               #
EqualOpStackBench.eightThreads:threadAbsence         thrpt   30       18830.000               #
EqualOpStackBench.fourThreads                        thrpt   30          47.216 ± 2.088  ops/us
EqualOpStackBench.fourThreads:failedCollisions       thrpt   30         242.000               #
EqualOpStackBench.fourThreads:similarOps             thrpt   30       44831.000               #
EqualOpStackBench.fourThreads:stackSuccesses         thrpt   30  1425514456.000               #
EqualOpStackBench.fourThreads:successfulCollisions   thrpt   30       20012.000               #
EqualOpStackBench.fourThreads:threadAbsence          thrpt   30       12863.000               #
EqualOpStackBench.twoThreads                         thrpt   30          48.795 ± 1.476  ops/us
EqualOpStackBench.twoThreads:failedCollisions        thrpt   30             ≈ 0               #
EqualOpStackBench.twoThreads:similarOps              thrpt   30       15964.000               #
EqualOpStackBench.twoThreads:stackSuccesses          thrpt   30  1488073239.000               #
EqualOpStackBench.twoThreads:successfulCollisions    thrpt   30             ≈ 0               #
EqualOpStackBench.twoThreads:threadAbsence           thrpt   30        3462.000               #
*
*
* From the metrics, encountering similar operations is much more than thread absence in the elim array and us completing a successful elimination
* I can redesign this for both pop and push to use separate elimination to ever prevent the issue of encountering similar operations in the elim array
*
*
Benchmark                                             Mode  Cnt           Score   Error   Units
EqualOpStackBench.eightThreads                       thrpt   30          44.122 ± 3.702  ops/us
EqualOpStackBench.eightThreads:failedCollisions      thrpt   30        1787.000               #
EqualOpStackBench.eightThreads:similarOps            thrpt   30       19524.000               #
EqualOpStackBench.eightThreads:stackSuccesses        thrpt   30  1328904850.000               #
EqualOpStackBench.eightThreads:successfulCollisions  thrpt   30      116204.000               #
EqualOpStackBench.eightThreads:threadAbsence         thrpt   30       16810.000               #
EqualOpStackBench.fourThreads                        thrpt   30          48.442 ± 1.998  ops/us
EqualOpStackBench.fourThreads:failedCollisions       thrpt   30         559.000               #
EqualOpStackBench.fourThreads:similarOps             thrpt   30        4847.000               #
EqualOpStackBench.fourThreads:stackSuccesses         thrpt   30  1463214117.000               #
EqualOpStackBench.fourThreads:successfulCollisions   thrpt   30       31912.000               #
EqualOpStackBench.fourThreads:threadAbsence          thrpt   30        5209.000               #
EqualOpStackBench.twoThreads                         thrpt   30          50.824 ± 2.100  ops/us
EqualOpStackBench.twoThreads:failedCollisions        thrpt   30             ≈ 0               #
EqualOpStackBench.twoThreads:similarOps              thrpt   30       11725.000               #
EqualOpStackBench.twoThreads:stackSuccesses          thrpt   30  1557312212.000               #
EqualOpStackBench.twoThreads:successfulCollisions    thrpt   30             ≈ 0               #
EqualOpStackBench.twoThreads:threadAbsence           thrpt   30        7881.000               #
* The number of similar operations does reduce significantly though across all threads, thrpt increased slightly
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
        public int similarOps;
        public int threadAbsence;

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
            threadAbsence = m.threadAbsence;
            similarOps = m.similarOps;
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
