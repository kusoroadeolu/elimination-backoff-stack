package io.github.kusoroadeolu.fstack;

import org.jetbrains.lincheck.Lincheck;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LincheckTest {
    @Test
    //We should have at least one non-null head regardless
    public void twoPushOnePopTest() {
        Lincheck.runConcurrentTest(() -> {

            final EliminationStack<Integer> stack = new EliminationStack<>();
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

}