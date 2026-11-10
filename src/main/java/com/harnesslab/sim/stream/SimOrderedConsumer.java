package com.harnesslab.sim.stream;

import com.harnesslab.sim.service.SimActivationService;
import com.harnesslab.sim.types.SimOrdered;
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
    public void onSimOrdered(SimOrdered order) {
        log.info("sim.ordered received orderId={} iccid={} msisdn={}", order.orderId(), order.iccid(), order.msisdn());
        service.registerOrdered(order);
    }
}
