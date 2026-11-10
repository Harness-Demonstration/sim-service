package com.harnesslab.sim.service;

import com.harnesslab.sim.repository.SimRepository;
import com.harnesslab.sim.types.Sim;
import com.harnesslab.sim.types.SimActivated;
import com.harnesslab.sim.types.SimOrdered;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class SimActivationService {

    private final SimRepository repository;
    private final SimEventPublisher eventPublisher;
    private final Clock clock;

    public SimActivationService(SimRepository repository, SimEventPublisher eventPublisher, Clock clock) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    public synchronized void registerOrdered(SimOrdered order) {
        if (repository.findByIccid(order.iccid()).isEmpty()) {
            repository.save(new Sim(order.iccid(), order.msisdn(), null));
        }
    }

    public synchronized void activate(String iccid) {
        Sim sim = repository.findByIccid(iccid).orElseThrow(() -> new SimNotFoundException(iccid));
        if (sim.activatedAt() != null) {
            return;
        }

        Instant activatedAt = clock.instant();
        repository.save(new Sim(sim.iccid(), sim.msisdn(), activatedAt));
        eventPublisher.publish(new SimActivated(sim.iccid(), sim.msisdn(), activatedAt));
    }
}
