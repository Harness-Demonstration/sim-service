package com.harnesslab.sim.stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
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
class SimOrderedKafkaTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("sim").withUsername("sim").withPassword("sim");
    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
    }

    @Test
    void a12_rejected_messages_reach_dead_letter_unchanged_and_a_later_valid_order_is_stored() throws Exception {
        byte[] key = {(byte) 0xff, 1};
        byte[] invalid = "{\"iccid\":\"bad\",\"msisdn\":\"491700000001\"}".getBytes(StandardCharsets.UTF_8);
        byte[] unreadable = {(byte) 0xff, (byte) 0xfe, 0};
        byte[] valid = "{\"iccid\":\"8949000000000000001\",\"msisdn\":\"491700000001\",\"orderId\":\"order-1\"}"
                .getBytes(StandardCharsets.UTF_8);

        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class))) {
            for (byte[] value : new byte[][] {invalid, unreadable, null, valid}) {
                producer.send(new ProducerRecord<>("sim.ordered", 0, key, value)).get();
            }
        }

        List<ConsumerRecord<byte[], byte[]>> rejected = new ArrayList<>();
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class))) {
            consumer.subscribe(List.of("sim.ordered.dlt"));
            long deadline = System.currentTimeMillis() + 60_000;
            while (rejected.size() < 3 && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(rejected::add);
            }
        }

        assertEquals(3, rejected.size());
        assertTrue(rejected.stream().anyMatch(record -> Arrays.equals(invalid, record.value())));
        assertTrue(rejected.stream().anyMatch(record -> Arrays.equals(unreadable, record.value())));
        assertTrue(rejected.stream().anyMatch(record -> record.value() == null));
        for (ConsumerRecord<byte[], byte[]> record : rejected) {
            assertArrayEquals(key, record.key());
            assertTrue(record.headers().lastHeader("reason").value().length > 0);
            assertNotNull(UUID.fromString(new String(record.headers().lastHeader("eventId").value(), StandardCharsets.UTF_8)));
        }
        long deadline = System.currentTimeMillis() + 30_000;
        int count = 0;
        while (count == 0 && System.currentTimeMillis() < deadline) {
            count = jdbc.queryForObject("select count(*) from sim where iccid = '8949000000000000001'", Integer.class);
            Thread.sleep(100);
        }
        assertEquals(1, count);
        assertEquals(1, jdbc.queryForObject("select count(*) from sim", Integer.class));
    }
}
