package com.harnesslab.sim.stream;

import com.harnesslab.sim.service.SimEventPublisher;
import com.harnesslab.sim.types.SimActivated;
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
        kafkaTemplate.send("sim.activated", event.iccid(), event);
    }
}
