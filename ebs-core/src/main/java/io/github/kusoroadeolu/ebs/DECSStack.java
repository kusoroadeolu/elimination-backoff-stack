package io.github.kusoroadeolu.ebs;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static io.github.kusoroadeolu.ebs.ConcurrentStack.Operation.POP;
import static io.github.kusoroadeolu.ebs.ConcurrentStack.Operation.PUSH;
import static io.github.kusoroadeolu.ebs.DECSStack.Status.*;


//Based on the paper https://arxiv.org/pdf/1106.6304
/*
* A variant of the EB stack.
* This stack aims to fix the issue of the elimination backoff stack.
* This stack combines the flat combing paradigm when similar operations collide.
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
* Communication between threads when handling the combining logic is done between a status field. Mainly using the release and acquire memory modes
* Invariant: A node marked as finished should always see the node swapped by the combining thread
*
*
* Operations on the stack are done in batches. Two main scenarios exist for batch push operations. We stick with the first option
* 1. The combiner tries to batch append a list of combining nodes to the stack
* 2. The combiner continuously walk down each node trying to cas them to the head of the list, we hand off to the thread whose node we fail at.
*
* For pop operations we walk down the stack up to the length of the combiner's linked list size. Stopping at the tail of the stack.
* If we to detach those nodes up the head of the stack, but the detached stack is not as long as the combining list,
* we hand off the combining task to the thread whose node we failed to apply a pop operation to
*
*
* A potential improvement can be made here to alleviate gc pressure.
* 1. We can use an array based approach to build the thread node list rather than holding a reference to a next and last ref.
* This improves cache locality and eliminates pointer chasing
* */
public class DECSStack<T> implements ConcurrentStack<T>{
    private final AtomicIntegerArray collisionArray;
    private final AtomicReferenceArray<ThreadNode<T>> locations;
    private final ThreadLocal<AdaptiveBackoffPolicy> policy;
    private static final int EMPTY = -1;
    private final MultiStack<T> stack;

    public DECSStack(int noThreads, int collisionArraySize, WaitStrategy strategy) {
        var counter = new AtomicInteger(0);
        stack = new MultiStack<>(); //A simple treiber stack
        collisionArray = new AtomicIntegerArray(collisionArraySize); //Reduce by half to increase collision probability
        locations = new AtomicReferenceArray<>(noThreads);
        for (int i = 0; i < collisionArraySize; ++i) {
            collisionArray.set(i, EMPTY);
        }

        policy = ThreadLocal.withInitial(() -> new AdaptiveBackoffPolicy(strategy, counter.getAndIncrement(), collisionArraySize));
    }

    public DECSStack(WaitStrategy strategy) {
        var ncpu = Runtime.getRuntime().availableProcessors();
        this(ncpu, ncpu, strategy);
    }


    @Override
    public boolean push(T t) {
        var s = stack;
        var p = policy.get();
        var idx = p.idx();
        ThreadNode<T> ourNode = new ThreadNode<>(idx, PUSH, t);
        if (s.push(ourNode)) return true;
        doEliminate(ourNode, s, p);
        return true;

    }

    @Override
    public T pop() {
        var s = stack;
        var p = policy.get();
        var idx = p.idx();
        ThreadNode<T> ourNode = new ThreadNode<>(idx, POP, null);
        if (s.pop(ourNode)) return ourNode.node.value;
        doEliminate(ourNode, s, p);
        return ourNode.node.value;
    }

