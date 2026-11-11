package com.harnesslab.sim.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.yaml.snakeyaml.Yaml;

/** Checks the Java code against docs/contracts/sim-events.asyncapi.yaml, using the Spring Boot configuration. */
@JsonTest
@EnableConfigurationProperties(KafkaProperties.class)
class MessageContractTest {

    private static final String ICCID = "8949000000000000001";
    private static final String MSISDN = "491700000001";
    private static final Instant ACTIVATION_TIME = Instant.parse("2026-01-02T03:04:05Z");

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KafkaProperties kafkaProperties;

    @Test
    void sim_activated_as_sent_matches_the_contract() throws Exception {
        SimRepository simRepository = mock(SimRepository.class);
        OutboxRepository outbox = mock(OutboxRepository.class);
        when(simRepository.findByIccidForUpdate(ICCID)).thenReturn(Optional.of(new Sim(ICCID, MSISDN, null)));
        SimActivationService service = new SimActivationService(
                simRepository, outbox, objectMapper, Clock.fixed(ACTIVATION_TIME, ZoneOffset.UTC));

        service.activate(ICCID);

        ArgumentCaptor<OutboxEvent> row = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(row.capture());
        assertEquals("sim.activated", row.getValue().topic());
        Set<ValidationMessage> errors = payloadSchema("SimActivated").validate(objectMapper.readTree(row.getValue().payload()));
        assertTrue(errors.isEmpty(), "sim.activated differs from the contract: " + errors);
    }

    @Test
    void sim_ordered_that_matches_the_contract_can_be_read() throws Exception {
        String message = "{\"iccid\":\"" + ICCID + "\",\"msisdn\":\"" + MSISDN + "\",\"orderId\":\"order-1\"}";
        Set<ValidationMessage> errors = payloadSchema("SimOrdered").validate(objectMapper.readTree(message));
        assertTrue(errors.isEmpty(), "the sample is not valid against the contract: " + errors);

        Map<String, Object> consumerProperties = kafkaProperties.buildConsumerProperties(null);
        try (JsonDeserializer<Object> deserializer = new JsonDeserializer<>()) {
            deserializer.configure(consumerProperties, false);
            Object read = deserializer.deserialize("sim.ordered", message.getBytes(StandardCharsets.UTF_8));
            assertEquals(new SimOrdered(ICCID, MSISDN, "order-1"), read);
        }
    }

    @SuppressWarnings("unchecked")
    private JsonSchema payloadSchema(String message) throws Exception {
        Map<String, Object> contract;
        try (var in = new java.io.FileInputStream(new File("docs/contracts/sim-events.asyncapi.yaml"))) {
            contract = new Yaml().load(in);
        }
        Map<String, Object> components = (Map<String, Object>) contract.get("components");
        Map<String, Object> messages = (Map<String, Object>) components.get("messages");
        Map<String, Object> payload = (Map<String, Object>) ((Map<String, Object>) messages.get(message)).get("payload");
        JsonNode schema = objectMapper.valueToTree(payload);
        SchemaValidatorsConfig config = new SchemaValidatorsConfig();
        config.setFormatAssertionsEnabled(true);
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schema, config);
    }
}
