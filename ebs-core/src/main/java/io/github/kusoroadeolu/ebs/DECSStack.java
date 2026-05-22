package io.github.kusoroadeolu.ebs;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;

import static io.github.kusoroadeolu.ebs.ConcurrentStack.Operation.POP;
import static io.github.kusoroadeolu.ebs.ConcurrentStack.Operation.PUSH;
import static io.github.kusoroadeolu.ebs.DECSStack.Status.*;

public class DECSStack<T> implements ConcurrentStack<T>{
    private final AtomicIntegerArray collisionArray;
    private final AtomicReferenceArray<ThreadNode<T>> locations;
    private final ThreadLocal<AdaptiveBackoffPolicy> policy;
    private static final int EMPTY = -1;
    private final CentralStack<T> stack;

    public DECSStack(int noThreads, int collisionArraySize, WaitStrategy strategy) {
        var counter = new AtomicInteger(0);
        stack = new CentralStack<>(); //A simple treiber stack
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
        tryEliminate(ourNode, s, p);
        return true;

    }

    @Override
    public T pop() {
        var s = stack;
        var p = policy.get();
        var idx = p.idx();
        ThreadNode<T> ourNode = new ThreadNode<>(idx, POP, null);
        if (s.pop(ourNode)) return ourNode.node.value;
        tryEliminate(ourNode, s, p);
        return ourNode.node.value;
    }

    void tryEliminate(ThreadNode<T> ourNode, CentralStack<T> s, AdaptiveBackoffPolicy p) {
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
            int theirIdx = locationToCollide(ca, idx, pos);  //Location we're colliding with
            if (theirIdx != EMPTY) {
                var theirNode = l.getAcquire(theirIdx); //Use an acquire read to ensure we always see the current node

                //The id check here is to ensure that another thread has not already swapped their thread info with this thread and we arent extending ourselves
                if (theirNode != null && theirIdx == theirNode.idx() && theirIdx != idx) {
                    //Try to make ourselves unavailable
                    if (l.compareAndSet(idx, ourNode, null)) {
                        //try to collide now
                        if (activeCollide(ourNode, theirNode, l)) break;
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
                        if (passiveCollide(ourNode, l)) break;
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
                if (passiveCollide(ourNode, l)) return; //If we fail to passively collide, just try to access the stack again
            }

            boolean succeed = op == PUSH ? s.multiPush(ourNode) : s.multiPop(ourNode);
            if (succeed) return;
            l.setRelease(idx, ourNode); //Rewrite our info
        }
        wp.increaseWait();
    }

    int locationToCollide(AtomicIntegerArray arr, int ourIdx, int pos) {
        return arr.getAndSet(pos, ourIdx);
    }

    boolean activeCollide(ThreadNode<T> ours, ThreadNode<T> theirs, AtomicReferenceArray<ThreadNode<T>> ara) {
        var op = ours.operation;

        if (ara.compareAndSet(theirs.idx(), theirs, ours)) {
            if (op == theirs.operation) {
                collide(ours, theirs);
                return false;
            } else {
                multiEliminate(ours, theirs);
                return true;
            }
        }

        return false;
    }

    boolean passiveCollide(ThreadNode<T> ours, AtomicReferenceArray<ThreadNode<T>> ara) {
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
                    ours.soInit();
                    return false;
                }
                LockSupport.parkNanos(1);

            }
        }
    }


    void multiEliminate(ThreadNode<T> ours, ThreadNode<T> theirs) {
        var aCurr = ours;
        var pCurr = theirs;
        var op = aCurr.operation;

        while (aCurr != null && pCurr != null) {
            if (op == POP) aCurr.node = pCurr.node;
            else pCurr.node = aCurr.node;

            aCurr.soFinished();
            pCurr.soFinished(); //Set release makes node instantly visible

            --aCurr.size; --pCurr.size;
            aCurr = aCurr.next;
            pCurr = pCurr.next;
        }

        if (aCurr != null) {
            aCurr.size = ours.size;
            aCurr.last = ours.last;
            aCurr.soRetry(); //Happens before on all previous next and size writes
        } else if (pCurr != null) {
            pCurr.size = theirs.size;
            pCurr.last = theirs.last;
            pCurr.soRetry();
        }

    }


    void collide(ThreadNode<T> ours, ThreadNode<T> theirs) {
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

        void soInit() {
            STATUS.setRelease(this, INIT);
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




    static class CentralStack<T> {
        private volatile ConcurrentStack.Node<T> head;

        //For quick tets
        boolean push(T i) {
            var node = new ConcurrentStack.Node<>(i);
            var h = loHead();
            node.spNext(h);
            return HEAD.compareAndSet(this, h, node);
        }


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
            if (!pushed) { //Here ensure we detach t from the stack, so we don't have some transience issues
                t.spNext(null);
                return false;
            }

            //Otherwise we reiterate the list marking everyone as pushed

            var c = tn.next; //No need to mark ourselves, as we'll just
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
                    //Apply from t
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
                    if (tn != curr) { //We've applied our node and probably others, handoff
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
                HEAD = l.findVarHandle(CentralStack.class, "head", ConcurrentStack.Node.class);
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



