package com.harnesslab.sim.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnesslab.sim.repository.OutboxRepository;
import com.harnesslab.sim.repository.SimRepository;
import com.harnesslab.sim.types.Sim;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Which sim.ordered messages are rejected and why. Delivery to sim.ordered.dlt is proven by SimOrderedKafkaTest. */
class SimOrderedValidationTest {
    private static final String ICCID = "8949000000000000001";
    private static final String MSISDN = "491700000001";

    @Test
    void a12_invalid_orders_are_rejected_with_a_reason_and_not_registered() {
        Map<String, String> reasons = Map.ofEntries(
                Map.entry("{}", "ICCID must be 19 or 20 digits"),
                Map.entry("{\"iccid\":null,\"msisdn\":\"" + MSISDN + "\"}", "ICCID must be 19 or 20 digits"),
                Map.entry("{\"iccid\":\"123\",\"msisdn\":\"" + MSISDN + "\"}", "ICCID must be 19 or 20 digits"),
                Map.entry("{\"iccid\":1234567890123456789,\"msisdn\":\"" + MSISDN + "\"}", "ICCID must be 19 or 20 digits"),
                Map.entry("{\"iccid\":\"" + ICCID + "\"}", "MSISDN must be 1 to 15 digits and start with a nonzero digit"),
                Map.entry("{\"iccid\":\"" + ICCID + "\",\"msisdn\":\"+491700000001\"}",
                        "MSISDN must be 1 to 15 digits and start with a nonzero digit"),
                Map.entry("{\"iccid\":\"" + ICCID + "\",\"msisdn\":\"0123\"}",
                        "MSISDN must be 1 to 15 digits and start with a nonzero digit"),
                Map.entry("{\"iccid\":\"" + ICCID + "\",\"msisdn\":\"1234567890123456\"}",
                        "MSISDN must be 1 to 15 digits and start with a nonzero digit"),
                Map.entry("{not json", "Invalid JSON"),
                Map.entry("{} {}", "Invalid JSON"),
                Map.entry("null", "Expected a JSON object"));
        SimRepository sims = mock(SimRepository.class);
        OutboxRepository outbox = mock(OutboxRepository.class);
        SimActivationService service = new SimActivationService(sims, outbox, new ObjectMapper(), Clock.systemUTC());

        reasons.forEach((value, reason) -> assertEquals(reason, assertThrows(InvalidSimOrderException.class,
                () -> service.registerOrderedMessage(value.getBytes(StandardCharsets.UTF_8))).getMessage(), value));
        assertEquals("Expected a JSON object",
                assertThrows(InvalidSimOrderException.class, () -> service.registerOrderedMessage(null)).getMessage());

        verify(sims, never()).save(any());
        verify(outbox, never()).save(any());
    }

    @Test
    void a12_valid_order_after_rejection_is_registered() {
        SimRepository sims = mock(SimRepository.class);
        when(sims.findByIccid(ICCID)).thenReturn(Optional.empty());
        SimActivationService service =
                new SimActivationService(sims, mock(OutboxRepository.class), new ObjectMapper(), Clock.systemUTC());
        assertThrows(InvalidSimOrderException.class,
                () -> service.registerOrderedMessage("{broken".getBytes(StandardCharsets.UTF_8)));

        service.registerOrderedMessage(("{\"iccid\":\"" + ICCID + "\",\"msisdn\":\"" + MSISDN
                + "\",\"orderId\":\"order-1\",\"extra\":1}").getBytes(StandardCharsets.UTF_8));

        verify(sims).save(new Sim(ICCID, MSISDN, null));
    }

    @Test
    void non_textual_or_missing_order_ids_are_rejected() {
        String prefix = "{\"iccid\":\"" + ICCID + "\",\"msisdn\":\"" + MSISDN + "\"";
        List<String> invalid = List.of(prefix + "}", prefix + ",\"orderId\":null}",
                prefix + ",\"orderId\":123}", prefix + ",\"orderId\":true}",
                prefix + ",\"orderId\":false}", prefix + ",\"orderId\":{}}",
                prefix + ",\"orderId\":[]}");
        SimRepository sims = mock(SimRepository.class);
        SimActivationService service =
                new SimActivationService(sims, mock(OutboxRepository.class), new ObjectMapper(), Clock.systemUTC());

        for (String value : invalid) {
            assertEquals("Invalid orderId", assertThrows(InvalidSimOrderException.class,
                    () -> service.registerOrderedMessage(value.getBytes(StandardCharsets.UTF_8))).getMessage(), value);
        }
        verify(sims, never()).save(any());
    }

    @Test
    void empty_string_order_id_is_valid() {
        SimRepository sims = mock(SimRepository.class);
        OutboxRepository outbox = mock(OutboxRepository.class);
        when(sims.findByIccid(ICCID)).thenReturn(Optional.empty());
        SimActivationService service = new SimActivationService(sims, outbox, new ObjectMapper(), Clock.systemUTC());

        service.registerOrderedMessage(("{\"iccid\":\"" + ICCID + "\",\"msisdn\":\"" + MSISDN
                + "\",\"orderId\":\"\"}").getBytes(StandardCharsets.UTF_8));

        verify(sims).save(new Sim(ICCID, MSISDN, null));
        verify(outbox, never()).save(any());
    }
}
