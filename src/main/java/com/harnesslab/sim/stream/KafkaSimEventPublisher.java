package com.harnesslab.sim.stream;

import com.harnesslab.sim.service.SimEventPublisher;
import com.harnesslab.sim.types.SimActivated;
import java.util.concurrent.ExecutionException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaSimEventPublisher implements SimEventPublisher {

    private final KafkaTemplate<String, SimActivated> kafkaTemplate;

    public KafkaSimEventPublisher(KafkaTemplate<String, SimActivated> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(SimActivated event) {
        try {
            kafkaTemplate.send("sim.activated", event.iccid(), event).get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing sim.activated", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Failed to publish sim.activated", exception.getCause());
        }
    }
}
