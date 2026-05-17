package io.github.kusoroadeolu.ebs.jmh;

import io.github.kusoroadeolu.ebs.ConcurrentStack;
import io.github.kusoroadeolu.ebs.EliminationStack;
import io.github.kusoroadeolu.ebs.WaitStrategy;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
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
Benchmark                       (type)   Mode  Cnt   Score   Error   Units
WaitStrategyBench.eightThreads    SPIN  thrpt   30  12.886 ± 0.141  ops/us
WaitStrategyBench.eightThreads    PARK  thrpt   30  33.496 ± 2.425  ops/us
WaitStrategyBench.fourThreads     SPIN  thrpt   30  22.942 ± 2.278  ops/us
WaitStrategyBench.fourThreads     PARK  thrpt   30  33.253 ± 2.887  ops/us
WaitStrategyBench.twoThreads      SPIN  thrpt   30  19.422 ± 1.316  ops/us
WaitStrategyBench.twoThreads      PARK  thrpt   30  29.585 ± 1.006  ops/us
* Idle parks perform better across the board
* My hypothesis
* 1. 10 spins as a starting idling point is basically nothing, modern cpus blaze through it in pico seconds, so we're probably not even idling compared to a 100ns park
* 2. To actually reach our max idle spin count of 200 (which is honestly still basically nothing compared to a 1000ns park), at best, we need to successfully get collided with (without failing any collisions we started) around 10 times
* which is honestly basically  a long shot since threads are not deterministic
* */
public class WaitStrategyBench {
    private ConcurrentStack<Integer> stack;
    @Param({"SPIN", "PARK"})
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
        stack = type.equals("SPIN") ? new EliminationStack<>(WaitStrategy.SPIN) : new EliminationStack<>(WaitStrategy.PARK);
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
                    .include(WaitStrategyBench.class.getSimpleName())
                    //.addProfiler(JavaFlightRecorderProfiler.class, "dir=C:\\jfr-fc")
                    .build();
            new org.openjdk.jmh.runner.Runner(options).run();
        }
    }
}
