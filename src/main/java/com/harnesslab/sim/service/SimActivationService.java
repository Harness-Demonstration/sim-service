package com.harnesslab.sim.service;

import com.harnesslab.sim.types.SimActivated;
import com.harnesslab.sim.types.SimOrdered;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class SimActivationService {

    private final KafkaTemplate<String, SimActivated> kafkaTemplate;
    private final Map<String, SimState> sims = new HashMap<>();

    public SimActivationService(KafkaTemplate<String, SimActivated> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public synchronized void onSimOrdered(SimOrdered order) {
        sims.putIfAbsent(order.iccid(), new SimState(order));
    }

    public synchronized boolean activate(String iccid) {
        SimState sim = sims.get(iccid);
        if (sim == null) {
            return false;
        }
        if (!sim.active) {
            kafkaTemplate.send("sim.activated", iccid,
                    new SimActivated(iccid, sim.order.msisdn(), Instant.now()));
            sim.active = true;
        }
        return true;
    }

    private static class SimState {
        private final SimOrdered order;
        private boolean active;

        private SimState(SimOrdered order) {
            this.order = order;
        }
    }
}