    void doEliminate(ThreadNode<T> ourNode, MultiStack<T> s, AdaptiveBackoffPolicy p) {
        var idx = p.idx();
       var wp = p.waitPolicy();
        var rp = p.rangePolicy();
        var l = locations;
        var ca = collisionArray;
        var op = ourNode.operation;
        ourNode.setLast(ourNode);
        l.setRelease(idx, ourNode); //Should make node and last write immediately visible


        while (true) {
            int pos = rp.calculatePos(); //random array collision position
            int theirIdx = getCollisionIndex(ca, idx, pos);  //Location we're colliding with
            if (theirIdx != EMPTY) {
                var theirNode = l.getAcquire(theirIdx); //Use an acquire read to ensure we always see the current node

                //The id check here is to ensure that another thread has not already swapped their thread info with this thread, and we aren't colliding with ourselves
                if (theirNode != null && theirIdx == theirNode.idx() && theirIdx != idx) {
                    //Try to make ourselves unavailable
                    if (l.compareAndSet(idx, ourNode, null)) {
                        //try to collide now
                        if (collide(ourNode, theirNode, l)) break;
                        else { //Else retry stack
                            boolean succeed = op == PUSH ? s.multiPush(ourNode) : s.multiPop(ourNode);
                            if (succeed) break;
                            rp.recordCollisionFailure(); //Failed to collide increase record range and decrease wait count
                             wp.decreaseWait();
                            l.setRelease(idx, ourNode);
                            continue; //Immediately try and collide again,
                        }

                    } else {
                        //If we can't make ourselves unavailable, another thread has collided with us, so we passive collide
                        if (tryFinishCollide(ourNode, l)) break;
                        l.setRelease(idx, ourNode);
                        continue;
                    }

                } else {
                    if (theirNode == null) {
                        rp.recordThreadAbsence(); //On thread absence
                    }

                }
            }

            wp.idle();

            if (l.getAcquire(idx) == null || !l.compareAndSet(idx, ourNode, null)){
                if (tryFinishCollide(ourNode, l)) return; //If we fail to passively collide, just try to access the stack again
            }

            boolean succeed = op == PUSH ? s.multiPush(ourNode) : s.multiPop(ourNode);
            if (succeed) return;
            l.setRelease(idx, ourNode); //Re write our info
        }
        wp.increaseWait();
    }

    int getCollisionIndex(AtomicIntegerArray arr, int ourIdx, int pos) {
        return arr.getAndSet(pos, ourIdx);
    }

