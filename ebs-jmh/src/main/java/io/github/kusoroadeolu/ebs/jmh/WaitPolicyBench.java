package io.github.kusoroadeolu.ebs.jmh;

import io.github.kusoroadeolu.ebs.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/* Elimination stack
* Benchmark                       (type)   Mode  Cnt   Score   Error   Units
WaitPolicyBench.eightThreads   DEFAULT  thrpt   30  42.910 ± 2.781  ops/us
WaitPolicyBench.eightThreads  ADAPTIVE  thrpt   30  48.853 ± 1.799  ops/us
WaitPolicyBench.fourThreads    DEFAULT  thrpt   30  47.889 ± 1.851  ops/us
WaitPolicyBench.fourThreads   ADAPTIVE  thrpt   30  47.379 ± 2.707  ops/us
WaitPolicyBench.twoThreads     DEFAULT  thrpt   30  47.816 ± 2.168  ops/us
WaitPolicyBench.twoThreads    ADAPTIVE  thrpt   30  35.786 ± 4.049  ops/us
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
* Benchmark                       (type)   Mode  Cnt   Score   Error   Units
WaitPolicyBench.eightThreads  ADAPTIVE  thrpt   30  31.063 ± 2.065  ops/us
WaitPolicyBench.eightThreads   DEFAULT  thrpt   30  33.747 ± 2.677  ops/us
WaitPolicyBench.fourThreads   ADAPTIVE  thrpt   30  32.845 ± 0.989  ops/us
WaitPolicyBench.fourThreads    DEFAULT  thrpt   30  38.142 ± 0.856  ops/us
WaitPolicyBench.twoThreads    ADAPTIVE  thrpt   30  32.154 ± 0.660  ops/us
WaitPolicyBench.twoThreads     DEFAULT  thrpt   30  38.929 ± 1.626  ops/us
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
        stack = type.equals("ADAPTIVE") ? new EliminationStack<>(WaitPolicy.adaptive()) : new EliminationStack<>(WaitStrategy.PARK);
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
