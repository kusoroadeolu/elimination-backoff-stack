package io.github.kusoroadeolu.ebs.stress;

import io.github.kusoroadeolu.ebs.DECSStack;
import io.github.kusoroadeolu.ebs.WaitStrategy;
import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.I_Result;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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


    @JCStressTest
    @Outcome(id = "1", expect = Expect.ACCEPTABLE, desc = "Invariant maintained")
    @Outcome(id = "0", expect = Expect.ACCEPTABLE_INTERESTING, desc = "Pop ran before push")
    @State
    public static class PushPopCompleteness {
        public final DECSStack<Integer> stack;
        public final Set<Integer> set;

        public PushPopCompleteness() {
            stack = new DECSStack<>(4, 1, WaitStrategy.SPIN);
            set = ConcurrentHashMap.newKeySet(2);
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
            var value = stack.pop();
            if (value != null) set.add(value);
        }

        @Actor
        public void actor3() {
            var value = stack.pop();
            if (value != null) set.add(value);

        }


        @Arbiter
        public void arbiter(I_Result r) {
            var ls = stack.toList();
            if (set.contains(1) && set.contains(2)) r.r1 = 1;
            else if (set.contains(1) && ls.contains(2)) r.r1 = 1;
            else if (set.contains(2) && ls.contains(1)) r.r1 = 1;
            else if (ls.contains(1) && ls.contains(2)) r.r1 = 0;
            set.clear();
        }
    }


    @JCStressTest
    @Outcome(id = "1", expect = Expect.ACCEPTABLE, desc = "Each value popped at most once")
    @Outcome(id = "0", expect = Expect.FORBIDDEN, desc = "Duplicate pop detected!")
    @State
    public static class UniqueReads {
        public final DECSStack<Integer> stack;
        public final Map<Integer, Integer> popCounts;

        public UniqueReads() {
            stack = new DECSStack<>(5, 1, WaitStrategy.SPIN); //4 actors plus the constructor thread
            popCounts = new ConcurrentHashMap<>();
            stack.push(1);
            stack.push(2);
        }

        @Actor
        public void actor() {
            var value = stack.pop();
            if (value != null) popCounts.merge(value, 1, Integer::sum);
        }

        @Actor
        public void actor1() {
            var value = stack.pop();
            if (value != null) popCounts.merge(value, 1, Integer::sum);
        }

        @Actor
        public void actor2() {
            var value = stack.pop();
            if (value != null) popCounts.merge(value, 1, Integer::sum);
        }

        @Actor
        public void actor3() {
            var value = stack.pop();
            if (value != null) popCounts.merge(value, 1, Integer::sum);
        }

        @Arbiter
        public void arbiter(I_Result r) {
            boolean noDuplicates = popCounts.values().stream().allMatch(count -> count <= 1);
            r.r1 = noDuplicates ? 1 : 0;
            popCounts.clear();
        }
    }


}
