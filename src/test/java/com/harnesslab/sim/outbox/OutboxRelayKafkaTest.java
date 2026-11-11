package com.harnesslab.sim.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnesslab.sim.service.SimActivationService;
import com.harnesslab.sim.types.SimOrdered;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

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

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @BeforeEach
    void clearDatabase() {
        jdbc.update("delete from outbox");
        jdbc.update("delete from sim");
    }

    @Test
    void a5_activation_event_reaches_kafka_without_calling_activate_again() throws Exception {
        service.registerOrdered(new SimOrdered(ICCID, "491700000011", "order-11"));
        service.activate(ICCID);
        UUID eventId =
                jdbc.queryForObject("select id from outbox where topic = 'sim.activated'", UUID.class);

        ConsumerRecord<String, String> record = awaitRecord("sim.activated", eventId);

        assertEquals(ICCID, record.key());
        JsonNode event = new ObjectMapper().readTree(record.value());
        assertEquals(ICCID, event.get("iccid").asText());
        assertEquals(eventId, UUID.fromString(event.get("eventId").asText()));
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
    void deactivation_event_reaches_kafka_with_event_id_and_acknowledgement() throws Exception {
        String msisdn = "491700000011";
        service.registerOrdered(new SimOrdered(ICCID, msisdn, "order-11"));
        service.activate(ICCID);
        service.deactivate(ICCID);
        UUID eventId =
                jdbc.queryForObject("select id from outbox where topic = 'sim.deactivated'", UUID.class);

        ConsumerRecord<String, String> record = awaitRecord("sim.deactivated", eventId);

        assertEquals("sim.deactivated", record.topic());
        assertEquals(ICCID, record.key());
        JsonNode event = new ObjectMapper().readTree(record.value());
        assertEquals(ICCID, event.get("iccid").asText());
        assertEquals(msisdn, event.get("msisdn").asText());
        assertNotNull(event.get("deactivatedAt"));
        Instant persistedTime =
                jdbc.queryForObject("select deactivated_at from sim where iccid = ?", Instant.class, ICCID);
        Instant eventTime = Instant.parse(event.get("deactivatedAt").asText());
        assertTrue(Duration.between(persistedTime, eventTime).abs().compareTo(Duration.ofNanos(1_000)) <= 0);
        assertEquals(eventId, UUID.fromString(event.get("eventId").asText()));
        long deadline = System.currentTimeMillis() + 30_000;
        Integer sent = 0;
        while (sent == 0 && System.currentTimeMillis() < deadline) {
            sent = jdbc.queryForObject(
                    "select count(*) from outbox where id = ? and sent_at is not null", Integer.class, eventId);
            Thread.sleep(200);
        }
        assertEquals(1, sent);
    }

    private ConsumerRecord<String, String> awaitRecord(String topic, UUID expectedEventId) throws Exception {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    JsonNode event = new ObjectMapper().readTree(record.value());
                    if (expectedEventId.toString().equals(event.path("eventId").asText())) {
                        return record;
                    }
                }
            }
        }
        assertNotNull(null, "no " + topic + " event received");
        return null;
    }
}
