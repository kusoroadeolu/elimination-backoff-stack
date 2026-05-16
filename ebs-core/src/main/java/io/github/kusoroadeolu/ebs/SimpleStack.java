package io.github.kusoroadeolu.ebs;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;


//A treiber stack adapted for the elim stack
@SuppressWarnings("unchecked")
class SimpleStack<T> {

    private volatile ConcurrentStack.Node<T> head;
    public boolean push(ConcurrentStack.ThreadInfo<T> info) {
        var node = info.node;
        var h = loHead();
        node.spNext(h);
        return HEAD.compareAndSet(this, h, node);
    }

    public boolean pop(ConcurrentStack.ThreadInfo<T> info) {
        var h = loHead();
        if (h == null) return true;

        var next = h.lpNext(); //Backed by acquire read up there

       boolean popped = HEAD.compareAndSet(this, h, next);
       if (popped) info.node = h;
       return popped;
    }

    public String toString() {
        return head.toString();
    }

    private ConcurrentStack.Node<T> loHead() {
        return (ConcurrentStack.Node<T>) HEAD.getAcquire(this);
    }

    private static final VarHandle HEAD;

    static {
        try {
            var l = MethodHandles.lookup();
            HEAD = l.findVarHandle(SimpleStack.class, "head", ConcurrentStack.Node.class);
        }catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
