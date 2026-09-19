package io.github.kusoroadeolu.ebs;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static io.github.kusoroadeolu.ebs.ConcurrentStack.Operation.POP;
import static io.github.kusoroadeolu.ebs.ConcurrentStack.Operation.PUSH;
import static io.github.kusoroadeolu.ebs.EliminationCombiningStack.Status.*;


//Based on the paper https://arxiv.org/pdf/1106.6304 (Dynamic Elimination Combining Stack)
/*
 * A variant of the EB stack.
 * This stack aims to fix the issue of the elimination backoff stack.
 * This stack combines the flat combing paradigm by allowing similar ops to batch together when they collide
 *
 * Unlike the EB stack, if similar ops collide.
 *
 * Each thread maintains a local thread node field which contains a next thread node ptr, a stack node ptr, a last node ptr and a size ptr
 * The thread which initiated the collision, swaps itself to the passive collider's location
 * and links the passive colliders thread node to its last node.
 *
 * The remainder of the core algorithm largely remains unchanged, however we do need to address the fact similar ops can collide.
 * So when we successfully collide with an inverse op.
 * We walk down from our node eliminating each op until we reach the tail of our node or the node we collided with
 * If we failed to fully eliminate all nodes from either ours or their node, we hand off the combining task to the first node we failed to eliminate
 *
 * The main invariant here is that a thread with the task of combining other threads operations, the reference node which it holds should always be its own.
 * Easily said, its node should always be the head of the combining linked list
 *
 * Communication between threads when handling the combining logic is done between a status field.
 * Mainly using release and acquire memory modes
 * The status enum has 3 main classes
 *
 * INIT (default state), RETRY(you're now the combiner), FINISHED(your work has been done you can leave)
 *
 * Invariants: A node marked as finished should always see the node swapped by the combining thread
 * A node marked to retry, should always see all "next" writes and the latest "size" write by the old combining thread
 * A combining node, should always see all "next" writes and the latest "size" write by the thread it clashed with (this is done using size)

 *
 * Operations on the stack are done in batches. Two main scenarios exist for batch push operations. We stick with the first option
 * 1. The combiner tries to batch append a list of combining nodes to the stack
 * 2. The combiner continuously walk down each node trying to cas them to the head of the list, we hand off to the thread whose node we fail at.
 *
 * For pop operations we walk down the stack up to the length of the combiner's linked list size. Stopping at the tail of the stack.
 * If we to detach those nodes up the head of the stack, but the detached stack is not as long as the combining list,
 * we hand off the combining task to the thread whose node we failed to apply a pop operation to
 *
 * */
public class EliminationCombiningStack<T> implements ConcurrentStack<T>{
    private final ArenaObject<T>[] arena;
    private final MultiStack<T> stack;
    private static final int MAX_SPINS = 1024;
    private static final int SPINS_PER_SLOT = 128;
    private static final int BACKOFF_SPINS = 32;
    private static final int MAX_STEPS = 8;
    private final int mask;

    public EliminationCombiningStack() {
        stack = new MultiStack<>(); //A simple treiber stack
        int arenaSize = ceilingNextPowerOfTwo(Runtime.getRuntime().availableProcessors());
        arena = new ArenaObject[arenaSize];

        for (int i = 0; i < arenaSize; ++i) arena[i] = new ArenaObject<>();

        mask = arenaSize - 1;
    }


    @Override
    public boolean push(T t) {
        ThreadNode<T> ours = new ThreadNode<>(PUSH, new Node<>(t));
        var s = stack;
        var arena = this.arena;
        if (s.push(ours)) return true;

        int startIndex = ThreadLocalRandom.current().nextInt();

        for (;;) {
            if (scanAndEliminate(ours, arena, startIndex) || s.multiPush(ours) || awaitElimination(startIndex, ours) || s.multiPush(ours)) return true;
        }

    }

    @Override
    public T pop() {
        var s = stack;
        ThreadNode<T> ours = new ThreadNode<>(POP,  (Node<T>) Node.EMPTY);
        var arena = this.arena;
        if (s.pop(ours)) return ours.node.value;

        int startIndex = ThreadLocalRandom.current().nextInt();
        for (;;) {
            if (scanAndEliminate(ours, arena, startIndex) || s.multiPop(ours) || awaitElimination(startIndex, ours) || s.multiPop(ours)) return ours.node.value;
        }

    }



