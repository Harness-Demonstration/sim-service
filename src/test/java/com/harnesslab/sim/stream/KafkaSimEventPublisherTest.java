package com.harnesslab.sim.stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.harnesslab.sim.types.OutboxEvent;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class KafkaSimEventPublisherTest {

    @Test
    void failed_kafka_send_is_reported() {
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        OutboxEvent event = new OutboxEvent(
                UUID.randomUUID(), "8949000000000000001", "sim.activated", "{}",
                Instant.parse("2026-01-02T03:04:05Z"), null);
        CompletableFuture<SendResult<String, String>> sendResult = new CompletableFuture<>();
        sendResult.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(kafkaTemplate.send("sim.activated", event.aggregateId(), event.payload())).thenReturn(sendResult);

        KafkaSimEventPublisher publisher = new KafkaSimEventPublisher(kafkaTemplate);

        assertThrows(ExecutionException.class, () -> publisher.publish(event).get());
    }

    @Test
    void send_does_not_wait_for_the_acknowledgement() {
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        OutboxEvent event = new OutboxEvent(
                UUID.randomUUID(), "8949000000000000001", "sim.activated", "{}",
                Instant.parse("2026-01-02T03:04:05Z"), null);
        CompletableFuture<SendResult<String, String>> sendResult = new CompletableFuture<>();
        when(kafkaTemplate.send("sim.activated", event.aggregateId(), event.payload())).thenReturn(sendResult);

        CompletableFuture<Void> acknowledgement = new KafkaSimEventPublisher(kafkaTemplate).publish(event);

        assertFalse(acknowledgement.isDone());
    }
}
