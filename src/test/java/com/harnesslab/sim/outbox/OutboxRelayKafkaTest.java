package com.harnesslab.sim.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnesslab.sim.service.SimActivationService;
import com.harnesslab.sim.types.SimOrdered;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.yaml.snakeyaml.Yaml;

@Testcontainers
@SpringBootTest
class OutboxRelayKafkaTest {

    private static final String ICCID = "8949000000000000011";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("sim")
            .withUsername("sim")
            .withPassword("sim");

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    @Autowired
    private SimActivationService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Test
    void a5_activation_event_reaches_kafka_without_calling_activate_again() throws Exception {
        service.registerOrdered(new SimOrdered(ICCID, "491700000011", "order-11"));
        service.activate(ICCID);

        ConsumerRecord<String, String> record = awaitRecord();

        assertEquals(ICCID, record.key());
        JsonNode event = new ObjectMapper().readTree(record.value());
        assertMatchesContract(event, messagePayload("SimActivated"));
        assertEquals(ICCID, event.get("iccid").asText());
        UUID eventId = UUID.fromString(event.get("eventId").asText());
        long deadline = System.currentTimeMillis() + 30_000;
        Integer sent = 0;
        while (sent == 0 && System.currentTimeMillis() < deadline) {
            sent = jdbc.queryForObject(
                    "select count(*) from outbox where id = ? and sent_at is not null", Integer.class, eventId);
            Thread.sleep(200);
        }
        assertEquals(1, sent);
    }

    @Test
    void sim_ordered_contract_message_is_read_and_registered() throws Exception {
        Map<String, Object> schema = messagePayload("SimOrdered");
        Map<String, String> values = Map.of(
                "iccid", "8949000000000000012",
                "msisdn", "491700000012",
                "orderId", "contract-order");
        Set<String> required = Set.copyOf((List<String>) schema.get("required"));
        assertEquals(
                required,
                Set.of(Arrays.stream(SimOrdered.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .toArray(String[]::new)));
        Map<String, Object> payload = new LinkedHashMap<>();
        required.forEach(name -> payload.put(name, values.get(name)));
        JsonNode message = new ObjectMapper().valueToTree(payload);
        assertMatchesContract(message, schema);

        kafkaTemplate.send("sim.ordered", values.get("iccid"), message.toString()).get();

        long deadline = System.currentTimeMillis() + 30_000;
        Integer registered = 0;
        while (registered == 0 && System.currentTimeMillis() < deadline) {
            registered = jdbc.queryForObject(
                    "select count(*) from sim where iccid = ?", Integer.class, values.get("iccid"));
            Thread.sleep(200);
        }
        assertEquals(1, registered);
        assertEquals(
                values.get("msisdn"),
                jdbc.queryForObject("select msisdn from sim where iccid = ?", String.class, values.get("iccid")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> messagePayload(String messageName) throws IOException {
        Map<String, Object> document;
        try (var contract = Files.newInputStream(Path.of("docs/contracts/sim-events.asyncapi.yaml"))) {
            document = new Yaml().load(contract);
        }
        Map<String, Object> components = (Map<String, Object>) document.get("components");
        Map<String, Object> messages = (Map<String, Object>) components.get("messages");
        Map<String, Object> message = (Map<String, Object>) messages.get(messageName);
        return (Map<String, Object>) message.get("payload");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> properties(Map<String, Object> schema) {
        return (Map<String, Object>) schema.get("properties");
    }

    @SuppressWarnings("unchecked")
    private void assertMatchesContract(JsonNode message, Map<String, Object> schema) {
        Map<String, Object> properties = properties(schema);
        for (String required : (List<String>) schema.get("required")) {
            assertTrue(message.has(required), "missing required contract property: " + required);
        }
        message.fieldNames().forEachRemaining(name -> {
            if (!properties.containsKey(name)) {
                assertTrue(
                        !Boolean.FALSE.equals(schema.get("additionalProperties")),
                        "additional property is not allowed: " + name);
                return;
            }
            assertTrue(message.get(name).isTextual(), "contract property must be a string: " + name);
            Map<String, Object> property = (Map<String, Object>) properties.get(name);
            assertEquals("string", property.get("type"), "unsupported contract property type: " + name);
            if ("uuid".equals(property.get("format"))) {
                assertNotNull(UUID.fromString(message.get(name).asText()));
            } else if ("date-time".equals(property.get("format"))) {
                assertNotNull(OffsetDateTime.parse(message.get(name).asText()));
            }
        });
    }

    private ConsumerRecord<String, String> awaitRecord() {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of("sim.activated"));
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    return record;
                }
            }
        }
        assertNotNull(null, "no sim.activated event received");
        return null;
    }
}
