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
import com.harnesslab.sim.types.SimOrderedDeadLetter;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
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
    public void registerOrderedMessage(byte[] value, byte[] key) {
        JsonNode json;
        try {
            json = value == null ? null : objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value);
        } catch (IOException exception) {
            rejectOrder(value, key, "Invalid JSON");
            return;
        }
        if (json == null || !json.isObject()) {
            rejectOrder(value, key, "Expected a JSON object");
            return;
        }
        JsonNode iccid = json.get("iccid");
        JsonNode msisdn = json.get("msisdn");
        if (iccid == null || !iccid.isTextual() || !iccid.textValue().matches("[0-9]{19,20}")) {
            rejectOrder(value, key, "ICCID must be 19 or 20 digits");
            return;
        }
        if (msisdn == null || !msisdn.isTextual() || !msisdn.textValue().matches("[1-9][0-9]{0,14}")) {
            rejectOrder(value, key, "MSISDN must be 1 to 15 digits and start with a nonzero digit");
            return;
        }
        JsonNode orderId = json.get("orderId");
        if (orderId == null || !orderId.isTextual()) {
            rejectOrder(value, key, "Invalid orderId");
            return;
        }
        registerOrdered(new SimOrdered(iccid.textValue(), msisdn.textValue(), orderId.textValue()));
    }

    private void rejectOrder(byte[] value, byte[] key, String reason) {
        UUID eventId = UUID.randomUUID();
        outbox.save(new OutboxEvent(eventId, eventId.toString(), SimOrderedDeadLetter.TOPIC,
                toJson(new SimOrderedDeadLetter(encode(key), encode(value), reason)), clock.instant(), null));
    }

    private static String encode(byte[] bytes) {
        return bytes == null ? null : Base64.getEncoder().encodeToString(bytes);
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
                activatedAt,
                null,
                1));
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
                deactivatedAt,
                null,
                2));
    }

    private String toJson(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialise " + event.getClass().getSimpleName(), exception);
        }
    }
}
