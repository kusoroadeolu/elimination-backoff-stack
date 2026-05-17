package io.github.kusoroadeolu.ebs;


import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;

import static io.github.kusoroadeolu.ebs.ConcurrentStack.Operation.POP;
import static io.github.kusoroadeolu.ebs.ConcurrentStack.Operation.PUSH;

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


/*
* Some extra notes
The collision array alone, in a sense helps and doesn't help, though context matters,
*  if there's too much contention on the collision array(a specific index in the array gets hammered
* a lot, meaning threads overlap a lot so only few threads make progress while others wait idly, so other indexes are just left empty)
* or the workload is asymmetric (higher push/pop operations than its counterpart)
* and little on the stack, we're basically reinventing the same problem just more complex.
* The collision array helps in the sense when there's contention on the head of the stack and rather than just backing off and doing nothing useful,
* we shift that contention to a different structure where threads can possibly make progress rather than waiting idly
* */

// Based on the paper: http://www.inf.ufsc.br/~dovicchi/pos-ed/pos/artigos/p206-hendler.pdf

//This version deviates from the paper a bit
// Rather than a shared collision array, both push and pop operations have exclusive collision arrays
// To prevent colliding to frequently with similar ops, push ops only write to the push array
// and read from the pop array and vice versa. Later one we try to clear the position we wrote to using a cas,
// to prevent clearing threads that might have written to that position since we last wrote
// While this reduces frequent ops collision by almost 90% (from 122k to 3k),
// it does come at the cost of an extra volatile write and acquire read in and out the loop
public class EliminationStack<T> implements ConcurrentStack<T>{

    private final SimpleStack<T> stack;
    private final AtomicIntegerArray popCollisionArray;
    private final AtomicIntegerArray pushCollisionArray;
    private final AtomicReferenceArray<ThreadInfo<T>> locations;
    private final ThreadLocal<AdaptiveBackoffPolicy> policy;
    private static final int NCPU = Runtime.getRuntime().availableProcessors();
    private static final int NCPU_HALVED = NCPU / 2;
    private static final int EMPTY = -1;

    public EliminationStack(WaitStrategy strategy) {
        var counter = new AtomicInteger(0);
        stack = new SimpleStack<>(); //A simple treiber stack
        popCollisionArray = new AtomicIntegerArray(NCPU_HALVED); //Reduce by half to increase collision probability
        pushCollisionArray = new AtomicIntegerArray(NCPU_HALVED); //Reduce by half to increase collision probability

        locations = new AtomicReferenceArray<>(NCPU);

        for (int i = 0; i < NCPU_HALVED; ++i) {
            popCollisionArray.set(i, EMPTY);
        }

        for (int i = 0; i < NCPU_HALVED; ++i) {
            pushCollisionArray.set(i, EMPTY);
        }

        policy = ThreadLocal.withInitial(() -> new AdaptiveBackoffPolicy(strategy, counter.getAndIncrement()));
    }

    public boolean push(T val) {
        var s = stack;
        var p = policy.get();
        var idx = p.idx;
        var wp = p.waitPolicy();
        var rp = p.rangePolicy();
        var m = p.metrics;

        ThreadInfo<T> ourInfo = new ThreadInfo<>(idx, val, PUSH);
        if (s.push(ourInfo)) {
            ++m.stackSuccesses;
            return true;
        }
        var l = locations;
        var ourArr = pushCollisionArray;
        var theirArr = popCollisionArray;
        l.setRelease(idx, ourInfo); //Should make node immediately visible
        int pos;

        while (true) {
            pos = rp.calculatePos(); //random array collision position
            int theirIdx = locationToCollide(ourArr, theirArr, idx, pos);  //Location we're colliding with
            if (theirIdx != EMPTY) {
                var theirInfo = l.getAcquire(theirIdx); //Use an acquire read to ensure we always see the current node

                //The id check here is to ensure that another thread has not already swapped their thread info with this thread
                if (theirInfo != null && theirIdx == theirInfo.idx() && theirInfo.op == POP) {
                    //Try to make ourselves unavailable
                    if (l.compareAndSet(idx, ourInfo, null)) {
                        //try to collide now
                        if (tryCollide(ourInfo, theirInfo, l)) {
                            m.successfulCollisions++;
                            break;
                        }
                        else { //Else retry stack
                            if (s.push(ourInfo)) break;
                            rp.recordCollisionFailure(); //Failed to collide increase record range and decrease wait count
                            wp.decreaseWait();
                            l.setRelease(idx, ourInfo);
                            m.failedCollisions++;
                            continue; //Immediately try and collide again,
                        }

                    } else {
                        ++m.successfulCollisions;
                        break; //If we can't make ourselves unavailable, another thread has collided with us, so we return
                    }

                } else {
                    if (theirInfo == null) {
                        rp.recordThreadAbsence(); //On thread absence
                        m.threadAbsence++;
                    }
                    else if (theirInfo.op == PUSH) {
                        m.similarOps++;
                    }

                }
            }

            wp.idle();

            if (l.getAcquire(idx) == null || !l.compareAndSet(idx, ourInfo, null)){
                m.successfulCollisions++;
                break; //We've been collided with
            }


            if (s.push(ourInfo)) {
                ++m.stackSuccesses;
                return true;
            }
            l.setRelease(idx, ourInfo); //Rewrite our info
        }

        wp.increaseWait();
        ourArr.compareAndSet(pos, idx, EMPTY);
        return true;
    }

