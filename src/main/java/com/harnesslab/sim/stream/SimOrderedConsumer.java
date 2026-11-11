package com.harnesslab.sim.stream;

import com.harnesslab.sim.service.SimActivationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class SimOrderedConsumer {

    private final SimActivationService service;

    public SimOrderedConsumer(SimActivationService service) {
        this.service = service;
    }

    @KafkaListener(topics = "sim.ordered", groupId = "sim-service")
    public void onSimOrdered(ConsumerRecord<byte[], byte[]> order) {
        service.registerOrderedMessage(order.value(), order.key());
    }
}
