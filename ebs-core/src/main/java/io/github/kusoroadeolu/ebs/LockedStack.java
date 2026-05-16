package io.github.kusoroadeolu.ebs;

public class LockedStack<T> implements ConcurrentStack<T> {
    private final Object lock;
    private Node<T> head;

    public LockedStack() {
        this.lock = new Object();
    }

    @Override
    public boolean push(T t) {
        var node = new Node<>(t);
        synchronized (lock) {
            node.next = head;
            head = node;
        }

        return true;
    }

    @Override
    public T pop() {
        Node<T> h;
        synchronized (lock) {
            h = head;
            if (h == null) return null;
            head = h.next;
        }

        return h.item;
    }

    private static class Node<T> {
        final T item;
        Node<T> next;

        public Node(T item) {
            this.item = item;
        }
    }
}
