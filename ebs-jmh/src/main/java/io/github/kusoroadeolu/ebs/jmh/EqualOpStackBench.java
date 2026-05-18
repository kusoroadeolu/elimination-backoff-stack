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
* Increased the max and min park time by 100%
EqualOpStackBench.eightThreads                       thrpt   30          45.034 ± 1.854  ops/us
EqualOpStackBench.eightThreads:failedCollisions      thrpt   30         447.000               #
EqualOpStackBench.eightThreads:similarOps            thrpt   30      122003.000               #
EqualOpStackBench.eightThreads:stackSuccesses        thrpt   30  1355863176.000               #
EqualOpStackBench.eightThreads:successfulCollisions  thrpt   30      113106.000               #
EqualOpStackBench.eightThreads:threadAbsence         thrpt   30       23372.000               #
EqualOpStackBench.fourThreads                        thrpt   30          47.787 ± 1.758  ops/us
EqualOpStackBench.fourThreads:failedCollisions       thrpt   30         254.000               #
EqualOpStackBench.fourThreads:similarOps             thrpt   30       50819.000               #
EqualOpStackBench.fourThreads:stackSuccesses         thrpt   30  1442509638.000               #
EqualOpStackBench.fourThreads:successfulCollisions   thrpt   30       30872.000               #
EqualOpStackBench.fourThreads:threadAbsence          thrpt   30        8998.000               #
EqualOpStackBench.twoThreads                         thrpt   30          51.383 ± 1.237  ops/us
EqualOpStackBench.twoThreads:failedCollisions        thrpt   30             ≈ 0               #
EqualOpStackBench.twoThreads:similarOps              thrpt   30       17275.000               #
EqualOpStackBench.twoThreads:stackSuccesses          thrpt   30  1575757217.000               #
EqualOpStackBench.twoThreads:successfulCollisions    thrpt   30             ≈ 0               #
EqualOpStackBench.twoThreads:threadAbsence           thrpt   30        2263.000               #
*
*
* I decided to experiment a bit more to see what moved the needle to improve thrpt, i started by increasing the size of the collision array from NCPU / 2 to NCPU
* Benchmark                                             Mode  Cnt           Score   Error   Units
EqualOpStackBench.eightThreads                       thrpt   30          46.630 ± 2.550  ops/us
EqualOpStackBench.eightThreads:failedCollisions      thrpt   30         508.000               #
EqualOpStackBench.eightThreads:similarOps            thrpt   30      120401.000               #
EqualOpStackBench.eightThreads:stackSuccesses        thrpt   30  1405007300.000               #
EqualOpStackBench.eightThreads:successfulCollisions  thrpt   30      116044.000               #
EqualOpStackBench.eightThreads:threadAbsence         thrpt   30       23953.000               #
EqualOpStackBench.fourThreads                        thrpt   30          50.391 ± 1.629  ops/us
EqualOpStackBench.fourThreads:failedCollisions       thrpt   30         266.000               #
EqualOpStackBench.fourThreads:similarOps             thrpt   30       50728.000               #
EqualOpStackBench.fourThreads:stackSuccesses         thrpt   30  1521352121.000               #
EqualOpStackBench.fourThreads:successfulCollisions   thrpt   30       30002.000               #
EqualOpStackBench.fourThreads:threadAbsence          thrpt   30        9013.000               #
EqualOpStackBench.twoThreads                         thrpt   30          49.996 ± 0.987  ops/us
EqualOpStackBench.twoThreads:failedCollisions        thrpt   30             ≈ 0               #
EqualOpStackBench.twoThreads:similarOps              thrpt   30       17955.000               #
EqualOpStackBench.twoThreads:stackSuccesses          thrpt   30  1530692684.000               #
EqualOpStackBench.twoThreads:successfulCollisions    thrpt   30             ≈ 0               #
EqualOpStackBench.twoThreads:threadAbsence           thrpt   30        1677.000               #
*
* I then decided to increase it again to NCPU * 2
*
Benchmark                                             Mode  Cnt           Score   Error   Units
EqualOpStackBench.eightThreads                       thrpt   30          45.430 ± 1.964  ops/us
EqualOpStackBench.eightThreads:failedCollisions      thrpt   30         427.000               #
EqualOpStackBench.eightThreads:similarOps            thrpt   30      121705.000               #
EqualOpStackBench.eightThreads:stackSuccesses        thrpt   30  1369780607.000               #
EqualOpStackBench.eightThreads:successfulCollisions  thrpt   30      111074.000               #
EqualOpStackBench.eightThreads:threadAbsence         thrpt   30       23075.000               #
EqualOpStackBench.fourThreads                        thrpt   30          48.986 ± 1.521  ops/us
EqualOpStackBench.fourThreads:failedCollisions       thrpt   30         226.000               #
EqualOpStackBench.fourThreads:similarOps             thrpt   30       50867.000               #
EqualOpStackBench.fourThreads:stackSuccesses         thrpt   30  1480418163.000               #
EqualOpStackBench.fourThreads:successfulCollisions   thrpt   30       26952.000               #
EqualOpStackBench.fourThreads:threadAbsence          thrpt   30        8930.000               #
EqualOpStackBench.twoThreads                         thrpt   30          51.586 ± 1.473  ops/us
EqualOpStackBench.twoThreads:failedCollisions        thrpt   30             ≈ 0               #
EqualOpStackBench.twoThreads:similarOps              thrpt   30       17357.000               #
EqualOpStackBench.twoThreads:stackSuccesses          thrpt   30  1571142825.000               #
EqualOpStackBench.twoThreads:successfulCollisions    thrpt   30             ≈ 0               #
EqualOpStackBench.twoThreads:threadAbsence           thrpt   30        2524.000               #
*
* From this we can see the size of the collision arr barely affects thrpt or collision count
*
*
* I decided to increase the base and max wait time of threads when backing off to see if that moves the needle and oddly, it doesnt
* Benchmark                                             Mode  Cnt           Score   Error   Units
EqualOpStackBench.eightThreads                       thrpt   30          45.027 ± 2.506  ops/us
EqualOpStackBench.eightThreads:failedCollisions      thrpt   30         386.000               #
EqualOpStackBench.eightThreads:similarOps            thrpt   30      119519.000               #
EqualOpStackBench.eightThreads:stackSuccesses        thrpt   30  1356773920.000               #
EqualOpStackBench.eightThreads:successfulCollisions  thrpt   30      117918.000               #
EqualOpStackBench.eightThreads:threadAbsence         thrpt   30       25178.000               #
EqualOpStackBench.fourThreads                        thrpt   30          50.554 ± 2.889  ops/us
EqualOpStackBench.fourThreads:failedCollisions       thrpt   30         368.000               #
EqualOpStackBench.fourThreads:similarOps             thrpt   30       51152.000               #
EqualOpStackBench.fourThreads:stackSuccesses         thrpt   30  1523962327.000               #
EqualOpStackBench.fourThreads:successfulCollisions   thrpt   30       36136.000               #
EqualOpStackBench.fourThreads:threadAbsence          thrpt   30        9242.000               #
EqualOpStackBench.twoThreads                         thrpt   30          52.297 ± 1.496  ops/us
EqualOpStackBench.twoThreads:failedCollisions        thrpt   30             ≈ 0               #
EqualOpStackBench.twoThreads:similarOps              thrpt   30       16893.000               #
EqualOpStackBench.twoThreads:stackSuccesses          thrpt   30  1598625411.000               #
EqualOpStackBench.twoThreads:successfulCollisions    thrpt   30             ≈ 0               #
EqualOpStackBench.twoThreads:threadAbsence           thrpt   30        2369.000               #
*
* I then decided to modify how wait times are modified by creating a separate counter
* for successful and failed collisions when dealing with wait back off rather than using a shared counter
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
