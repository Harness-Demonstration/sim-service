package com.harnesslab.sim.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnesslab.sim.repository.OutboxRepository;
import com.harnesslab.sim.repository.SimRepository;
import com.harnesslab.sim.types.OutboxEvent;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SimOrderedValidationTest {
    private static final String ICCID = "8949000000000000001";
    private static final String MSISDN = "491700000001";

    @Test
    void a12_invalid_orders_are_not_registered_and_are_queued_with_the_original_bytes_and_reason() throws Exception {
        List<String> invalid = List.of(
                "{}",
                "{\"iccid\":null,\"msisdn\":\"" + MSISDN + "\"}",
                "{\"iccid\":\"123\",\"msisdn\":\"" + MSISDN + "\"}",
                "{\"iccid\":1234567890123456789,\"msisdn\":\"" + MSISDN + "\"}",
                "{\"iccid\":\"" + ICCID + "\"}",
                "{\"iccid\":\"" + ICCID + "\",\"msisdn\":\"+491700000001\"}",
                "{\"iccid\":\"" + ICCID + "\",\"msisdn\":\"0123\"}",
                "{\"iccid\":\"" + ICCID + "\",\"msisdn\":\"1234567890123456\"}",
                "{not json",
                "null",
                "{} {}");
        SimRepository sims = mock(SimRepository.class);
        OutboxRepository outbox = mock(OutboxRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        SimActivationService service = new SimActivationService(
                sims, outbox, mapper, Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC));
        byte[] key = {(byte) 0xff, 1};

        for (String value : invalid) {
            service.registerOrderedMessage(value.getBytes(StandardCharsets.UTF_8), key);
        }
        service.registerOrderedMessage(null, null);

        verify(sims, never()).save(any());
        ArgumentCaptor<OutboxEvent> events = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox, org.mockito.Mockito.times(invalid.size() + 1)).save(events.capture());
        for (int i = 0; i < invalid.size(); i++) {
            OutboxEvent event = events.getAllValues().get(i);
            assertEquals("sim.ordered.dlt", event.topic());
            var envelope = mapper.readTree(event.payload());
            assertEquals(invalid.get(i), new String(Base64.getDecoder().decode(envelope.get("value").asText()), StandardCharsets.UTF_8));
            assertEquals(Base64.getEncoder().encodeToString(key), envelope.get("key").asText());
            assertTrue(envelope.get("reason").asText().length() > 0);
        }
        assertTrue(mapper.readTree(events.getAllValues().get(invalid.size()).payload()).get("value").isNull());
    }

    @Test
    void a12_valid_order_after_rejection_is_registered() {
        SimRepository sims = mock(SimRepository.class);
        OutboxRepository outbox = mock(OutboxRepository.class);
        when(sims.findByIccid(ICCID)).thenReturn(Optional.empty());
        SimActivationService service = new SimActivationService(sims, outbox, new ObjectMapper(), Clock.systemUTC());
        service.registerOrderedMessage("{broken".getBytes(StandardCharsets.UTF_8), null);

        service.registerOrderedMessage(("{\"iccid\":\"" + ICCID + "\",\"msisdn\":\"" + MSISDN
                + "\",\"orderId\":\"order-1\",\"extra\":1}").getBytes(StandardCharsets.UTF_8), null);

        verify(sims).save(new com.harnesslab.sim.types.Sim(ICCID, MSISDN, null));
        verify(outbox).save(any());
    }

    @Test
    void non_textual_or_missing_order_ids_are_rejected() throws Exception {
        String prefix = "{\"iccid\":\"" + ICCID + "\",\"msisdn\":\"" + MSISDN + "\"";
        List<String> invalid = List.of(prefix + "}", prefix + ",\"orderId\":null}",
                prefix + ",\"orderId\":123}", prefix + ",\"orderId\":true}",
                prefix + ",\"orderId\":false}", prefix + ",\"orderId\":{}}",
                prefix + ",\"orderId\":[]}");
        SimRepository sims = mock(SimRepository.class);
        OutboxRepository outbox = mock(OutboxRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        SimActivationService service = new SimActivationService(sims, outbox, mapper, Clock.systemUTC());
        byte[] key = {(byte) 0xff, 1};

        for (String value : invalid) {
            service.registerOrderedMessage(value.getBytes(StandardCharsets.UTF_8), key);
        }

        verify(sims, never()).save(any());
        ArgumentCaptor<OutboxEvent> events = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox, org.mockito.Mockito.times(invalid.size())).save(events.capture());
        for (int i = 0; i < invalid.size(); i++) {
            OutboxEvent event = events.getAllValues().get(i);
            assertEquals("sim.ordered.dlt", event.topic());
            var envelope = mapper.readTree(event.payload());
            assertEquals(invalid.get(i), new String(Base64.getDecoder().decode(envelope.get("value").asText()), StandardCharsets.UTF_8));
            assertEquals(Base64.getEncoder().encodeToString(key), envelope.get("key").asText());
            assertEquals("Invalid orderId", envelope.get("reason").asText());
        }
    }

    @Test
    void empty_string_order_id_is_valid() {
        SimRepository sims = mock(SimRepository.class);
        OutboxRepository outbox = mock(OutboxRepository.class);
        when(sims.findByIccid(ICCID)).thenReturn(Optional.empty());
        SimActivationService service = new SimActivationService(sims, outbox, new ObjectMapper(), Clock.systemUTC());

        service.registerOrderedMessage(("{\"iccid\":\"" + ICCID + "\",\"msisdn\":\"" + MSISDN
                + "\",\"orderId\":\"\"}").getBytes(StandardCharsets.UTF_8), null);

        verify(sims).save(new com.harnesslab.sim.types.Sim(ICCID, MSISDN, null));
        verify(outbox, never()).save(any());
    }

    @Test
    void rejected_order_remains_pending_when_dead_letter_publish_fails() {
        SimRepository sims = mock(SimRepository.class);
        OutboxRepository outbox = mock(OutboxRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);
        SimActivationService service = new SimActivationService(sims, outbox, new ObjectMapper(), clock);
        service.registerOrderedMessage("{broken".getBytes(StandardCharsets.UTF_8), null);
        ArgumentCaptor<OutboxEvent> captured = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(captured.capture());
        OutboxEvent event = captured.getValue();
        when(outbox.lockPending(100)).thenReturn(List.of(event));
        OutboxRelayService relay = new OutboxRelayService(outbox, clock);

        assertEquals(0, relay.relayPending(ignored -> CompletableFuture.failedFuture(new IllegalStateException("Kafka down"))));
        verify(outbox).save(any());
        assertEquals(1, relay.relayPending(ignored -> CompletableFuture.completedFuture(null)));
        verify(outbox, org.mockito.Mockito.times(2)).save(captured.capture());
        assertEquals(clock.instant(), captured.getAllValues().get(captured.getAllValues().size() - 1).sentAt());
    }
}
