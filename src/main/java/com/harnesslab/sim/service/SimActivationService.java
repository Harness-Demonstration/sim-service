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
import com.harnesslab.sim.types.SimOrderedRejected;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SimActivationService {

    static final String SIM_ACTIVATED_TOPIC = "sim.activated";

    static final String SIM_DEACTIVATED_TOPIC = "sim.deactivated";

    static final String SIM_ORDERED_DEAD_LETTER_TOPIC = "sim.ordered.dead-letter";

    private static final Pattern ICCID_FORMAT = Pattern.compile("\\d{19,20}");

    private static final Pattern MSISDN_FORMAT = Pattern.compile("\\d{7,15}");

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

    /** Registers a raw sim.ordered message, or keeps it aside in the dead-letter topic with the reason. */
    public void handleOrdered(String rawMessage) {
        String reason = null;
        SimOrdered order = null;
        try {
            JsonNode node = rawMessage == null
                    ? null
                    : objectMapper.reader()
                            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .readTree(rawMessage);
            if (node == null || node.isMissingNode() || node.isNull()) {
                reason = "message is empty";
            } else if (!node.isObject()) {
                reason = "message is not a JSON object";
            } else {
                reason = invalidReason(node, "iccid", ICCID_FORMAT);
                if (reason == null) {
                    reason = invalidReason(node, "msisdn", MSISDN_FORMAT);
                }
                if (reason == null) {
                    JsonNode orderId = node.get("orderId");
                    order = new SimOrdered(
                            node.get("iccid").asText(),
                            node.get("msisdn").asText(),
                            orderId != null && orderId.isTextual() ? orderId.asText() : null);
                }
            }
        } catch (JsonProcessingException exception) {
            reason = "message is not valid JSON";
        }
        if (order != null) {
            registerOrdered(order);
        } else {
            deadLetter(rawMessage, reason);
        }
    }

    private static String invalidReason(JsonNode node, String field, Pattern format) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return field + " is missing";
        }
        if (!value.isTextual() || !format.matcher(value.asText()).matches()) {
            return field + " is malformed";
        }
        return null;
    }

    private void deadLetter(String rawMessage, String reason) {
        UUID eventId = UUID.randomUUID();
        String encodedPayload = Base64.getEncoder()
                .encodeToString(toJson(new SimOrderedRejected(eventId, reason, rawMessage))
                        .getBytes(StandardCharsets.UTF_8));
        outbox.save(new OutboxEvent(
                eventId,
                eventId.toString(),
                SIM_ORDERED_DEAD_LETTER_TOPIC,
                toJson(encodedPayload),
                clock.instant(),
                null,
                1));
    }

    public synchronized void registerOrdered(SimOrdered order) {
        if (repository.findByIccid(order.iccid()).isEmpty()) {
            repository.save(new Sim(order.iccid(), order.msisdn(), null));
        }
    }

    @Transactional
    public void activate(String iccid) {
        Sim sim = repository.findByIccidForUpdate(iccid).orElseThrow(() -> new SimNotFoundException(iccid));
        if (sim.deactivatedAt() != null) {
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
