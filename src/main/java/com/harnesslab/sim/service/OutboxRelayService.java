package com.harnesslab.sim.service;

import com.harnesslab.sim.repository.OutboxRepository;
import com.harnesslab.sim.types.OutboxEvent;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxRelayService {

    static final int BATCH_SIZE = 100;
    static final Duration ACK_TIMEOUT = Duration.ofSeconds(10);
    private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);

    private final OutboxRepository outbox;
    private final Clock clock;

    public OutboxRelayService(OutboxRepository outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Sends all claimed events first, then waits for the acknowledgements together. Acknowledged events are
     * marked sent; events whose send failed or timed out stay pending for the next run.
     */
    @Transactional
    public int relayPending(SimEventPublisher publisher) {
        List<OutboxEvent> pending = outbox.lockPending(BATCH_SIZE);
        List<CompletableFuture<Void>> acknowledgements = new ArrayList<>();
        for (OutboxEvent event : pending) {
            acknowledgements.add(start(publisher, event));
        }

        long deadline = System.nanoTime() + ACK_TIMEOUT.toNanos();
        int sent = 0;
        for (int i = 0; i < pending.size(); i++) {
            OutboxEvent event = pending.get(i);
            if (!acknowledged(acknowledgements.get(i), deadline, event)) {
                continue;
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

    private static CompletableFuture<Void> start(SimEventPublisher publisher, OutboxEvent event) {
        try {
            return publisher.publish(event);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private boolean acknowledged(CompletableFuture<Void> acknowledgement, long deadline, OutboxEvent event) {
        try {
            acknowledgement.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted waiting for outbox event {}, will retry", event.id());
        } catch (ExecutionException | TimeoutException exception) {
            log.warn("Publishing outbox event {} to {} failed, will retry", event.id(), event.topic(), exception);
        }
        return false;
    }
}
