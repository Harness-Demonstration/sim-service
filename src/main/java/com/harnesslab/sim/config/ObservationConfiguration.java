package com.harnesslab.sim.config;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObservationConfiguration {

    /** The outbox relay runs every second; one empty trace per run would hide the real ones in Jaeger. */
    @Bean
    ObservationPredicate noScheduledTaskObservations() {
        return (name, context) -> !"tasks.scheduled.execution".equals(name);
    }
}
