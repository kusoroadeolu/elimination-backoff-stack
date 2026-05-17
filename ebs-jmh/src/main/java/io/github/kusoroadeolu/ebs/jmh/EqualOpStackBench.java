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
* */
public class EqualOpStackBench {
    private ConcurrentStack<Integer> stack;
    @Param({"ELIM", "LOCK", "TREB"})
    private String type;

    @State(Scope.Thread)
    public static class ThreadState {
        boolean push = true;

        static final AtomicInteger threadCounter = new AtomicInteger();

        @Setup
        public void setup() {
            // Odd-numbered threads start with push, even with pop
            push = (threadCounter.getAndIncrement() % 2) == 0;
        }
    }


    @Setup
    public void setup() {
        stack = switch (type) {
            case "ELIM" -> new EliminationStack<>(WaitStrategy.PARK);
            case "LOCK" -> new LockedStack<>();
            case "TREB" -> new TreiberStack<>();
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
        boolean isPush = ts.push;

        ts.push = !isPush;
        if (isPush) bh.consume(stack.push(42));
        else bh.consume(stack.pop());
    }
}
