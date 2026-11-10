package com.harnesslab.sim.repository;

import com.harnesslab.sim.types.Sim;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "sim")
public class SimEntity {

    @Id
    @Column(length = 32)
    private String iccid;

    @Column(nullable = false, length = 32)
    private String msisdn;

    @Column(name = "activated_at")
    private Instant activatedAt;

    protected SimEntity() {}

    SimEntity(Sim sim) {
        this.iccid = sim.iccid();
        this.msisdn = sim.msisdn();
        this.activatedAt = sim.activatedAt();
    }

    Sim toDomain() {
        return new Sim(iccid, msisdn, activatedAt);
    }
}
