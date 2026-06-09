package io.github.kusoroadeolu.ebs.jmh;

import io.github.kusoroadeolu.ebs.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.JavaFlightRecorderProfiler;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
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


//DECS stack overall less thrpt that the elim stack but better than the treiber stack

// Benchmark                Mode  Cnt  Score   Error   Units
//PushBench.eightThreads  thrpt   30  8.004 ± 1.221  ops/us
//PushBench.fourThreads   thrpt   30  8.573 ± 1.189  ops/us
//PushBench.twoThreads    thrpt   30  8.041 ± 1.176  ops/us


/* Latency (Park)
Benchmark               (type)  Mode  Cnt   Score    Error  Units
PushBench.eightThreads    ELIM  avgt   30   1.195 ±  0.450  us/op
PushBench.eightThreads    TREB  avgt   30   1.892 ±  0.291  us/op
PushBench.eightThreads    DECS  avgt   30   1.669 ±  0.756  us/op
PushBench.fourThreads     ELIM  avgt   30   0.478 ±  0.028  us/op
PushBench.fourThreads     TREB  avgt   30   0.695 ±  0.088  us/op
PushBench.fourThreads     DECS  avgt   30  17.653 ± 60.427  us/op
PushBench.twoThreads      ELIM  avgt   30   0.265 ±  0.039  us/op
PushBench.twoThreads      TREB  avgt   30   0.272 ±  0.030  us/op
PushBench.twoThreads      DECS  avgt   30   0.415 ±  0.383  us/ope
* */

/* Latency (Spin)
* Benchmark               (type)  Mode  Cnt  Score   Error  Units
PushBench.eightThreads    ELIM  avgt   30  1.313 ± 0.198  us/op
PushBench.eightThreads    DECS  avgt   30  1.036 ± 0.109  us/op
PushBench.fourThreads     ELIM  avgt   30  0.574 ± 0.056  us/op
PushBench.fourThreads     DECS  avgt   30  0.500 ± 0.047  us/op
PushBench.twoThreads      ELIM  avgt   30  0.354 ± 0.184  us/op
PushBench.twoThreads      DECS  avgt   30  0.245 ± 0.022  us/op
* */
public class PushBench {
    private ConcurrentStack<Integer> stack;
    @Param({"ELIM", "DESC", "TREB"})
  private String type;

    @Setup
    public void setup() {
        stack = switch (type) {
            case "ELIM" -> new EliminationStack<>(WaitStrategy.SPIN);
            case "TREB" -> new TreiberStack<>();
            case "DECS" -> new DECStack<>(WaitStrategy.PARK);
            default -> throw new RuntimeException();
        };

    }

    @TearDown(Level.Iteration)
    public void teardown() {
        while (stack.pop() != null);
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

    static class Runner {
        static void main() throws RunnerException {
            Options options = new OptionsBuilder()
                    .include(PushBench.class.getSimpleName())
                    .addProfiler(JavaFlightRecorderProfiler.class, "dir=C:\\jfr-stk")
                    .build();
            new org.openjdk.jmh.runner.Runner(options).run();
        }
    }
}
