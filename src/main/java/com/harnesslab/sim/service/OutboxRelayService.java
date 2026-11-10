package com.harnesslab.sim.service;

import com.harnesslab.sim.repository.OutboxRepository;
import com.harnesslab.sim.types.OutboxEvent;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxRelayService {

    static final int BATCH_SIZE = 100;
    private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);

    private final OutboxRepository outbox;
    private final Clock clock;

    public OutboxRelayService(OutboxRepository outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Publishes the oldest unsent events and marks each one sent. Stops at the first failure; events sent
     * before it stay marked, the failed one and the rest stay pending for the next run.
     */
    @Transactional
    public int relayPending(SimEventPublisher publisher) {
        List<OutboxEvent> pending = outbox.lockPending(BATCH_SIZE);
        int sent = 0;
        for (OutboxEvent event : pending) {
            try {
                publisher.publish(event);
            } catch (RuntimeException exception) {
                log.warn("Publishing outbox event {} to {} failed, will retry", event.id(), event.topic(), exception);
                break;
            }
            outbox.save(new OutboxEvent(
                    event.id(),
                    event.aggregateId(),
                    event.topic(),
                    event.payload(),
                    event.createdAt(),
                    clock.instant()));
            sent++;
        }
        return sent;
    }
}
