package com.harnesslab.sim.tracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.harnesslab.sim.service.SimActivationService;
import com.harnesslab.sim.types.SimOrdered;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/** The REST activation, the outbox relay and the Kafka message form one trace (docs/design-docs/tracing.md). */
@Testcontainers
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TracingKafkaTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("sim").withUsername("sim").withPassword("sim");
    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    private static final String ICCID = "8949000000000000021";

    @TestConfiguration
    static class Exporter {
        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }
    }

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private SimActivationService service;
    @Autowired
    private InMemorySpanExporter spans;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("management.otlp.tracing.endpoint", () -> "http://localhost:1/v1/traces");
    }

    @Test
    void sim_activated_carries_the_trace_of_the_rest_activation() throws Exception {
        service.registerOrdered(new SimOrdered(ICCID, "491700000021", "order-21"));

        assertTrue(rest.postForEntity("/sims/" + ICCID + "/activate", null, String.class).getStatusCode().is2xxSuccessful());

        ConsumerRecord<byte[], byte[]> message = firstMessage("sim.activated");
        Header header = message.headers().lastHeader("traceparent");
        assertNotNull(header, "sim.activated has no traceparent header");
        String messageTraceId = new String(header.value(), StandardCharsets.UTF_8).split("-")[1];

        SpanData http = waitForSpan(span -> span.getName().startsWith("http post"));
        assertEquals(http.getTraceId(), messageTraceId);
        SpanData relay = waitForSpan(span -> span.getName().equals("outbox relay sim.activated"));
        assertEquals(http.getTraceId(), relay.getTraceId());
        SpanData send = waitForSpan(span -> span.getName().startsWith("sim.activated send"));
        assertEquals(relay.getSpanId(), send.getParentSpanId());
    }

    private SpanData waitForSpan(java.util.function.Predicate<SpanData> match) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Optional<SpanData> found = spans.getFinishedSpanItems().stream().filter(match).findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            Thread.sleep(200);
        }
        List<String> names = spans.getFinishedSpanItems().stream().map(SpanData::getName).toList();
        throw new AssertionError("span not exported within 30 seconds; exported: " + names);
    }

    private static ConsumerRecord<byte[], byte[]> firstMessage(String topic) {
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class))) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<byte[], byte[]> record : consumer.poll(Duration.ofMillis(500))) {
                    return record;
                }
            }
        }
        throw new AssertionError("no message on " + topic + " within 60 seconds");
    }
}
