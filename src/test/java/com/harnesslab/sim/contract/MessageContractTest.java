package com.harnesslab.sim.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.harnesslab.sim.repository.OutboxRepository;
import com.harnesslab.sim.repository.SimRepository;
import com.harnesslab.sim.service.SimActivationService;
import com.harnesslab.sim.types.OutboxEvent;
import com.harnesslab.sim.types.Sim;
import com.harnesslab.sim.types.SimOrdered;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import java.io.File;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.support.serializer.JsonDeserializer;

/** Checks the Java code against docs/contracts/sim-events.asyncapi.yaml. */
class MessageContractTest {

    private static final File CONTRACT = new File("docs/contracts/sim-events.asyncapi.yaml");
    private static final String ICCID = "8949000000000000001";
    private static final String MSISDN = "491700000001";
    private static final Instant ACTIVATION_TIME = Instant.parse("2026-01-02T03:04:05Z");

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, KafkaAutoConfiguration.class));

    @Test
    void sim_activated_as_sent_matches_the_contract() {
        context.run(ctx -> {
            ObjectMapper objectMapper = ctx.getBean(ObjectMapper.class);
            Map<String, Sim> sims = new ConcurrentHashMap<>();
            sims.put(ICCID, new Sim(ICCID, MSISDN, null));
            List<OutboxEvent> outbox = new ArrayList<>();
            SimActivationService service = new SimActivationService(
                    simRepository(sims), outboxRepository(outbox), objectMapper,
                    Clock.fixed(ACTIVATION_TIME, ZoneOffset.UTC));

            service.activate(ICCID);

            assertEquals(1, outbox.size());
            JsonNode sent = new ObjectMapper().readTree(outbox.get(0).payload());
            Optional<String> problems = violations("SimActivated", sent);
            assertTrue(problems.isEmpty(), () -> "sim.activated differs from the contract: " + problems.get());
        });
    }

    @Test
    void sim_ordered_that_matches_the_contract_can_be_read() {
        context.run(ctx -> {
            String message = """
                    {"iccid": "%s", "msisdn": "%s", "orderId": "order-1", "addedByAnotherService": true}
                    """.formatted(ICCID, MSISDN);
            Optional<String> problems = violations("SimOrdered", new ObjectMapper().readTree(message));
            assertTrue(problems.isEmpty(), () -> "test message does not match the contract: " + problems.get());

            Map<String, Object> properties = ctx.getBean(KafkaProperties.class).buildConsumerProperties(null);
            try (JsonDeserializer<Object> deserializer = new JsonDeserializer<>()) {
                deserializer.configure(properties, false);
                Object read = deserializer.deserialize("sim.ordered", message.getBytes());
                assertEquals(new SimOrdered(ICCID, MSISDN, "order-1"), read);
            }
        });
    }

    @Test
    void the_contract_check_rejects_a_message_with_an_unknown_field() throws Exception {
        JsonNode message = new ObjectMapper().readTree("""
                {"eventId": "2b1b7d0e-8c3f-4a53-9d58-0d4b6f4b2a10", "iccid": "%s", "msisdn": "%s",
                 "activatedAt": "2026-01-02T03:04:05Z", "extra": 1}
                """.formatted(ICCID, MSISDN));

        assertFalse(violations("SimActivated", message).isEmpty());
    }

    private static Optional<String> violations(String messageName, JsonNode message) throws Exception {
        JsonNode payload = new YAMLMapper().readTree(CONTRACT).at("/components/messages/" + messageName + "/payload");
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(payload, config);
        var errors = schema.validate(message);
        return errors.isEmpty() ? Optional.empty() : Optional.of(errors.toString());
    }

    private static SimRepository simRepository(Map<String, Sim> sims) {
        return new SimRepository() {
            @Override
            public Optional<Sim> findByIccid(String iccid) {
                return Optional.ofNullable(sims.get(iccid));
            }

            @Override
            public Optional<Sim> findByIccidForUpdate(String iccid) {
                return findByIccid(iccid);
            }

            @Override
            public void save(Sim sim) {
                sims.put(sim.iccid(), sim);
            }
        };
    }

    private static OutboxRepository outboxRepository(List<OutboxEvent> outbox) {
        return new OutboxRepository() {
            @Override
            public void save(OutboxEvent event) {
                outbox.add(event);
            }

            @Override
            public List<OutboxEvent> lockPending(int limit) {
                return List.of();
            }
        };
    }
}
