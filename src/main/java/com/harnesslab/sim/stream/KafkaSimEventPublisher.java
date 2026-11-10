package com.harnesslab.sim.stream;

import com.harnesslab.sim.service.SimEventPublisher;
import com.harnesslab.sim.types.OutboxEvent;
import java.util.concurrent.CompletableFuture;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaSimEventPublisher implements SimEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaSimEventPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public CompletableFuture<Void> publish(OutboxEvent event) {
        try {
            return kafkaTemplate.send(event.topic(), event.aggregateId(), event.payload()).thenApply(result -> null);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }
}
