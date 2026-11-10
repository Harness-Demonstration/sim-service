package com.harnesslab.sim.api;

import com.harnesslab.sim.service.SimNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class SimExceptionHandler {

    @ExceptionHandler(SimNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public void handleSimNotFound() {
    }
}
