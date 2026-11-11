package com.harnesslab.sim.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnesslab.sim.repository.OutboxRepository;
import com.harnesslab.sim.repository.SimRepository;
import com.harnesslab.sim.service.SimActivationService;
import com.harnesslab.sim.types.Sim;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SimActivationControllerTest {

    @Test
    void a3_unknown_iccid_returns_404_and_publishes_nothing() throws Exception {
        OutboxRepository outbox = mock(OutboxRepository.class);
        SimRepository repository = mock(SimRepository.class);
        when(repository.findByIccidForUpdate("8949000000000000001")).thenReturn(Optional.empty());
        SimActivationService service = new SimActivationService(
                repository, outbox, new ObjectMapper(), Clock.systemUTC());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SimActivationController(service))
                .setControllerAdvice(new SimExceptionHandler())
                .build();

        mvc.perform(post("/sims/{iccid}/activate", "8949000000000000001"))
                .andExpect(status().isNotFound());

        verify(outbox, never()).save(any());
    }

    @Test
    void a10_deactivating_a_never_activated_sim_returns_409_and_publishes_nothing() throws Exception {
        OutboxRepository outbox = mock(OutboxRepository.class);
        SimRepository repository = mock(SimRepository.class);
        when(repository.findByIccidForUpdate("8949000000000000001"))
                .thenReturn(Optional.of(new Sim("8949000000000000001", "491700000001", null)));
        MockMvc mvc = mvc(repository, outbox);

        mvc.perform(post("/sims/{iccid}/deactivate", "8949000000000000001"))
                .andExpect(status().isConflict());

        verify(outbox, never()).save(any());
    }

    @Test
    void a10_deactivating_an_unknown_iccid_returns_404() throws Exception {
        OutboxRepository outbox = mock(OutboxRepository.class);
        SimRepository repository = mock(SimRepository.class);
        when(repository.findByIccidForUpdate("8949000000000000001")).thenReturn(Optional.empty());

        mvc(repository, outbox).perform(post("/sims/{iccid}/deactivate", "8949000000000000001"))
                .andExpect(status().isNotFound());

        verify(outbox, never()).save(any());
    }

    @Test
    void a9_deactivating_an_already_deactivated_sim_returns_204_and_publishes_nothing() throws Exception {
        OutboxRepository outbox = mock(OutboxRepository.class);
        SimRepository repository = mock(SimRepository.class);
        Instant now = Instant.parse("2026-01-02T03:04:05Z");
        when(repository.findByIccidForUpdate("8949000000000000001"))
                .thenReturn(Optional.of(new Sim("8949000000000000001", "491700000001", now, now)));

        mvc(repository, outbox).perform(post("/sims/{iccid}/deactivate", "8949000000000000001"))
                .andExpect(status().isNoContent());

        verify(outbox, never()).save(any());
    }

    private static MockMvc mvc(SimRepository repository, OutboxRepository outbox) {
        SimActivationService service = new SimActivationService(
                repository, outbox, new ObjectMapper(), Clock.systemUTC());
        return MockMvcBuilders.standaloneSetup(new SimActivationController(service))
                .setControllerAdvice(new SimExceptionHandler())
                .build();
    }
}
