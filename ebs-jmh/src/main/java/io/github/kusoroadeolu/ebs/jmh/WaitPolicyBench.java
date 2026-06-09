package io.github.kusoroadeolu.ebs.jmh;

import io.github.kusoroadeolu.ebs.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/* Elimination stack
Benchmark                       (type)   Mode  Cnt   Score   Error   Units
WaitPolicyBench.eightThreads  ADAPTIVE  thrpt   30  47.177 ± 2.209  ops/us
WaitPolicyBench.eightThreads   DEFAULT  thrpt   30  47.755 ± 1.871  ops/us
WaitPolicyBench.fourThreads   ADAPTIVE  thrpt   30  47.519 ± 1.551  ops/us
WaitPolicyBench.fourThreads    DEFAULT  thrpt   30  47.626 ± 2.726  ops/us
WaitPolicyBench.twoThreads    ADAPTIVE  thrpt   30  40.092 ± 1.022  ops/us
WaitPolicyBench.twoThreads     DEFAULT  thrpt   30  48.282 ± 1.362  ops/us
*
*
Benchmark                       (type)  Mode  Cnt  Score   Error  Units
WaitPolicyBench.eightThreads   DEFAULT  avgt   30  0.179 ± 0.008  us/op
WaitPolicyBench.eightThreads  ADAPTIVE  avgt   30  0.179 ± 0.009  us/op
WaitPolicyBench.fourThreads    DEFAULT  avgt   30  0.082 ± 0.004  us/op
WaitPolicyBench.fourThreads   ADAPTIVE  avgt   30  0.087 ± 0.003  us/op
WaitPolicyBench.twoThreads     DEFAULT  avgt   30  0.049 ± 0.005  us/op
WaitPolicyBench.twoThreads    ADAPTIVE  avgt   30  0.057 ± 0.003  us/op
* */


/* DECS Stack
Benchmark                       (type)   Mode  Cnt   Score   Error   Units
WaitPolicyBench.eightThreads  ADAPTIVE  thrpt   30  28.934 ± 2.446  ops/us
WaitPolicyBench.eightThreads   DEFAULT  thrpt   30  33.202 ± 1.613  ops/us
WaitPolicyBench.fourThreads   ADAPTIVE  thrpt   30  28.831 ± 2.618  ops/us
WaitPolicyBench.fourThreads    DEFAULT  thrpt   30  35.873 ± 1.709  ops/us
WaitPolicyBench.twoThreads    ADAPTIVE  thrpt   30  31.477 ± 0.838  ops/us
WaitPolicyBench.twoThreads     DEFAULT  thrpt   30  37.745 ± 0.537  ops/us
* */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
public class WaitPolicyBench {
    private ConcurrentStack<Integer> stack;
    @Param({"ADAPTIVE", "DEFAULT"})
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
        stack = type.equals("ADAPTIVE") ? new DECStack<>(WaitPolicy.adaptive()) : new DECStack<>(WaitStrategy.PARK);
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
