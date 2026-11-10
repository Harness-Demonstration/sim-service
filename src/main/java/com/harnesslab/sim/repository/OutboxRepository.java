package com.harnesslab.sim.repository;

import com.harnesslab.sim.types.OutboxEvent;
import java.util.List;

public interface OutboxRepository {
    void save(OutboxEvent event);

    /** Oldest unsent events, locked so that other instances skip them until the transaction ends. */
    List<OutboxEvent> lockPending(int limit);
}
