package com.harnesslab.sim.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnesslab.sim.service.SimActivationService;
import com.harnesslab.sim.types.SimOrdered;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The outbox published by Debezium, end to end: PostgreSQL, Kafka and the Kafka Connect image from infra/debezium,
 * configured with infra/debezium/sim-outbox.json exactly as in the local stack (docs/design-docs/outbox.md).
 */
@Testcontainers
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DebeziumOutboxTest {

    private static final Network NETWORK = Network.newNetwork();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("sim").withUsername("sim").withPassword("sim")
            .withCommand("postgres", "-c", "wal_level=logical")
            .withNetwork(NETWORK).withNetworkAliases("postgres");

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0")
            .withNetwork(NETWORK).withListener("kafka:19092");

    @Container
    private static final GenericContainer<?> CONNECT = new GenericContainer<>(
            new ImageFromDockerfile("sim-service-debezium-connect", false)
                    .withFileFromPath(".", Path.of("infra/debezium")))
            .withNetwork(NETWORK)
            .withExposedPorts(8083)
            .withEnv(Map.of(
                    "BOOTSTRAP_SERVERS", "kafka:19092",
                    "GROUP_ID", "sim-connect",
                    "CONFIG_STORAGE_TOPIC", "connect_configs",
                    "OFFSET_STORAGE_TOPIC", "connect_offsets",
                    "STATUS_STORAGE_TOPIC", "connect_statuses",
                    "CONFIG_STORAGE_REPLICATION_FACTOR", "1",
                    "OFFSET_STORAGE_REPLICATION_FACTOR", "1",
                    "STATUS_STORAGE_REPLICATION_FACTOR", "1",
                    "OTEL_SERVICE_NAME", "debezium",
                    // Spans are created and their context is put on the message; there is no Jaeger to export to.
                    "OTEL_TRACES_EXPORTER", "none"))
            .dependsOn(KAFKA, POSTGRES)
            .waitingFor(Wait.forHttp("/connectors").forPort(8083).withStartupTimeout(Duration.ofMinutes(3)));

    private static boolean connectorRunning;

    @TestConfiguration
    static class Exporter {
        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }
    }

    @Autowired
    private SimActivationService service;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private InMemorySpanExporter spans;
    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("management.otlp.tracing.endpoint", () -> "http://localhost:1/v1/traces");
    }

    /** After Flyway created the outbox table: register the connector and wait until it streams the log. */
    @BeforeEach
    void startConnector() throws Exception {
        if (connectorRunning) {
            return;
        }
        String config = java.nio.file.Files.readString(Path.of("infra/debezium/sim-outbox.json"));
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(connectUri("/connectors/sim-outbox/config"))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(config)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() / 100 == 2, response::body);
        awaitTrue("replication slot sim_outbox is streaming", Duration.ofMinutes(2), () -> Boolean.TRUE.equals(jdbc.queryForObject(
                "select coalesce(bool_or(active), false) from pg_replication_slots where slot_name = 'sim_outbox'",
                Boolean.class)));
        connectorRunning = true;
    }

    @Test
    void a1_activation_is_published_with_the_iccid_as_key_and_the_event_id_as_header() throws Exception {
        String iccid = "8949000000000000031";
        service.registerOrdered(new SimOrdered(iccid, "491700000031", "order-31"));

        service.activate(iccid);

        ConsumerRecord<String, String> record = awaitRecords("sim.activated", iccid, 1).get(0);
        JsonNode event = JSON.readTree(record.value());
        assertEquals(iccid, event.get("iccid").asText());
        assertEquals("491700000031", event.get("msisdn").asText());
        Instant activatedAt = jdbc.queryForObject("select activated_at from sim where iccid = ?", Instant.class, iccid);
        // PostgreSQL keeps microseconds; the event carries the Clock's full precision (nanoseconds on Linux).
        assertEquals(activatedAt, Instant.parse(event.get("activatedAt").asText()).truncatedTo(ChronoUnit.MICROS));
        assertEquals(event.get("eventId").asText(), header(record, "id"));
        assertEquals(0, jdbc.queryForObject("select count(*) from outbox", Integer.class));
    }

    @Test
    void a8_deactivation_follows_its_activation_on_the_same_partition() throws Exception {
        String iccid = "8949000000000000032";
        service.registerOrdered(new SimOrdered(iccid, "491700000032", "order-32"));

        service.activate(iccid);
        service.deactivate(iccid);

        ConsumerRecord<String, String> activated = awaitRecords("sim.activated", iccid, 1).get(0);
        ConsumerRecord<String, String> deactivated = awaitRecords("sim.deactivated", iccid, 1).get(0);
        assertEquals(iccid, deactivated.key());
        assertEquals("491700000032", JSON.readTree(deactivated.value()).get("msisdn").asText());
        assertNotEquals(JSON.readTree(activated.value()).get("eventId"), JSON.readTree(deactivated.value()).get("eventId"));
        assertTrue(activated.timestamp() <= deactivated.timestamp());
    }

    @Test
    void a5_activation_while_kafka_is_down_is_published_when_kafka_is_back() throws Exception {
        String iccid = "8949000000000000033";
        service.registerOrdered(new SimOrdered(iccid, "491700000033", "order-33"));
        var docker = DockerClientFactory.instance().client();

        docker.pauseContainerCmd(KAFKA.getContainerId()).exec();
        try {
            service.activate(iccid);
            Thread.sleep(5_000);
        } finally {
            docker.unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }

        assertEquals(1, awaitRecords("sim.activated", iccid, 1).size());
    }

    @Test
    void rest_activation_debezium_and_the_kafka_message_form_one_trace() throws Exception {
        String iccid = "8949000000000000034";
        service.registerOrdered(new SimOrdered(iccid, "491700000034", "order-34"));

        assertTrue(rest.postForEntity("/sims/" + iccid + "/activate", null, String.class).getStatusCode().is2xxSuccessful());

        String[] traceparent = header(awaitRecords("sim.activated", iccid, 1).get(0), "traceparent").split("-");
        SpanData http = awaitSpan(span -> span.getName().startsWith("http post"));
        assertEquals(http.getTraceId(), traceparent[1]);
        // The parent in the message is a span Debezium created, not one of this service's spans.
        assertTrue(spans.getFinishedSpanItems().stream().noneMatch(span -> span.getSpanId().equals(traceparent[2])));
    }

    private static URI connectUri(String path) {
        return URI.create("http://" + CONNECT.getHost() + ":" + CONNECT.getMappedPort(8083) + path);
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        assertNotNull(header, "no " + name + " header on " + record.topic());
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private static List<ConsumerRecord<String, String>> awaitRecords(String topic, String key, int count) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> found = new java.util.ArrayList<>();
            long deadline = System.currentTimeMillis() + 90_000;
            while (found.size() < count && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(record -> {
                    if (key.equals(record.key())) {
                        found.add(record);
                    }
                });
            }
            assertEquals(count, found.size(), "messages with key " + key + " on " + topic + " within 90 seconds");
            return found;
        }
    }

    private SpanData awaitSpan(Predicate<SpanData> match) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Optional<SpanData> found = spans.getFinishedSpanItems().stream().filter(match).findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            Thread.sleep(200);
        }
        throw new AssertionError("span not exported within 30 seconds");
    }

    private static void awaitTrue(String what, Duration timeout, java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("timed out waiting until " + what);
    }
}
