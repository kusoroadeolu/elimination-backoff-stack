package io.github.kusoroadeolu.ebs;

import java.util.concurrent.locks.LockSupport;

public class Backoff {
    private static final int SPIN_LIMIT = 6;
    private static final int YIELD_LIMIT = 10;
    private static final int PARK_NANOS = 1_000_000; //park for 1ms


    /*
    * Exponentially spins before yielding and eventually parks briefly and continuing as so
    * */
    public static int snooze(int step) {
        if (step <= SPIN_LIMIT) {
            int spins = 1 << step;
            for (int i = 0; i < spins; i++) Thread.onSpinWait();
        } else if (step <= YIELD_LIMIT){
            Thread.yield();
        } else {
            LockSupport.parkNanos(PARK_NANOS);
        }

        if (step <= YIELD_LIMIT) {
            return ++step;
        } else {
            return 0;
        }
    }

}