package io.github.kusoroadeolu.ebs.jmh;

import io.github.kusoroadeolu.ebs.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
/*
Thrpt (Park)
* Benchmark                   (type)   Mode  Cnt   Score   Error   Units
PushSkewBench.eightThreads    ELIM  thrpt   45  20.943 ± 3.252  ops/us
PushSkewBench.eightThreads    LOCK  thrpt   45  14.876 ± 1.873  ops/us
PushSkewBench.eightThreads    TREB  thrpt   45   6.004 ± 0.466  ops/us
PushSkewBench.fourThreads     ELIM  thrpt   45  44.503 ± 1.643  ops/us
PushSkewBench.fourThreads     LOCK  thrpt   45  14.976 ± 1.385  ops/us
PushSkewBench.fourThreads     TREB  thrpt   45   6.518 ± 0.657  ops/us
PushSkewBench.twoThreads      ELIM  thrpt   45  15.924 ± 2.500  ops/us
PushSkewBench.twoThreads      LOCK  thrpt   45  15.898 ± 0.264  ops/us
PushSkewBench.twoThreads      TREB  thrpt   45  13.276 ± 2.309  ops/us
* 75% push 25% pop
* The elim stacks thrpt drops as operation ratio becomes asymmetric,
* unlike the 100% push bench, there are some consumers,
* so the stack doesn't grow unbounded throughout
* the iteration which means less pressure on the GC hence more thrpt
* */


//Decided to choose spin as my baseline as it seems to have the most predictable latency

/* Latency (Spin)
Benchmark                   (type)  Mode  Cnt  Score   Error  Units
PushSkewBench.eightThreads    ELIM  avgt   30  0.601 ± 0.113  us/op
PushSkewBench.eightThreads    DESC  avgt   30  0.486 ± 0.104  us/op
PushSkewBench.fourThreads     ELIM  avgt   30  0.312 ± 0.025  us/op
PushSkewBench.fourThreads     DESC  avgt   30  0.373 ± 0.055  us/op
PushSkewBench.twoThreads      ELIM  avgt   30  0.319 ± 0.115  us/op
PushSkewBench.twoThreads      DESC  avgt   30  0.347 ± 0.252  us/op
* */


/* Thrpt (Spin)
* PushSkewBench.eightThreads    ELIM  thrpt   30  13.770 ± 2.669  ops/us
* ushSkewBench.eightThreads    DESC  thrpt   30  16.067 ± 2.478  ops/us
PushSkewBench.fourThreads     ELIM  thrpt   30  19.040 ± 3.257  ops/us
PushSkewBench.fourThreads     DESC  thrpt   30  14.334 ± 2.705  ops/us
PushSkewBench.twoThreads      ELIM  thrpt   30   9.375 ± 1.284  ops/us
PushSkewBench.twoThreads      DESC  thrpt   30   8.662 ± 1.083  ops/us
* */


/*
Benchmark                       (type)   Mode  Cnt    Score    Error   Units
PushSkewBench.eightThreads        ELIM  thrpt   30   16.696 ±  5.997  ops/us
PushSkewBench.eightThreads  MANES_ELIM  thrpt   30   16.341 ±  2.216  ops/us
* PushSkewBench.twoThreads          ELIM  thrpt   30    9.187 ±  0.770  ops/us
PushSkewBench.twoThreads    MANES_ELIM  thrpt   30   10.043 ±  1.040  ops/us
* */

/* SPIN
* Benchmark                   (type)   Mode  Cnt   Score   Error   Units
PushSkewBench.eightThreads    DESC  thrpt   30  19.659 ± 1.194  ops/us
PushSkewBench.fourThreads     DESC  thrpt   30  13.653 ± 2.429  ops/us
PushSkewBench.twoThreads      DESC  thrpt   30   7.603 ± 0.365  ops/us
*
* ADAPTIVE
* Benchmark                  (type)   Mode  Cnt   Score   Error   Units
PushPopBench.eightThreads    DECS  thrpt   30  15.357 ± 1.105  ops/us
PushPopBench.fourThreads     DECS  thrpt   30  30.903 ± 2.179  ops/us
PushPopBench.twoThreads      DECS  thrpt   30  32.140 ± 2.014  ops/us
*

* */

public class PushSkewBench {
    private ConcurrentStack<Integer> stack;
    @Param({ "DECS", "ELIM", "MANES_ELIM"})
    private String type;

    @State(Scope.Thread)
    public static class ThreadState {
        boolean pop = true;
        @Setup
        public void setup(BenchState bs) {
            pop = (bs.threadCounter.getAndIncrement() % 4) == 0;
        }
    }

    @State(Scope.Benchmark)
    public static class BenchState {
        AtomicInteger threadCounter = new AtomicInteger(1); // resets each iteration
    }


    @TearDown(Level.Iteration)
    public void teardown() {
        while (stack.pop() != null);
    }


    @Setup
    public void setup() {
        stack = switch (type) {
            case "MANES_ELIM" -> new ManesEliminationStack<>();
            case "DECS" -> new DECStack<>(WaitPolicy.adaptive());
            case "ELIM" -> new EliminationStack<>(WaitStrategy.SPIN);
            default -> throw new RuntimeException();
        };

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
        boolean isPop = ts.pop;

        if (isPop) bh.consume(stack.pop());
        else bh.consume(stack.push(42));
    }
}
