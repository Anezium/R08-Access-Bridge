package com.anezium.ringhealth.internal.transport;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class SerializedQueueTest {
    @Test public void neverStartsSecondGattOperationBeforeFirstCallback() {
        List<String> started = new ArrayList<>();
        SerializedQueue<String> queue = new SerializedQueue<>(item -> {
            started.add(item);
            return true;
        });
        queue.add("descriptor-control");
        queue.add("descriptor-large");
        queue.add("control-write");
        assertEquals(List.of("descriptor-control"), started);
        assertEquals(2, queue.pendingCount());
        queue.complete();
        assertEquals(List.of("descriptor-control", "descriptor-large"), started);
        queue.complete();
        assertEquals(List.of("descriptor-control", "descriptor-large", "control-write"), started);
    }

    @Test public void rejectedOperationDoesNotBlockFollowingOperation() {
        List<String> started = new ArrayList<>();
        SerializedQueue<String> queue = new SerializedQueue<>(item -> {
            started.add(item);
            return !item.equals("rejected");
        });
        queue.add("rejected");
        queue.add("accepted");
        assertEquals(List.of("rejected", "accepted"), started);
        assertEquals("accepted", queue.active());
    }
}