    public T pop() {
        var s = stack;
        var p = policy.get();
        var idx = p.idx;
        var m = p.metrics;

        ThreadInfo<T> ourInfo = new ThreadInfo<>(idx, null, POP);
        if (s.pop(ourInfo)) {
            m.stackSuccesses++;
            return ourInfo.node().value;
        }

        var wp = p.waitPolicy();
        var rp = p.rangePolicy();
        var l = locations;
        var ourArr = popCollisionArray;
        var theirArr = pushCollisionArray;
        l.setRelease(idx, ourInfo); //Should make node immediately visible
        int pos;
        while (true) {
            pos = rp.calculatePos(); //random collision position
            int theirIdx = locationToCollide(ourArr, theirArr, idx, pos);  //Location idx we're colliding with
            if (theirIdx != EMPTY) {
                var theirInfo = l.getAcquire(theirIdx); //Use a get acquire read to ensure we always see the current node

                //The id check here is to ensure that another thread has not already swapped their thread info with this thread
                if (theirInfo != null && theirIdx == theirInfo.idx() && theirInfo.op() == PUSH) {
                    //Try to make ourselves unavailable
                    if (l.compareAndSet(idx, ourInfo, null)) {
                        //try to collide now
                        if (tryCollide(ourInfo, theirInfo, l)) {
                            m.successfulCollisions++;
                            break;
                        }
                        else { //Else retry stack
                            if (s.pop(ourInfo)) break;
                            rp.recordCollisionFailure(); //Failed to collide increase record range and decrease wait count
                            wp.decreaseWait();
                            l.setRelease(idx, ourInfo);
                            m.failedCollisions++;
                            continue; //Immediately try and collide again,
                        }

                    }else {
                        m.successfulCollisions++;
                        popFinishCollide(ourInfo, l.getAcquire(idx), l);
                        break;
                    } //If we can't make ourselves unavailable, another thread has collided with us, so we finish colliding

                } else {
                    if (theirInfo == null) {
                        rp.recordThreadAbsence(); //On thread absence
                        m.threadAbsence++;
                    }
                    else if (theirInfo.op == POP) {
                        m.similarOps++;
                    }
                }
            }

            wp.idle();
            if (!l.compareAndSet(idx, ourInfo, null)) {
                var i = l.getAcquire(idx);
                popFinishCollide(ourInfo,i , l);
                m.successfulCollisions++;
                break;
            } //We've been collided with

            if (s.pop(ourInfo)) {
                ++m.stackSuccesses;
                return ourInfo.node().value;
            }
            l.setRelease(idx, ourInfo);
        }

        ourArr.compareAndSet(pos, idx, EMPTY);
        wp.increaseWait();
        return ourInfo.node().value;
    }

    public Metrics getMetrics() {
        return policy.get().metrics;
    }

    //Set our idx in pos, and the get the opp idx at pos
    int locationToCollide(AtomicIntegerArray ours, AtomicIntegerArray opp ,int ourIdx, int pos) {
        ours.setRelease(pos, ourIdx);
        return opp.getAcquire(pos);
    }

    void popFinishCollide(ThreadInfo<T> ours, ThreadInfo<T> theirs, AtomicReferenceArray<ThreadInfo<T>> l) {
        ours.node = theirs.node;
        soLocation(ours.idx(), l);
    }

