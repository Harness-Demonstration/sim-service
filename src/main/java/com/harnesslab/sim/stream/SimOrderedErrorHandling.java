package com.harnesslab.sim.stream;

import com.harnesslab.sim.service.InvalidSimOrderException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Errors in Kafka listeners. A rejected sim.ordered message goes to sim.ordered.dlt at once, with its original key and
 * value bytes and the headers {@code reason} and {@code eventId}. Any other failure, for example the database being
 * down, is retried every second until it succeeds, so no order is skipped.
 */
@Configuration
public class SimOrderedErrorHandling {

    static final String DEAD_LETTER_TOPIC = "sim.ordered.dlt";

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<byte[], byte[]> kafkaTemplate) {
        // Partition -1: Kafka picks the partition from the key, the dead-letter topic need not match sim.ordered.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate, (record, exception) -> new TopicPartition(DEAD_LETTER_TOPIC, -1));
        recoverer.setHeadersFunction((record, exception) -> {
            RecordHeaders headers = new RecordHeaders();
            headers.add("reason", reason(exception).getBytes(StandardCharsets.UTF_8));
            headers.add("eventId", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
            return headers;
        });
        DefaultErrorHandler handler =
                new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.addNotRetryableExceptions(InvalidSimOrderException.class);
        return handler;
    }

    /** The listener's exception arrives wrapped by Spring Kafka; the reason is the message of the rejection. */
    private static String reason(Exception exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof InvalidSimOrderException rejection) {
                return rejection.getMessage();
            }
        }
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
