package com.harnesslab.sim.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnesslab.sim.service.SimEventPublisher;
import com.harnesslab.sim.types.OutboxEvent;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaSimEventPublisher implements SimEventPublisher {

    private static final String SIM_ORDERED_DEAD_LETTER_TOPIC = "sim.ordered.dead-letter";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaSimEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public CompletableFuture<Void> publish(OutboxEvent event) {
        try {
            return kafkaTemplate
                    .send(event.topic(), event.aggregateId(), publishedPayload(event))
                    .thenApply(result -> null);
        } catch (JsonProcessingException | RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private String publishedPayload(OutboxEvent event) throws JsonProcessingException {
        if (!SIM_ORDERED_DEAD_LETTER_TOPIC.equals(event.topic())) {
            return event.payload();
        }
        JsonNode storedPayload = objectMapper.readTree(event.payload());
        if (!storedPayload.isTextual()) {
            return event.payload();
        }
        return new String(Base64.getDecoder().decode(storedPayload.textValue()), StandardCharsets.UTF_8);
    }
}
