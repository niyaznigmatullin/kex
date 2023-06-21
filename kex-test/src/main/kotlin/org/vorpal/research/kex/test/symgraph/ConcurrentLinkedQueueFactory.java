package org.vorpal.research.kex.test.symgraph;

import java.util.concurrent.ConcurrentLinkedQueue;

public class ConcurrentLinkedQueueFactory {
    public static <E> ConcurrentLinkedQueue<E> newConcurrentLinkedQueue() {
        return new ConcurrentLinkedQueue<>();
    }
}