    boolean collide(ThreadNode<T> ours, ThreadNode<T> theirs, AtomicReferenceArray<ThreadNode<T>> ara) {
        var op = ours.operation;

        if (ara.compareAndSet(theirs.idx(), theirs, ours)) {
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

    boolean tryFinishCollide(ThreadNode<T> ours, AtomicReferenceArray<ThreadNode<T>> ara) {
        var n = ara.getAcquire(ours.idx());
        ara.setRelease(ours.idx(), null);
        var op = ours.operation;


        if (n.operation != op) {
            if (op == POP) ours.node = n.node; //Made visible by the cas to our idx
            return true;
        } else {
            while (true) {
                var s = ours.loStatus();
                if (s == FINISHED) return true;
                else if (s == RETRY) {
                    ours.spInit();
                    return false;
                }

            }
        }
    }


    void multiEliminate(ThreadNode<T> ours, ThreadNode<T> theirs) {
        var ourCurr = ours;
        var theirCurr = theirs;
        var op = ourCurr.operation;

        while (ourCurr != null && theirCurr != null) {
            if (op == POP) ourCurr.node = theirCurr.node;
            else theirCurr.node = ourCurr.node;

            ourCurr.soFinished();
            theirCurr.soFinished(); //Set release makes node instantly visible

            --ourCurr.size; --theirCurr.size;
            ourCurr = ourCurr.next;
            theirCurr = theirCurr.next;
        }

        //Invariants: Theirs should never be null, so we can never hand off to ourselves
        // Swapped nodes should always be visible, if a node is marked as finished

        //Handoff
        if (ourCurr != null) {
            ourCurr.size = ours.size;
            ourCurr.last = ours.last;
            ourCurr.soRetry(); //Happens before on all next and size writes. Basically the node we handed off to will always see all descendant nodes
        } else if (theirCurr != null) {
            theirCurr.size = theirs.size;
            theirCurr.last = theirs.last;
            theirCurr.soRetry();
        }


    }



    void combine(ThreadNode<T> ours, ThreadNode<T> theirs) {
        var l = ours.last;
        if (ours.operation == PUSH) {
            l.node.spNext(theirs.node);
        }

        l.next = theirs;
        ours.last = theirs.last;
        ours.size += theirs.size;
    }

    public List<T> toList() {
        var h = stack.loHead();
        List<T> ls = new ArrayList<>();
        while (h != null){
            ls.add(h.value);
            h = h.loNext();
        }

        return ls;
    }


    static class ThreadNode<T> {
        final int idx;
        ConcurrentStack.Node<T> node;
        final Operation operation;
        volatile Status status = INIT;
        ThreadNode<T> next; //During handoff, all next writes will be made visible by a release status
        ThreadNode<T> last;
        int size = 1;

        public ThreadNode(int threadId, Operation operation, T t) {
            idx = threadId;
            this.operation = operation;
            if (operation == POP) this.node = (Node<T>) Node.EMPTY;
            else this.node = new Node<>(t);
        }


        public int idx() {
            return idx;
        }

        void soFinished() {
            STATUS.setRelease(this, FINISHED);
        }

        void spInit() {
            STATUS.set(this, INIT);
        }


        Status loStatus() {
          return (Status) STATUS.getAcquire(this);
        }

        void setLast(ThreadNode<T> last) {
            this.last = last;
        }

        void soRetry() {
            STATUS.setRelease(this, Status.RETRY);
        }
    }

    enum Status {
        INIT, RETRY, FINISHED
    }

    static class MultiStack<T> {
        private volatile ConcurrentStack.Node<T> head;

        public boolean push(ThreadNode<T> tn) {
            var node = tn.node;
            var h = loHead();
            node.spNext(h);
            return HEAD.compareAndSet(this, h, node);
        }

        public boolean pop(ThreadNode<T> tn) {
            var h = loHead();
            if (h == null) return true;

            var next = h.lpNext(); //Backed by the head acquire read

            boolean popped = HEAD.compareAndSet(this, h, next);
            if (popped) tn.node = h;
            return popped;
        }


        //Our thread node is always the head
        public boolean multiPush(ThreadNode<T> tn) {
            ConcurrentStack.Node<T> newHead = tn.node;
            ConcurrentStack.Node<T> t = tn.last.node;
            var h = loHead();
            t.spNext(h);
            boolean pushed = HEAD.compareAndSet(this, h, newHead);
            if (!pushed) { //Here ensure we detach t from the stack
                t.spNext(null);
                return false;
            }

            //Otherwise we reiterate the list marking everyone as pushed

            var c = tn.next; //No need to mark ourselves, as we'll just exit after this
            while (c != null) {
                c.soFinished();
                c = c.next;
            }

            return true;
        }


        public boolean multiPop(ThreadNode<T> tn) {
            int len = tn.size;
            ConcurrentStack.Node<T> h;
            var curr = tn;

                while ((h = loHead()) == null && curr != null) {
                    curr.soFinished();
                    curr = curr.next;
                    --len;
                }

                if (curr == null) return true;
                //Otherwise we reiterate the list marking everyone as pushed

                int i = 1;
                var n = h.loNext();
                for (;  i < len && n != null; ++i) {
                    n = n.loNext();
                }

                if (HEAD.compareAndSet(this, h, n)) {
                    //Apply from curr
                    while (curr != null) {
                        if (h != null) {
                            curr.node = h;
                            h = h.lpNext();
                        }

                        curr.soFinished();
                        curr = curr.next;
                    }

                    return true;
                } else {
                    if (tn != curr) { //We've applied our node and possibly others, handoff to the next unapplied node
                        curr.size = len;
                        curr.last = tn.last;
                        curr.soRetry();
                        return true;
                    } else return false; //We didn't clear any nodes at all
                }

        }

        public String toString() {
            return head.toString();
        }

        public ConcurrentStack.Node<T> loHead() {
            return (ConcurrentStack.Node<T>) HEAD.getAcquire(this);
        }

        private static final VarHandle HEAD;

        static {
            try {
                var l = MethodHandles.lookup();
                HEAD = l.findVarHandle(MultiStack.class, "head", ConcurrentStack.Node.class);
            }catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }


    private static final VarHandle STATUS;

    static {
        try {
            var l = MethodHandles.lookup();
            STATUS = l.findVarHandle(ThreadNode.class, "status", Status.class);
        }catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}



