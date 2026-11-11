package com.harnesslab.sim.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
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
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.support.serializer.JsonDeserializer;

/** Checks the Java code against docs/contracts/sim-events.asyncapi.yaml. */
class MessageContractTest {

    private static final Path CONTRACT = Path.of("docs/contracts/sim-events.asyncapi.yaml");
    private static final String ICCID = "8949000000000000001";
    private static final String MSISDN = "491700000001";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void sim_activated_as_sent_matches_the_contract() throws Exception {
        List<OutboxEvent> saved = new ArrayList<>();
        ObjectMapper springMapper = bootObjectMapper();
        SimActivationService service = new SimActivationService(
                new SingleSimRepository(new Sim(ICCID, MSISDN, null)),
                new CollectingOutbox(saved),
                springMapper,
                Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC));

        service.activate(ICCID);

        assertEquals(1, saved.size());
        assertEquals("sim.activated", saved.get(0).topic());
        Set<ValidationMessage> errors = payloadSchema("SimActivated").validate(JSON.readTree(saved.get(0).payload()));
        assertTrue(errors.isEmpty(), "sim.activated does not match the contract: " + errors);
    }

    @Test
    void sim_ordered_that_matches_the_contract_can_be_read() throws Exception {
        JsonNode schema = contractPayload("SimOrdered");
        ObjectNode message = JSON.createObjectNode();
        schema.get("properties").fieldNames().forEachRemaining(name -> message.put(name, "value-of-" + name));
        message.put("fieldAddedByAnotherTeam", "x");
        assertTrue(payloadSchema("SimOrdered").validate(message).isEmpty(), "sample must match the contract");

        // Same deserializer settings as the listener in application.yml.
        try (JsonDeserializer<SimOrdered> deserializer = new JsonDeserializer<>(SimOrdered.class, false)) {
            SimOrdered order = deserializer.deserialize("sim.ordered", message.toString().getBytes(StandardCharsets.UTF_8));

            assertEquals(new SimOrdered("value-of-iccid", "value-of-msisdn", "value-of-orderId"), order);
        }
    }

    /** The ObjectMapper the application injects, configured by Spring Boot from application.yml. */
    private static ObjectMapper bootObjectMapper() {
        ObjectMapper[] mapper = new ObjectMapper[1];
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(context -> mapper[0] = context.getBean(ObjectMapper.class));
        return mapper[0];
    }

    private static JsonNode contractPayload(String message) throws IOException {
        JsonNode contract = new ObjectMapper(new YAMLFactory()).readTree(Files.readString(CONTRACT));
        return contract.at("/components/messages/" + message + "/payload");
    }

    private static JsonSchema payloadSchema(String message) throws IOException {
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(contractPayload(message), config);
    }

    private record SingleSimRepository(Sim sim) implements SimRepository {
        @Override
        public Optional<Sim> findByIccid(String iccid) {
            return Optional.of(sim);
        }

        @Override
        public Optional<Sim> findByIccidForUpdate(String iccid) {
            return Optional.of(sim);
        }

        @Override
        public void save(Sim updated) {
        }
    }

    private record CollectingOutbox(List<OutboxEvent> events) implements OutboxRepository {
        @Override
        public void save(OutboxEvent event) {
            events.add(event);
        }

        @Override
        public List<OutboxEvent> lockPending(int limit) {
            return List.of();
        }
    }
}
