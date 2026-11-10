package com.harnesslab.sim.api;

import com.harnesslab.sim.service.SimActivationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/sims")
public class SimController {

    private final SimActivationService simActivationService;

    public SimController(SimActivationService simActivationService) {
        this.simActivationService = simActivationService;
    }

    @PostMapping("/{iccid}/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void activate(@PathVariable String iccid) {
        if (!simActivationService.activate(iccid)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
