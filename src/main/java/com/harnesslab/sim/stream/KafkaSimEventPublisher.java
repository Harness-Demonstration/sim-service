package com.harnesslab.sim.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnesslab.sim.service.SimEventPublisher;
import com.harnesslab.sim.types.OutboxEvent;
import com.harnesslab.sim.types.SimOrderedDeadLetter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaSimEventPublisher implements SimEventPublisher {

    private final KafkaTemplate<byte[], byte[]> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaSimEventPublisher(KafkaTemplate<byte[], byte[]> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public CompletableFuture<Void> publish(OutboxEvent event) {
        try {
            if (SimOrderedDeadLetter.TOPIC.equals(event.topic())) {
                SimOrderedDeadLetter deadLetter = objectMapper.readValue(event.payload(), SimOrderedDeadLetter.class);
                ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(
                        event.topic(), decode(deadLetter.key()), decode(deadLetter.value()));
                record.headers().add("reason", deadLetter.reason().getBytes(StandardCharsets.UTF_8));
                record.headers().add("eventId", event.id().toString().getBytes(StandardCharsets.UTF_8));
                return kafkaTemplate.send(record).thenApply(result -> null);
            }
            return kafkaTemplate.send(event.topic(), event.aggregateId().getBytes(StandardCharsets.UTF_8),
                    event.payload().getBytes(StandardCharsets.UTF_8)).thenApply(result -> null);
        } catch (RuntimeException | JsonProcessingException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private static byte[] decode(String value) {
        return value == null ? null : Base64.getDecoder().decode(value);
    }
}
