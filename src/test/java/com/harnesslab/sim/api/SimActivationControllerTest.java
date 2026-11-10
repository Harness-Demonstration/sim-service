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
import java.time.Clock;
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
}
