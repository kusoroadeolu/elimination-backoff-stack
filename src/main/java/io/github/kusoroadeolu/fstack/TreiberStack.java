package io.github.kusoroadeolu.fstack;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public class TreiberStack<T> implements ConcurrentStack<T>{

    private volatile Node<T> head;

    @Override
    public boolean push(ThreadInfo<T> info) {
        var node = info.node;
        var h = loHead();
        node.spNext(h);
        return HEAD.compareAndSet(this, h, node);
    }

    public boolean pop(ThreadInfo<T> info) {
        var h = loHead();
        if (h == null) return true;

        var next = h.lpNext(); //Backed by acquire read up there

       boolean popped = HEAD.compareAndSet(this, h, next);
       if (popped) info.node = h;
       return popped;
    }

    @Override
    public String toString() {
        return head.toString();
    }

    private Node<T> loHead() {
        return (Node<T>) HEAD.getAcquire(this);
    }

    private static final VarHandle HEAD;

    static {
        try {
            var l = MethodHandles.lookup();
            HEAD = l.findVarHandle(TreiberStack.class, "head", Node.class);
        }catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
