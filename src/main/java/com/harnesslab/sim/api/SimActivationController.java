package com.harnesslab.sim.api;

import com.harnesslab.sim.service.SimActivationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SimActivationController {

    private final SimActivationService service;

    public SimActivationController(SimActivationService service) {
        this.service = service;
    }

    @PostMapping("/sims/{iccid}/activate")
    public ResponseEntity<Void> activate(@PathVariable String iccid) {
        service.activate(iccid);
        return ResponseEntity.noContent().build();
    }
}
