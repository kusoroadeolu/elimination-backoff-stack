package io.github.kusoroadeolu.fstack;


import io.github.kusoroadeolu.fstack.ConcurrentStack.ThreadInfo;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static io.github.kusoroadeolu.fstack.ConcurrentStack.Operation.POP;
import static io.github.kusoroadeolu.fstack.ConcurrentStack.Operation.PUSH;

/*
* An elimination based stack with adaptive backoff.
* Ideally most concurrent stacks are not scalable due to high contention at the head of the stack (during CAS failures)
* Elimination arrays attempt to handle this contention by using the inverse properties of pop and push on a stack
* For example given a stack
* A - B - C where A is the head of the stack
* Pushing a value D onto the stack then popping it doesn't change the structure of the stack*
*
* The core idea is quite simple, if a pop / push operation fails on the stack,
* they then try to eliminate inverse operations on the array, if that fails, they retry and so on
*
* We try to apply our operations on the given concurrent stack, if the operations on the stack fail after one attempt, we proceed to elimination
*
* We then try to obtain an idx from the collision array and swap our idx into that array so we can eliminate a waiting thread at that location.
* If this idx is EMPTY, that means no thread has ever accessed that location so we delay a bit (a park), before trying to cas to the stack again
* We get the thread info at that index, if the thread info is either null, the id at that location != the idx we read initially
* (a thread has eliminated the value at that location), or op == our op (conflicting ops), we park before trying to cas again
*
* After so, we try CAS our location from our info object to null, if we fail, another thread has collided with us and we can finish
*
* If we're a push operation, we try to cas our info object to the corresponding pop operations' location, if we fail, return false, otherwise we return true and end
*
* If we're a pop operation, we try cas the push operation info object to null, if we fail, we return
*
* If we fail in either collision, that means we were delayed or too slow and another object has collided with that pop operation,
*
* */
public class EliminationStack<T> {

    private final ConcurrentStack<T> stack;
    private final AtomicIntegerArray collisionArray;
    private final AtomicReferenceArray<ThreadInfo<T>> locations;
    private final ThreadLocal<int[]> id;
    private static final int NCPU = Runtime.getRuntime().availableProcessors();
    private static final int NCPU_HALVED = NCPU / 2;
    private static final int EMPTY = -1;

    public EliminationStack() {
        var counter = new AtomicInteger(0);
        stack = new TreiberStack<>();
        collisionArray = new AtomicIntegerArray(NCPU_HALVED); //Reduce by half to increase collision probability
        locations = new AtomicReferenceArray<>(NCPU);
        for (int i = 0; i < NCPU_HALVED; ++i) {
            collisionArray.set(i, EMPTY);
        }

        id = ThreadLocal.withInitial(() -> new int[]{counter.getAndIncrement()});
    }



    public void push(T val) {
        var s = stack;
        var idx = id.get()[0];

        ThreadInfo<T> ourInfo = new ThreadInfo<>(PUSH, idx, val);
        if (s.push(ourInfo)) return;
        var l = locations;
        var ca = collisionArray;
        l.setRelease(idx, ourInfo); //Should make node immediately visible


        while (true) {
            int pos = calculatePos(); //random array collision position
            int theirPos = locationToCollide(ca, idx, pos);  //Location we're colliding with
            if (theirPos != EMPTY) {
                var theirInfo = l.getAcquire(theirPos); //Use a get acquire read to ensure we always see the current node

                //The id check here is to ensure that another thread has not already swapped their thread info with this thread
                if (theirInfo != null && theirPos == theirInfo.idx() && theirInfo.op != PUSH) {
                    //Try to make ourselves unavailable
                    if (l.compareAndSet(idx, ourInfo, null)) {
                        //try collide now
                        if (tryCollide(ourInfo, theirInfo, l)) return;
                        else { //Else retry stack
                            if (s.push(ourInfo)) return;
                            continue; //Immediately try and collide again,
                        }

                    } else return; //If we can't make ourselves unavailable, another thread has collided with us, so we return

                }
            }

            int i = 0;
            while (++i < 10) Thread.onSpinWait();
            if (!l.compareAndSet(idx, ourInfo, null))
                return; //We've been collided with

            if (s.push(ourInfo)) return;
            l.setRelease(idx, ourInfo);
        }
    }

