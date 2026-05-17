package io.github.kusoroadeolu.ebs;

import org.openjdk.jol.info.ClassLayout;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import static io.github.kusoroadeolu.ebs.ConcurrentStack.Node.EMPTY;


@SuppressWarnings("unchecked")
public interface ConcurrentStack<T> {
    boolean push(T t);
    T pop();

    class Metrics {
        public int successfulCollisions;
        public int failedCollisions;
        public int stackSuccesses;
        public int threadAbsence;
        public int similarOps;


        public void reset() {
            successfulCollisions = 0;
            failedCollisions = 0;
            stackSuccesses = 0;
            threadAbsence = 0;
            similarOps = 0;
        }
    }

    @SuppressWarnings("unchecked")
    class Node<T> {
        final T value;
        private volatile Node<T> next;
        static final Node<?> EMPTY = new Node<>(null);

        public Node(T value) {
            this.value = value;
        }

        //Backed by a release
        public void spNext(Node<T> next) {
            NEXT.set(this, next);
        }

        public Node<T> lpNext() {
            return (Node<T>) NEXT.get(this);
        }

        @Override
        public String toString() {
            var s = value == null ? null : value.toString();
            var n = next == null ? null : next.toString();
            return s + " -> " + n;
        }

        private static final VarHandle NEXT;

        static {
            try {
                var l = MethodHandles.lookup();
                NEXT = l.findVarHandle(Node.class, "next", Node.class);
            }catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    class ThreadInfo<T>  {
        final Operation op;
        final int idx;
        Node<T> node;
        public ThreadInfo(int idx, T t, Operation op) {
            this.op = op;
            this.idx = idx;
            if (t == null) this.node = (Node<T>) EMPTY;
            else this.node = new Node<>(t);
        }

        public Operation op() {
            return op;
        }

        public int idx() {
            return idx;
        }

        public Node<T> node() {
            return node;
        }
    }

    enum Operation {
        POP, PUSH
    }

    static void main() {
        var s = ClassLayout.parseInstance(ThreadInfo.class).toPrintable();
        System.out.println(s);
    }

}
