package io.github.kusoroadeolu.ebs;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public class TreiberStack<T> implements ConcurrentStack<T>{
    private volatile Node<T> head;

    public boolean push(T t) {
        var node = new Node<>(t);
        Node<T> h;
        do {
            h = loHead();
            node.spNext(h); //Backed by acquire write
        }while (!HEAD.compareAndSet(this, h, node));

         return true;
    }

    public T pop() {
        Node<T> h;
        Node<T> next;
        do {
            h = loHead();
            if (h == null) return null;
            next = h.lpNext();
        } while (!HEAD.compareAndSet(this, h, next));
        return h.value;
    }

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
