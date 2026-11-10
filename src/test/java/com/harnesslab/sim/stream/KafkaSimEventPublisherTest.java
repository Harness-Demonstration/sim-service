package com.harnesslab.sim.stream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.harnesslab.sim.types.SimActivated;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class KafkaSimEventPublisherTest {

    @Test
    void failed_kafka_send_is_reported() {
        KafkaTemplate<String, SimActivated> kafkaTemplate = mock(KafkaTemplate.class);
        SimActivated event = new SimActivated(
                "8949000000000000001", "491700000001", Instant.parse("2026-01-02T03:04:05Z"));
        CompletableFuture<SendResult<String, SimActivated>> sendResult = new CompletableFuture<>();
        sendResult.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(kafkaTemplate.send("sim.activated", event.iccid(), event)).thenReturn(sendResult);

        KafkaSimEventPublisher publisher = new KafkaSimEventPublisher(kafkaTemplate);

        assertThrows(IllegalStateException.class, () -> publisher.publish(event));
    }
}
