package com.harnesslab.sim.stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnesslab.sim.types.OutboxEvent;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class KafkaSimEventPublisherTest {

    @Test
    void failed_kafka_send_is_reported() {
        KafkaTemplate<byte[], byte[]> kafkaTemplate = mock(KafkaTemplate.class);
        OutboxEvent event = new OutboxEvent(
                UUID.randomUUID(), "8949000000000000001", "sim.activated", "{}",
                Instant.parse("2026-01-02T03:04:05Z"), null);
        CompletableFuture<SendResult<byte[], byte[]>> sendResult = new CompletableFuture<>();
        sendResult.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(kafkaTemplate.send(eq("sim.activated"), aryEq(event.aggregateId().getBytes(StandardCharsets.UTF_8)),
                aryEq(event.payload().getBytes(StandardCharsets.UTF_8)))).thenReturn(sendResult);

        KafkaSimEventPublisher publisher = new KafkaSimEventPublisher(kafkaTemplate, new ObjectMapper());

        assertThrows(ExecutionException.class, () -> publisher.publish(event).get());
    }

    @Test
    void send_does_not_wait_for_the_acknowledgement() {
        KafkaTemplate<byte[], byte[]> kafkaTemplate = mock(KafkaTemplate.class);
        OutboxEvent event = new OutboxEvent(
                UUID.randomUUID(), "8949000000000000001", "sim.activated", "{}",
                Instant.parse("2026-01-02T03:04:05Z"), null);
        CompletableFuture<SendResult<byte[], byte[]>> sendResult = new CompletableFuture<>();
        when(kafkaTemplate.send(eq("sim.activated"), aryEq(event.aggregateId().getBytes(StandardCharsets.UTF_8)),
                aryEq(event.payload().getBytes(StandardCharsets.UTF_8)))).thenReturn(sendResult);

        CompletableFuture<Void> acknowledgement = new KafkaSimEventPublisher(kafkaTemplate, new ObjectMapper()).publish(event);

        assertFalse(acknowledgement.isDone());
    }

    @Test
    void dead_letter_publishes_original_bytes_and_reason_header() throws Exception {
        KafkaTemplate<byte[], byte[]> kafkaTemplate = mock(KafkaTemplate.class);
        byte[] key = {(byte) 0xff};
        byte[] value = {(byte) 0xfe, 0};
        ObjectMapper mapper = new ObjectMapper();
        var envelope = new com.harnesslab.sim.types.SimOrderedDeadLetter(
                Base64.getEncoder().encodeToString(key), Base64.getEncoder().encodeToString(value), "Invalid JSON");
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), UUID.randomUUID().toString(), "sim.ordered.dlt",
                mapper.writeValueAsString(envelope), Instant.parse("2026-01-02T03:04:05Z"), null);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        new KafkaSimEventPublisher(kafkaTemplate, mapper).publish(event).get();

        ArgumentCaptor<ProducerRecord<byte[], byte[]>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<byte[], byte[]> sent = captor.getValue();
        assertArrayEquals(key, sent.key());
        assertArrayEquals(value, sent.value());
        assertEquals("Invalid JSON", new String(sent.headers().lastHeader("reason").value(), StandardCharsets.UTF_8));
        assertEquals(event.id().toString(), new String(sent.headers().lastHeader("eventId").value(), StandardCharsets.UTF_8));
    }
}
