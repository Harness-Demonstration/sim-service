package com.harnesslab.sim.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnesslab.sim.repository.OutboxRepository;
import com.harnesslab.sim.repository.SimRepository;
import com.harnesslab.sim.types.OutboxEvent;
import com.harnesslab.sim.types.Sim;
import com.harnesslab.sim.types.SimActivated;
import com.harnesslab.sim.types.SimDeactivated;
import com.harnesslab.sim.types.SimOrdered;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SimActivationService {

    static final String SIM_ACTIVATED_TOPIC = "sim.activated";

    static final String SIM_DEACTIVATED_TOPIC = "sim.deactivated";

    private final SimRepository repository;
    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SimActivationService(
            SimRepository repository, OutboxRepository outbox, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public synchronized void registerOrdered(SimOrdered order) {
        if (repository.findByIccid(order.iccid()).isEmpty()) {
            repository.save(new Sim(order.iccid(), order.msisdn(), null));
        }
    }

    @Transactional
    public void registerOrderedMessage(byte[] value) {
        JsonNode json;
        try {
            json = value == null ? null : objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value);
        } catch (IOException exception) {
            throw new InvalidSimOrderException("Invalid JSON");
        }
        if (json == null || !json.isObject()) {
            throw new InvalidSimOrderException("Expected a JSON object");
        }
        JsonNode iccid = json.get("iccid");
        JsonNode msisdn = json.get("msisdn");
        if (iccid == null || !iccid.isTextual() || !iccid.textValue().matches("[0-9]{19,20}")) {
            throw new InvalidSimOrderException("ICCID must be 19 or 20 digits");
        }
        if (msisdn == null || !msisdn.isTextual() || !msisdn.textValue().matches("[1-9][0-9]{0,14}")) {
            throw new InvalidSimOrderException("MSISDN must be 1 to 15 digits and start with a nonzero digit");
        }
        JsonNode orderId = json.get("orderId");
        if (orderId == null || !orderId.isTextual()) {
            throw new InvalidSimOrderException("Invalid orderId");
        }
        registerOrdered(new SimOrdered(iccid.textValue(), msisdn.textValue(), orderId.textValue()));
    }

    @Transactional
    public void activate(String iccid) {
        Sim sim = repository.findByIccidForUpdate(iccid).orElseThrow(() -> new SimNotFoundException(iccid));
        if (sim.activatedAt() != null) {
            return;
        }

        Instant activatedAt = clock.instant();
        repository.save(new Sim(sim.iccid(), sim.msisdn(), activatedAt, null));
        UUID eventId = UUID.randomUUID();
        outbox.save(new OutboxEvent(
                eventId,
                sim.iccid(),
                SIM_ACTIVATED_TOPIC,
                toJson(new SimActivated(eventId, sim.iccid(), sim.msisdn(), activatedAt)),
                activatedAt));
    }

    @Transactional
    public void deactivate(String iccid) {
        Sim sim = repository.findByIccidForUpdate(iccid).orElseThrow(() -> new SimNotFoundException(iccid));
        if (sim.deactivatedAt() != null) {
            return;
        }
        if (sim.activatedAt() == null) {
            throw new SimNotActivatedException(iccid);
        }

        Instant deactivatedAt = clock.instant();
        repository.save(new Sim(sim.iccid(), sim.msisdn(), sim.activatedAt(), deactivatedAt));
        UUID eventId = UUID.randomUUID();
        outbox.save(new OutboxEvent(
                eventId,
                sim.iccid(),
                SIM_DEACTIVATED_TOPIC,
                toJson(new SimDeactivated(eventId, sim.iccid(), sim.msisdn(), deactivatedAt)),
                deactivatedAt));
    }

    private String toJson(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialise " + event.getClass().getSimpleName(), exception);
        }
    }
}
