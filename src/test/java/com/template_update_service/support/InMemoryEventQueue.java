package com.template_update_service.support;

import com.template_update_service.events.EventQueue;
import com.template_update_service.events.TemplateEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Delivers exactly the order a test asks for, including orders FIFO would never produce.
 * Requeued events go to the back, which is what lets a parked decision event land behind
 * the EngagementCreated it was waiting for.
 */
public class InMemoryEventQueue implements EventQueue {

    private final Deque<TemplateEvent> undelivered = new ArrayDeque<>();
    private final List<TemplateEvent> acknowledged = new ArrayList<>();

    public InMemoryEventQueue(List<TemplateEvent> events) {
        undelivered.addAll(events);
    }

    @Override
    public List<TemplateEvent> poll(int maxEvents) {
        List<TemplateEvent> batch = new ArrayList<>();
        while (batch.size() < maxEvents && !undelivered.isEmpty()) {
            batch.add(undelivered.poll());
        }
        return batch;
    }

    @Override
    public void acknowledge(TemplateEvent event) {
        acknowledged.add(event);
    }

    @Override
    public void requeue(TemplateEvent event) {
        undelivered.addLast(event);
    }

    public boolean isDrained() {
        return undelivered.isEmpty();
    }

    public List<TemplateEvent> acknowledged() {
        return List.copyOf(acknowledged);
    }
}