    //Here we aren't visible to other threads so we're free to try and force collisions
    boolean scanAndEliminate(ThreadNode<T> ours, ArenaObject<T>[] arena, int start) {
        int mask = this.mask;
        int length = mask + 1;
        for (int step = 0; step < length; ++step) {
            int index = (start + step) & mask;
            var object = arena[index];
            var theirs = object.laNode();
            if (theirs != null && collide(ours, theirs, object)) return true;
        }

        return false;
    }

    boolean awaitElimination(int startIndex, ThreadNode<T> ours) {
        var arena = this.arena;
        int spins = 0;
        int mask = this.mask;
        outer: for (int steps = 0; steps < MAX_STEPS && spins < MAX_SPINS; ++steps) {
            int index = (steps + startIndex) & mask;
            var object = arena[index];
            ThreadNode<T> node;
            if ((node = object.loNode()) == null && object.casNode(null, ours)) {
                int slotSpins = 0;
                for (int backoffSpins = 0;;) {
                    ThreadNode<T> found = object.laNode();

                    if (found != ours) return awaitStatus(ours);

                    if (slotSpins >= SPINS_PER_SLOT) {
                        if (object.casNode(ours, null)) {
                            spins += slotSpins;
                            continue outer;
                        } else return awaitStatus(ours);
                    }

                    while (backoffSpins++ < BACKOFF_SPINS) Thread.onSpinWait();

                    slotSpins += backoffSpins;
                }
            } else if (node != null && collide(ours, node, object)) return true;
        }


        return false;
    }


    boolean collide(ThreadNode<T> ours, ThreadNode<T> theirs ,ArenaObject<T> object) {
        var op = ours.operation;

        if (object.casNode(theirs, null)) {
            if (op == theirs.operation) {
                combine(ours, theirs);
                return false;
            } else {
                multiEliminate(ours, theirs);
                return true;
            }
        }

        return false;
    }

    boolean awaitStatus(ThreadNode<T> ours) {
        int step = 0;
        while (true) {
            var s = ours.laStatus();
            if (s == FINISHED) return true;
            else if (s == RETRY) {
                ours.spInit();
                return false;
            }

            step = Backoff.snooze(step);
        }
    }

    static int ceilingNextPowerOfTwo(int x) {
        return 1 << (Integer.SIZE - Integer.numberOfLeadingZeros(x - 1));
    }


    void multiEliminate(ThreadNode<T> ours, ThreadNode<T> theirs) {
        var ourCurrentNode = ours;
        var theirCurrentNode = theirs;
        var operation = ourCurrentNode.operation;

        while (ourCurrentNode != null && theirCurrentNode != null) {

            if (operation == POP) ourCurrentNode.node = theirCurrentNode.node;
            else theirCurrentNode.node = ourCurrentNode.node; //swap nodes

            ourCurrentNode.srFinished();
            theirCurrentNode.srFinished();

            ourCurrentNode.decrementSize();
            theirCurrentNode.decrementSize();

            ourCurrentNode = ourCurrentNode.next;
            theirCurrentNode = theirCurrentNode.next;
        }

        //Invariants: Theirs should never be null, so we can never hand off to ourselves
        // Swapped nodes should always be visible, if a node is marked as finished

        //Handoff
        if (ourCurrentNode != null) {
            ourCurrentNode.setSize(ours.size());
            ourCurrentNode.last = ours.last;
            ourCurrentNode.srRetry(); //Happens before on all next and size writes. Basically the node we handed off to will always see all descendant nodes
        }

        if (theirCurrentNode != null) {
            theirCurrentNode.setSize(theirs.size());
            theirCurrentNode.last = theirs.last;
            theirCurrentNode.srRetry();
        }


    }



    void combine(ThreadNode<T> ours, ThreadNode<T> theirs) {
        var l = ours.last;
        int theirSize = theirs.size();
        if (ours.operation == PUSH) {
            l.node.spNext(theirs.node);
        }

        l.next = theirs;
        ours.last = theirs.last;
        ours.setSize(ours.size() + theirSize);
    }

    public List<T> toList() {
        var h = stack.laHead();
        List<T> ls = new ArrayList<>();
        while (h != null){
            ls.add(h.value);
            h = h.lpNext();
        }

        return ls;
    }


