package com.harnesslab.sim.stream;

import com.harnesslab.sim.service.SimActivationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class SimOrderedConsumer {

    private static final Logger log = LoggerFactory.getLogger(SimOrderedConsumer.class);
    private final SimActivationService service;

    public SimOrderedConsumer(SimActivationService service) {
        this.service = service;
    }

    @KafkaListener(topics = "sim.ordered", groupId = "sim-service")
    public void onSimOrdered(String message) {
        log.info("sim.ordered received");
        service.handleOrdered(message);
    }
}
