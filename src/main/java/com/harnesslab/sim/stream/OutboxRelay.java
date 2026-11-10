package com.harnesslab.sim.stream;

import com.harnesslab.sim.service.OutboxRelayService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "sim.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private final OutboxRelayService service;
    private final KafkaSimEventPublisher publisher;

    public OutboxRelay(OutboxRelayService service, KafkaSimEventPublisher publisher) {
        this.service = service;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelay = 1000)
    public void relay() {
        service.relayPending(publisher);
    }
}
