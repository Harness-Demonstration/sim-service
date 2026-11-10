package com.harnesslab.sim.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.harnesslab.sim.repository.SimRepository;
import com.harnesslab.sim.service.SimActivationService;
import com.harnesslab.sim.types.SimActivated;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SimActivationControllerTest {

    @Test
    void c3_unknown_iccid_returns_404_and_publishes_nothing() throws Exception {
        List<SimActivated> events = new ArrayList<>();
        SimRepository repository = mock(SimRepository.class);
        when(repository.findByIccid("8949000000000000001")).thenReturn(Optional.empty());
        SimActivationService service = new SimActivationService(
                repository, events::add, Clock.systemUTC());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SimActivationController(service))
                .setControllerAdvice(new SimExceptionHandler())
                .build();

        mvc.perform(post("/sims/{iccid}/activate", "8949000000000000001"))
                .andExpect(status().isNotFound());

        assertEquals(List.of(), events);
    }
}
