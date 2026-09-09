package com.template_update_service.events;

import java.util.List;

/**
 * In production this is SQS FIFO grouped by engagement id, but ordering is defence
 * in depth rather than the correctness mechanism -- the inbox and the sequence guard
 * are. Tests implement this in memory and deliver events in orders FIFO would never
 * produce.
 */
public interface EventQueue {

    List<TemplateEvent> poll(int maxEvents);

    void acknowledge(TemplateEvent event);

    /**
     * Not processable yet. Returned for later delivery rather than dropped: a redrive
     * policy moves it to the DLQ after N attempts, which is the signal that something
     * is wrong upstream rather than merely late.
     */
    void requeue(TemplateEvent event);
}