    static class ThreadNode<T> {
        Node<T> node;
        final Operation operation;
        ThreadNode<T> next; //During handoff, all next writes will be made visible by a release status
        ThreadNode<T> last;
        int size;
        volatile Status status;

        public ThreadNode(Operation operation, Node<T> node) {
            this.operation = operation;
            this.node = node;
            last = this;
            size = 1;
            status = NONE;
        }

        void srFinished() {
            STATUS.setRelease(this, FINISHED);
        }

        void spInit() {
            STATUS.set(this, NONE);
        }


        void decrementSize() {
            size--;
        }

        void setSize(int i) {
            size = i;
        }

        int size() {
            return size;
        }


        Status laStatus() {
            return (Status) STATUS.getAcquire(this);
        }

        void srRetry() {
            STATUS.setRelease(this, Status.RETRY);
        }
    }

    enum Status {
        NONE, RETRY, FINISHED
    }

    static class MultiStack<T> {
        private volatile Node<T> head;

        public boolean push(ThreadNode<T> threadNode) {
            var node = threadNode.node;
            var h = laHead();
            node.spNext(h);
            return HEAD.compareAndSet(this, h, node);
        }

        public boolean pop(ThreadNode<T> tn) {
            var h = laHead();
            if (h == null) return true;

            var next = h.lpNext(); //Backed by the head acquire read

            boolean popped = HEAD.compareAndSet(this, h, next);
            if (popped) tn.node = h;
            return popped;
        }


        //Our thread node is always the head
        public boolean multiPush(ThreadNode<T> node) {
            Node<T> newHead = node.node;
            Node<T> last = node.last.node;

            var h = laHead();
            last.spNext(h);

            if (!HEAD.compareAndSet(this, h, newHead)) {
                last.spNext(null); //ensure we detach t from the stack
                return false;
            }

            //Iterate the local list marking everyone as pushed

            var curr = node.next; //No need to mark ourselves, as we'll just exit after this

            while (curr != null) {
                curr.srFinished();
                curr = curr.next;
            }

            return true;
        }



        // Either atomic or not (similar to what multi push guarantees)
        public boolean multiPop(ThreadNode<T> node) {
            int size = node.size();

            Node<T> head;
            var curr = node;

            if ((head = laHead()) == null) {
                while (curr != null) {
                    curr.srFinished();
                    curr = curr.next;
                }

                return true;
            }



            var sweep = head;
            for (int i = 1; i < size && sweep != null; ++i) {
                sweep = sweep.lpNext();
            }

            if (HEAD.compareAndSet(this, head, sweep)) {

                while (curr != null) {

                    if (head != null) {
                        curr.node = head;
                        head = head.lpNext();
                    }

                    curr.srFinished();
                    curr = curr.next;
                }

                return true;
            }


            return false;

        }

        public String toString() {
            return head.toString();
        }

        public Node<T> laHead() {
            return (Node<T>) HEAD.getAcquire(this);
        }

        private static final VarHandle HEAD;

        static {
            try {
                var l = MethodHandles.lookup();
                HEAD = l.findVarHandle(MultiStack.class, "head", Node.class);
            }catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }


    private static final VarHandle STATUS;
    private static final VarHandle NODE;

    static {
        try {
            var l = MethodHandles.lookup();
            STATUS = l.findVarHandle(ThreadNode.class, "status", Status.class);
            NODE = l.findVarHandle(ArenaObjectFields.class, "node", ThreadNode.class);
        }catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static class ArenaObjectLPad {
        long l1, l2, l3, l4, l5, l6, l7, l8;
    }

    static class ArenaObjectFields<T> extends ArenaObjectLPad {
        ThreadNode<T> node;

        ThreadNode<T> laNode() {
            return (ThreadNode<T>) NODE.getAcquire(this);
        }

        ThreadNode<T> loNode() {
            return (ThreadNode<T>) NODE.getOpaque(this);
        }

        boolean casNode(ThreadNode<T> from,  ThreadNode<T> to) {
            return NODE.compareAndSet(this, from, to);
        }
    }

    static class ArenaObject<T> extends ArenaObjectFields<T>{
        long l1, l2, l3, l4, l5, l6, l7;

    }



}


