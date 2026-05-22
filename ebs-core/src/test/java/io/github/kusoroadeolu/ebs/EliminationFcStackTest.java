package io.github.kusoroadeolu.ebs;

import io.github.kusoroadeolu.ebs.DECSStack.ThreadNode;
import org.junit.jupiter.api.Test;

import static io.github.kusoroadeolu.ebs.DECSStack.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class EliminationFcStackTest {
//    @Test
//    void testMultiPop() {
//        var stack = new DECSStack.MultiStack<Integer>();
//
//        // Prefill stack with 5 nodes: 1 -> 2 -> 3 -> 4 -> 5
//        for (int i = 5; i >= 1; i--) {
//            stack.push(i);
//        }
//
//        // Build a ThreadNode chain requesting 3 pops
//        var tn1 = new ThreadNode<Integer>(1);
//        var tn2 = new ThreadNode<Integer>(2);
//        var tn3 = new ThreadNode<Integer>(3);
//        tn1.next = tn2;
//        tn2.next = tn3;
//        tn1.last = tn3;
//        tn1.size = 3;
//
//        boolean result = stack.multiPop(tn1);
//
//        assertTrue(result);
//
//        // Thread nodes should have gotten nodes 1, 2, 3
//        assertEquals(1, tn1.node.value);
//        assertEquals(2, tn2.node.value);
//        assertEquals(3, tn3.node.value);
//
//        // MultiStack head should now be at 4
//        assertEquals(4, stack.loHead().value);
//
//        // All statuses should be FINISHED
//        assertEquals(FINISHED, tn1.status);
//        assertEquals(FINISHED, tn2.status);
//        assertEquals(FINISHED, tn3.status);
//    }
//
//    @Test
//    void testMultiPopEmptyStack() {
//        var stack = new DECSStack.MultiStack<Integer>();
//        var tn1 = new ThreadNode<Integer>(1);
//        tn1.size = 2;
//        var tn2 = new ThreadNode<Integer>(2);
//        tn1.next = tn2;
//        tn1.last = tn2;
//
//        boolean result = stack.multiPop(tn1);
//
//        assertTrue(result);
//        // Both should be FINISHED with null/EMPTY nodes
//        assertEquals(FINISHED, tn1.status);
//        assertEquals(FINISHED, tn2.status);
//    }
//
//    @Test
//    void testMultiPopMoreThanStack() {
//        var stack = new DECSStack.MultiStack<Integer>();
//        stack.push(1); // only 1 node
//
//        var tn1 = new ThreadNode<Integer>(1);
//        var tn2 = new ThreadNode<Integer>(2);
//        tn1.next = tn2;
//        tn1.last = tn2;
//        tn1.size = 2;
//
//        boolean result = stack.multiPop(tn1);
//
//        assertTrue(result);
//        assertEquals(1, tn1.node.value);
//        // tn2 asked for a node but stack ran out
//        assertEquals(FINISHED, tn2.status); // finished with EMPTY/null
//    }
}