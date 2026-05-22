package io.github.kusoroadeolu.ebs.stress;

import io.github.kusoroadeolu.ebs.DECSStack;
import io.github.kusoroadeolu.ebs.WaitStrategy;
import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.I_Result;

public class DecsStress {
    @JCStressTest
    @Outcome(id = "1", expect = Expect.ACCEPTABLE, desc = "Invariant maintained")
    @Outcome(id = "0", expect = Expect.FORBIDDEN, desc = "Invariant violated")
    @State
    public static class NoLostWrites {
        public final DECSStack<Integer> stack;

        public NoLostWrites() {
            stack = new DECSStack<>(4, 1, WaitStrategy.SPIN);
        }


        @Actor
        public void actor() {
            stack.push(1);
        }

        @Actor
        public void actor1() {
            stack.push(2);
        }

        @Actor
        public void actor2() {
            stack.push(3);
        }

        @Actor
        public void actor3() {
            stack.push(4);
        }


        @Arbiter
        public void arbiter(I_Result r) {
            var ls = stack.toList();
            boolean noLostWrites = ls.contains(1) && ls.contains(2) && ls.contains(3) && ls.contains(4);
            r.r1 = noLostWrites ? 1 : 0;
        }
    }
}
