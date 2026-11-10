package com.harnesslab.sim.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harnesslab.sim.api.SimController;
import com.harnesslab.sim.types.SimActivated;
import com.harnesslab.sim.types.SimOrdered;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SimActivationServiceTest {

    private KafkaTemplate<String, SimActivated> kafkaTemplate;
    private SimActivationService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        service = new SimActivationService(kafkaTemplate);
        mockMvc = MockMvcBuilders.standaloneSetup(new SimController(service)).build();
    }

    @Test
    void activatingAnOrderedSimPublishesOneEvent() throws Exception {
        service.onSimOrdered(new SimOrdered("iccid-1", "msisdn-1", "order-1"));

        mockMvc.perform(post("/sims/iccid-1/activate"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/sims/iccid-1/activate"))
                .andExpect(status().isNoContent());

        ArgumentCaptor<SimActivated> event = ArgumentCaptor.forClass(SimActivated.class);
        verify(kafkaTemplate).send(eq("sim.activated"), eq("iccid-1"), event.capture());
        assertEquals("iccid-1", event.getValue().iccid());
        assertEquals("msisdn-1", event.getValue().msisdn());
        assertNotNull(event.getValue().activatedAt());
    }

    @Test
    void activatingAnUnknownSimReturnsNotFoundWithoutPublishing() throws Exception {
        mockMvc.perform(post("/sims/unknown/activate"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(kafkaTemplate);
    }
}
