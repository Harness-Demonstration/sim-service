package com.harnesslab.sim.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.harnesslab.sim.repository.OutboxRepository;
import com.harnesslab.sim.repository.SimRepository;
import com.harnesslab.sim.service.SimActivationService;
import com.harnesslab.sim.types.OutboxEvent;
import com.harnesslab.sim.types.Sim;
import com.harnesslab.sim.types.SimOrdered;
import com.harnesslab.sim.types.SimOrderedRejected;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.support.serializer.JsonDeserializer;

class MessageContractTest {

    private static final Path CONTRACT = Path.of("docs/contracts/sim-events.asyncapi.yaml");
    private static final String ICCID = "8949000000000000001";
    private static final String MSISDN = "491700000001";

    @Test
    void sim_activated_as_sent_matches_the_contract() throws Exception {
        List<OutboxEvent> saved = new ArrayList<>();
        OutboxRepository outbox = new OutboxRepository() {
            @Override
            public void save(OutboxEvent event) {
                saved.add(event);
            }

            @Override
            public List<OutboxEvent> lockPending(int limit) {
                return List.of();
            }
        };
        Clock clock = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);
        new SimActivationService(new SingleSimRepository(new Sim(ICCID, MSISDN, null)), outbox, springObjectMapper(), clock)
                .activate(ICCID);
        String json = saved.get(0).payload();

        Set<ValidationMessage> errors = schema("SimActivated").validate(json, InputFormat.JSON);

        assertTrue(errors.isEmpty(), () -> "sim.activated does not match the contract: " + errors + " in " + json);
    }

    @Test
    void sim_deactivated_as_sent_matches_the_contract() throws Exception {
        List<OutboxEvent> saved = new ArrayList<>();
        OutboxRepository outbox = new OutboxRepository() {
            @Override
            public void save(OutboxEvent event) {
                saved.add(event);
            }

            @Override
            public List<OutboxEvent> lockPending(int limit) {
                return List.of();
            }
        };
        Clock clock = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);
        Sim activatedSim = new Sim(ICCID, MSISDN, Instant.parse("2026-01-01T03:04:05Z"));
        new SimActivationService(new SingleSimRepository(activatedSim), outbox, springObjectMapper(), clock)
                .deactivate(ICCID);
        String json = saved.get(0).payload();

        Set<ValidationMessage> errors = schema("SimDeactivated").validate(json, InputFormat.JSON);

        assertTrue(errors.isEmpty(), () -> "sim.deactivated does not match the contract: " + errors + " in " + json);
    }

    @Test
    void sim_ordered_matching_the_contract_can_be_read() throws Exception {
        String json = "{\"iccid\":\"" + ICCID + "\",\"msisdn\":\"" + MSISDN + "\",\"orderId\":\"order-1\"}";
        assertTrue(schema("SimOrdered").validate(json, InputFormat.JSON).isEmpty(), "fixture must match the contract");

        assertEquals(new SimOrdered(ICCID, MSISDN, "order-1"), read(json));
    }

    @Test
    void sim_ordered_with_fields_the_contract_allows_beyond_the_required_ones_can_be_read() throws Exception {
        String json = "{\"iccid\":\"" + ICCID + "\",\"msisdn\":\"" + MSISDN + "\",\"orderId\":\"order-1\",\"extra\":1}";
        assertTrue(schema("SimOrdered").validate(json, InputFormat.JSON).isEmpty(), "fixture must match the contract");

        assertEquals(new SimOrdered(ICCID, MSISDN, "order-1"), read(json));
    }

    @Test
    void sim_ordered_rejected_with_event_id_matches_the_contract() throws Exception {
        String json = new ObjectMapper()
                .writeValueAsString(new SimOrderedRejected(
                        UUID.fromString("5b1874b6-2065-4e8c-82e8-ded0c03ab02b"),
                        "message is not valid JSON",
                        "{not json"));

        assertTrue(schema("SimOrderedRejected").validate(json, InputFormat.JSON).isEmpty());
    }

    private static ObjectMapper springObjectMapper() {
        ObjectMapper[] holder = new ObjectMapper[1];
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(context -> holder[0] = context.getBean(ObjectMapper.class));
        return holder[0];
    }

    private static SimOrdered read(String json) {
        try (JsonDeserializer<SimOrdered> deserializer = new JsonDeserializer<>()) {
            deserializer.configure(
                    Map.of(
                            JsonDeserializer.TRUSTED_PACKAGES, "com.harnesslab.sim.types",
                            JsonDeserializer.VALUE_DEFAULT_TYPE, SimOrdered.class.getName(),
                            JsonDeserializer.USE_TYPE_INFO_HEADERS, false),
                    false);
            return deserializer.deserialize("sim.ordered", json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static JsonSchema schema(String message) throws Exception {
        JsonNode payload = new ObjectMapper(new YAMLFactory())
                .readTree(Files.readString(CONTRACT))
                .at("/components/messages/" + message + "/payload");
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(payload, config);
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
        public void save(Sim sim) {
        }
    }
}
