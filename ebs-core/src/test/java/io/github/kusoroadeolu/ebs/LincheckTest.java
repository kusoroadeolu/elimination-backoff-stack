package io.github.kusoroadeolu.ebs;

import org.jetbrains.lincheck.Lincheck;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class LincheckTest {
    @Test
    //We should have at least one non-null head regardless
    public void twoPushOnePopTest() {
        Lincheck.runConcurrentTest(() -> {

            final ConcurrentStack<Integer> stack = new DECStack<>(WaitStrategy.SPIN);
            Thread t1 = new Thread(() -> stack.push(1));
            Thread t2 = new Thread(() -> stack.push(2));
            Thread t3 = new Thread(stack::pop);


            t1.start();
            t2.start();
            t3.start();

            try {
                t1.join();
                t2.join();
                t3.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }


            Assertions.assertNotNull(stack.pop());
        });
    }

    @Test
    public void noDuplicatePops() {
        Lincheck.runConcurrentTest(() -> {
            ConcurrentStack<Integer> stack = new DECStack<>(WaitStrategy.SPIN);
            Set<Integer> popped = ConcurrentHashMap.newKeySet();

            stack.push(1); stack.push(2); stack.push(3);

            Thread t1 = new Thread(() -> {
                Integer val = stack.pop();
                if (val != null) assertTrue(popped.add(val));
            });

            Thread t2 = new Thread(() -> {
                Integer val = stack.pop();
                if (val != null) assertTrue(popped.add(val));
            });

            t1.start(); t2.start();

            try {
                t1.join();
                t2.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
    }

}