    boolean tryCollide(ThreadInfo<T> ours, ThreadInfo<T> theirs, AtomicReferenceArray<ThreadInfo<T>> arr) {
        return switch (ours.op) {
            case PUSH -> casLocation(theirs.idx(), arr, theirs, ours); //Swap our info into their position. Linearizability point
            case POP -> {
                if (casLocation(theirs.idx(), arr, theirs, null)) { //Swap their info to null. Linearizability point
                    ours.node = theirs.node;
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

    static <T>void soLocation(int idx, AtomicReferenceArray<ThreadInfo<T>> array) {
        array.setRelease(idx, null);
    }




    // Adaptive backoff
    /*
     * Back-off in space, we start with a factor which subsets our range to half the array
     * If we fail to collide with another thread `x` times.
     * It means the range we're operating in is too wide,
     * hence we decrease the factor to increase our chance of colliding with an operation

     * If we encounter a thread `x` times, but fail to collide with it ( the most probable reason is another thread has collided with it)
     * We multiply our factor by 2
     *
     * By failing to eliminate a thread, it means we failed to find a
     *
     *
     * Backoff in time, we start at the min count
     * If we fail to collide we increase a local count,
     *  once we reach a threshold we decrease our wait time
     *  basically it means if we're failing to collide we should retry more often to collide rather than spinning idly
     *
     * If we collide we increase a local count.
     *  once we reach a threshold we increase our wait time
     *  basically it means if we collide a lot we can wait longer as from our past record, we'll have been collided with
     *
     *
     * We approach these constraints by providing range
     *
     * */
    private static class AdaptiveBackoffPolicy {
        private final int idx;
        private final RangePolicy rangePolicy;
        private final WaitPolicy waitPolicy;
        private final Metrics metrics;


        AdaptiveBackoffPolicy(WaitStrategy strategy, int idx) {
            this.idx = idx;
            this.waitPolicy = new WaitPolicy(strategy);
            this.rangePolicy = new RangePolicy();
            this.metrics = new Metrics();
        }

        public WaitPolicy waitPolicy() {
            return waitPolicy;
        }

        public RangePolicy rangePolicy() {
            return rangePolicy;
        }
    }

    static class WaitPolicy {
        private int wait;
        private final WaitStrategy strategy;
        private int waitCount;


        WaitPolicy(WaitStrategy strategy) {
            this.strategy = Objects.requireNonNull(strategy);
            wait = switch (strategy) {
                case PARK -> MIN_PARK;
                case SPIN -> MIN_SPIN;
            };

            waitCount = 0;
        }

        static final int MIN_SPIN = 10;
        static final int MAX_SPIN = 200;

        static final int MIN_PARK = 100;
        static final int MAX_PARK = 1000;

        static final int UPPER_LIMIT = 5;
        static final int LOWER_LIMIT = -5;


        void increaseWait() {
            if (++waitCount > UPPER_LIMIT) {
                wait = switch (strategy) {
                    case SPIN -> Math.min(wait * 2, MAX_SPIN);
                    case PARK -> Math.min(wait + 100, MAX_PARK);
                };

                waitCount = 0;
            }
        }

        void decreaseWait() {
            if (--waitCount < LOWER_LIMIT) {
                wait = switch (strategy) {
                    case SPIN -> Math.max(wait / 2, MIN_SPIN);
                    case PARK -> Math.max(wait - 100, MIN_PARK);
                };
                waitCount = 0;
            }
        }

        void idle() {
            switch (strategy) {
                case SPIN -> {
                    int count = 0;
                    while (++count < wait) Thread.onSpinWait();
                }
                case PARK -> LockSupport.parkNanos(wait);
            }
        }

    }


    static class RangePolicy {
        private int collisionFailure;
        private int threadAbsence;
        private float rangeFactor = 0.5f;
        static final int LIMIT = 5;
        private int range = 1; // start narrow, expand on success

        void recordThreadAbsence() {
            if (++threadAbsence > LIMIT) {
                rangeFactor = Math.max(0.1f, rangeFactor / 2);
                range = Math.max(1, (int) (NCPU_HALVED * rangeFactor));
               threadAbsence = 0;
            }
        }

        void recordCollisionFailure() {
            if (++collisionFailure > LIMIT) {
                rangeFactor = Math.min(1f, rangeFactor * 2);
                range = Math.max(1, (int) (NCPU_HALVED * rangeFactor));
                collisionFailure = 0;

            }
        }

        int calculatePos() {
            //half = 1/2  =0; start = 1; end = (3 or 2.5) : 2
            int mid = NCPU_HALVED / 2;
            int half = range / 2;
            int start = Math.max(0, mid - half);
            int end = Math.min(NCPU_HALVED - 1, mid + half);
            return start + Math.abs(ThreadLocalRandom.current().nextInt() % (end - start + 1));
        }

    }



}
