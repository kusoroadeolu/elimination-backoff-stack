package io.github.kusoroadeolu.fstack;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import static io.github.kusoroadeolu.fstack.ConcurrentStack.Node.EMPTY;


public interface ConcurrentStack<T> {
    boolean push(ThreadInfo<T> info);
    boolean pop(ThreadInfo<T> info);

    @SuppressWarnings("unchecked")
    class Node<T> {
        final T value;
        private volatile Node<T> next;
        static final Node<?> EMPTY = new Node<>(null);

        public Node(T value) {
            this.value = value;
        }

        //Backed by volatile write
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


    static class ThreadInfoPad {
        private long l1, l2, l3, l4, l5, l6, l7, l8, l9;
    }

    static class ThreadInfoFields<T> extends ThreadInfoPad{
        final Operation op;
        final int idx;
        Node<T> node;

        ThreadInfoFields(Operation op, int idx, T val) {
            this.op = op;
            this.idx = idx;
            if (val == null) this.node = (Node<T>) EMPTY;
            else this.node = new Node<>(val);
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

    static class ThreadInfo<T> extends ThreadInfoFields<T> {
        private long l1, l2, l3, l4, l5, l6, l7, l8, l9;

        public ThreadInfo(Operation op, int idx, T t) {
            super(op, idx, t);
        }
    }

    enum Operation {
        POP, PUSH
    }



}
