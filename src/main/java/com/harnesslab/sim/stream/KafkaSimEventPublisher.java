package com.harnesslab.sim.stream;

import com.harnesslab.sim.service.SimEventPublisher;
import com.harnesslab.sim.types.OutboxEvent;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaSimEventPublisher implements SimEventPublisher {

    private static final long ACK_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaSimEventPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(OutboxEvent event) {
        try {
            kafkaTemplate.send(event.topic(), event.aggregateId(), event.payload())
                    .get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing " + event.topic(), exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Failed to publish " + event.topic(), exception.getCause());
        } catch (TimeoutException exception) {
            throw new IllegalStateException("Timed out publishing " + event.topic(), exception);
        }
    }
}
