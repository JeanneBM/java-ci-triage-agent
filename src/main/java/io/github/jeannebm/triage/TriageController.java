package io.github.jeannebm.triage;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
final class TriageController {

    private final TriageService service;

    TriageController(TriageService service) {
        this.service = service;
    }

    @PostMapping("/triage")
    TriageService.Result triage(@RequestBody Request request) {
        String log = request.log();
        if (log == null || log.isBlank() || log.length() > 12_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide a log of 1–12000 characters");
        }
        return service.triage(log);
    }

    @ExceptionHandler(TriageService.InvalidResponse.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    Map<String, String> invalidResponse() {
        return Map.of("error", "The model did not produce a valid triage result");
    }

    record Request(String log) {}
}
