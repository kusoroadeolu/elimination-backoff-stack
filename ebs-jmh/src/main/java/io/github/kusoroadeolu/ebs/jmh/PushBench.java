package io.github.kusoroadeolu.ebs.jmh;

import io.github.kusoroadeolu.ebs.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
/*Benchmark                   (type)   Mode  Cnt  Score   Error   Units
PushBench.eightThreads    ELIM  thrpt   30  8.560 ± 1.104  ops/us
PushBench.eightThreads    LOCK  thrpt   30  8.935 ± 1.038  ops/us
PushBench.eightThreads    TREB  thrpt   30  4.904 ± 0.533  ops/us
PushBench.fourThreads     ELIM  thrpt   30  9.134 ± 0.709  ops/us
PushBench.fourThreads     LOCK  thrpt   30  9.017 ± 1.414  ops/us
PushBench.fourThreads     TREB  thrpt   30  6.024 ± 0.904  ops/us
PushBench.twoThreads      ELIM  thrpt   30  9.888 ± 1.398  ops/us
PushBench.twoThreads      LOCK  thrpt   30  8.838 ± 1.089  ops/us
PushBench.twoThreads      TREB  thrpt   30  8.105 ± 1.033  ops/us
*/
// Degrades gracefully, basically turns into a backoff treiber stack
public class PushBench {
    private ConcurrentStack<Integer> stack;
    @Param({"ELIM", "LOCK", "TREB"})
    private String type;

    @Setup
    public void setup() {
        stack = switch (type) {
            case "TREB" -> new TreiberStack<>();
            case "ELIM" -> new EliminationStack<>(WaitStrategy.PARK); //To avoid long park time during elim failures
            case "LOCK" -> new LockedStack<>();
            default -> throw new RuntimeException();
        };

    }

    @TearDown(Level.Iteration)
    public void teardown() {
        while (stack.pop() != null);
        if (type.equals("ELIM")) {
            var s = (EliminationStack<Integer>) stack;
            s.clearArrays();
        }
    }

    @Threads(2)
    @Benchmark
    public void twoThreads(Blackhole bh) {
        push(bh);
    }

    @Threads(4)
    @Benchmark
    public void fourThreads(Blackhole bh) {
        push(bh);
    }

    @Threads(8)
    @Benchmark
    public void eightThreads(Blackhole bh) {
        push(bh);
    }

    void push(Blackhole bh){
        bh.consume(stack.push(42));
    }
}