    public T pop() {
        var s = stack;
        var idx = id.get()[0];

        ThreadInfo<T> ourInfo = new ThreadInfo<>(POP, idx, null);
        if (s.pop(ourInfo)) return ourInfo.node().value;
        var l = locations;
        var ca = collisionArray;
        l.setRelease(idx, ourInfo); //Should make node immediately visible

        while (true) {
            int pos = calculatePos(); //random array collision position
            int lpos = locationToCollide(ca, idx, pos);  //Location we're colliding with
            if (lpos != EMPTY) {
                var theirInfo = l.getAcquire(lpos); //Use a get acquire read to ensure we always see the current node

                //The id check here is to ensure that another thread has not already swapped their thread info with this thread
                if (theirInfo != null && lpos == theirInfo.idx() && theirInfo.op() != POP) {
                    //Try to make ourselves unavailable
                    if (l.getAcquire(idx) != null && l.compareAndSet(idx, ourInfo, null)) {
                        //try collide now
                        if (tryCollide(ourInfo, theirInfo, l)) break;
                        else { //Else retry stack
                            if (s.pop(ourInfo)) break;
                            continue; //Immediately try and collide again,
                        }

                    }else {
                        popFinishCollide(ourInfo, l.getAcquire(idx), l);
                        break;
                    } //If we can't make ourselves unavailable, another thread has collided with us, so we finish colliding

                }
            }

            int i = 0;
            while (++i < 1000) Thread.onSpinWait(); //TODO add adaptive backoff
            if (l.getAcquire(idx) == null || !l.compareAndSet(idx, ourInfo, null)) {
                popFinishCollide(ourInfo, l.getAcquire(idx), l);
                break;
            }; //We've been collided with

            if (s.pop(ourInfo)) break;
            l.setRelease(idx, ourInfo);
        }

        return ourInfo.node().value;
    }

    int locationToCollide(AtomicIntegerArray collisionArray, int ourIdx, int pos) {
        return collisionArray.getAndSet(pos, ourIdx);
    }

    //Bounded to location NCPU / 2
    int calculatePos() {
        return Math.abs(ThreadLocalRandom.current().nextInt() % NCPU_HALVED);
    }

    void popFinishCollide(ThreadInfo<T> ours, ThreadInfo<T> theirs, AtomicReferenceArray<ThreadInfo<T>> l) {
        ours.node = theirs.node;
        soLocation(ours.idx(), l, null);
    }

    boolean tryCollide(ThreadInfo<T> ours, ThreadInfo<T> theirs, AtomicReferenceArray<ThreadInfo<T>> l) {
        return switch (ours.op) {
            case PUSH -> casLocation(theirs.idx(), l, theirs, ours); //Swap our info into their position
            case POP -> {
                if (casLocation(theirs.idx(), l, theirs, null)) { //Swap their info to null
                    ours.node = theirs.node;
                    spLocation(ours.idx(), l);  //Do we need to set our location to null? since we've already cas'd it to null earlier
                    yield true;
                }

                yield false;
            }
        };
    }

    @Override
    public String toString() {
        return stack.toString();
    }

    static <T>boolean casLocation(int idx, AtomicReferenceArray<ThreadInfo<T>> array , ThreadInfo<T> from, ThreadInfo<T> to) {
        return array.compareAndSet(idx, from, to);
    }


    // A plain write is alright here, since we've already made our location unavail
    static <T>void spLocation(int idx, AtomicReferenceArray<ThreadInfo<T>> array) {
        array.setPlain(idx, null);
    }

    static <T>void soLocation(int idx, AtomicReferenceArray<ThreadInfo<T>> array, ThreadInfo<T> to) {
        array.setRelease(idx, to);
    }



}
