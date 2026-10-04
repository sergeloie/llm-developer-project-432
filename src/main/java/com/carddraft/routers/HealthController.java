package com.carddraft.routers;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.carddraft.core.ReadinessProbe;

@RestController
@RequestMapping("/health")
public class HealthController {

    private final ReadinessProbe readinessProbe;

    public HealthController(ReadinessProbe readinessProbe) {
        this.readinessProbe = readinessProbe;
    }

    /**
     * Liveness answers whether this process can serve at all. It deliberately touches no
     * dependency: a database outage must not restart every instance, because restarting does
     * not repair the database.
     */
    @GetMapping("/live")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, String> live() {
        return Map.of("status", "UP");
    }

    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        Map<String, Object> report = readinessProbe.check();
        HttpStatus status = "UP".equals(report.get("status")) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(report);
    }
}
