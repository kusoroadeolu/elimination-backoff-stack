package io.github.kusoroadeolu.ebs;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

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
public class AdaptiveBackoffPolicy {
        private final int idx;
        private final RangePolicy rangePolicy;
        private final WaitPolicy waitPolicy;



        AdaptiveBackoffPolicy(WaitPolicy policy, int idx, int collisionArraySize) {
            this.idx = idx;
            this.waitPolicy = policy;
            this.rangePolicy = new RangePolicy(collisionArraySize);
        }



        public WaitPolicy waitPolicy() {
            return waitPolicy;
        }

        public RangePolicy rangePolicy() {
            return rangePolicy;
        }

    public int arrayIndex() {
        return idx;
    }

    public static class DefaultWaitPolicy implements WaitPolicy{
        private int wait;
        private final WaitStrategy strategy;
        private int successWaitCount;
        private int failWaitCount;


        public DefaultWaitPolicy(WaitStrategy strategy) {
            this.strategy = Objects.requireNonNull(strategy);
            wait = switch (strategy) {
                case PARK -> MIN_PARK;
                case SPIN -> MIN_SPIN;
            };

            successWaitCount = 0;
        }

        static final int MIN_SPIN = 100;
        static final int MAX_SPIN = 1000;

        static final int MIN_PARK = 1;
        static final int MAX_PARK = 10;

        static final int LIMIT = 5;

        @Override
        public void increaseWait() {
            if (++successWaitCount > LIMIT) {
                wait = switch (strategy) {
                    case SPIN -> Math.min(wait * 2, MAX_SPIN);
                    case PARK -> Math.min(wait + 2, MAX_PARK);
                };

                successWaitCount = 0;
            }
        }

        @Override
        public void decreaseWait() {
            if (++failWaitCount > LIMIT) {
                wait = switch (strategy) {
                    case SPIN -> Math.max(wait / 2, MIN_SPIN);
                    case PARK -> Math.max(wait - 1, MIN_PARK);
                };
                failWaitCount = 0;
            }
        }

        @Override
        public void idle() {
            switch (strategy) {
                case SPIN -> {
                    int count = 0;
                    while (++count < wait) Thread.onSpinWait();
                }
                case PARK -> LockSupport.parkNanos(wait);
            }
        }

    }

    public static class AdaptiveWaitPolicy implements WaitPolicy{

        private WaitStrategy mode;
        private int spinCount;
        private int parkNanos;
        private int successCount;
        private int failCount;

        static final int MIN_SPIN = 100;
        static final int MAX_SPIN = 1000;

        static final int MIN_PARK = 1;
        static final int MAX_PARK = 10;

        static final int LIMIT = 5;

        AdaptiveWaitPolicy() {
            mode = WaitStrategy.SPIN;
            spinCount = MIN_SPIN;
            parkNanos = MIN_PARK;
            successCount = 0;
            failCount = 0;
        }

        public void increaseWait() {
            if (++successCount > LIMIT) {
                if (mode == WaitStrategy.SPIN) {
                    spinCount = Math.min(spinCount * 2, MAX_SPIN);
                    if (spinCount == MAX_SPIN) mode = WaitStrategy.PARK;
                } else {
                    parkNanos = Math.min(parkNanos + 2, MAX_PARK);
                }
                successCount = 0;
            }
        }

        public void decreaseWait() {
            if (++failCount > LIMIT) {
                if (mode == WaitStrategy.PARK) {
                    parkNanos = Math.max(parkNanos - 1, MIN_PARK);
                    if (parkNanos == MIN_PARK) mode = WaitStrategy.SPIN;
                } else {
                    spinCount = Math.max(spinCount / 2, MIN_SPIN);
                }
                successCount = 0;
            }
        }

        public void idle() {
            if (mode == WaitStrategy.SPIN) {
                int count = 0;
                while (++count < spinCount) Thread.onSpinWait();
            } else {
                LockSupport.parkNanos(parkNanos);
            }
        }
    }


    public static class RangePolicy {
        private int collisionFailure;
        private int threadAbsence;
        private float rangeFactor = 0.5f;
        static final int LIMIT = 5;
        private int range = 1; // start narrow, expand on success
        private final int collisionArraySize;

        public RangePolicy(int collisionArraySize) {
            this.collisionArraySize = collisionArraySize;
        }

        public void recordThreadAbsence() {
            if (++threadAbsence > LIMIT) {
                rangeFactor = Math.max(0.1f, rangeFactor / 2);
                range = Math.max(1, (int) (collisionArraySize * rangeFactor));
                threadAbsence = 0;
            }
        }

        public void recordCollisionFailure() {
            if (++collisionFailure > LIMIT) {
                rangeFactor = Math.min(1f, rangeFactor * 2);
                range = Math.max(1, (int) (collisionArraySize * rangeFactor));
                collisionFailure = 0;

            }
        }

        public int calculatePos() {
            int mid = collisionArraySize / 2;
            int half = range / 2;
            int start = Math.max(0, mid - half);
            int end = Math.min(collisionArraySize - 1, mid + half);
            int bound = end - start + 1;
            return bound > 1
                    ? start + ThreadLocalRandom.current().nextInt(bound)
                    : start;
        }

    }
}

