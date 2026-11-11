package com.harnesslab.sim.stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnesslab.sim.types.OutboxEvent;
import com.harnesslab.sim.types.SimOrderedRejected;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
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

        KafkaSimEventPublisher publisher = new KafkaSimEventPublisher(kafkaTemplate, new ObjectMapper());

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

        CompletableFuture<Void> acknowledgement =
                new KafkaSimEventPublisher(kafkaTemplate, new ObjectMapper()).publish(event);

        assertFalse(acknowledgement.isDone());
    }

    @Test
    void dead_letter_payload_is_decoded_before_it_is_sent_to_kafka() throws Exception {
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        ObjectMapper mapper = new ObjectMapper();
        UUID eventId = UUID.randomUUID();
        String publishedPayload = mapper.writeValueAsString(new SimOrderedRejected(eventId, "message is empty", null));
        String storedPayload = mapper.writeValueAsString(Base64.getEncoder()
                .encodeToString(publishedPayload.getBytes(StandardCharsets.UTF_8)));
        OutboxEvent event = new OutboxEvent(
                eventId,
                eventId.toString(),
                "sim.ordered.dead-letter",
                storedPayload,
                Instant.parse("2026-01-02T03:04:05Z"),
                null);
        when(kafkaTemplate
                        .send(event.topic(), event.aggregateId(), publishedPayload))
                .thenReturn(CompletableFuture.completedFuture(null));

        new KafkaSimEventPublisher(kafkaTemplate, mapper).publish(event);

        verify(kafkaTemplate).send(event.topic(), event.aggregateId(), publishedPayload);
    }
}
