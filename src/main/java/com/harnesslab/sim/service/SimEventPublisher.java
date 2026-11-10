package com.harnesslab.sim.service;

import com.harnesslab.sim.types.SimActivated;

public interface SimEventPublisher {
    void publish(SimActivated event);
}
