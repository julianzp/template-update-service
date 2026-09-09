package com.template_update_service.consumer;

import com.template_update_service.events.EventQueue;
import com.template_update_service.events.TemplateEvent;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Not a Spring bean: no EventQueue implementation ships in this slice, so the poller is
 * constructed by whatever supplies one -- the tests here, an SQS adapter in production.
 */
public class EventPoller {

    private static final Logger log = LoggerFactory.getLogger(EventPoller.class);

    private final EventQueue queue;
    private final EventConsumer consumer;

    public EventPoller(EventQueue queue, EventConsumer consumer) {
        this.queue = queue;
        this.consumer = consumer;
    }

    public int drainOnce(int maxEvents) {
        List<TemplateEvent> batch = queue.poll(maxEvents);
        for (TemplateEvent event : batch) {
            try {
                if (consumer.handle(event).shouldRedeliver()) {
                    queue.requeue(event);
                } else {
                    queue.acknowledge(event);
                }
            } catch (RuntimeException ex) {
                // Concurrent publishes for one template can collide on the single-HEAD
                // index. Requeue rather than drop; a redrive policy escalates to the DLQ,
                // where depth is alerted -- a lost event is a silently wrong engagement.
                log.warn("event {} failed, requeueing", event.eventId(), ex);
                queue.requeue(event);
            }
        }
        return batch.size();
    }
}
