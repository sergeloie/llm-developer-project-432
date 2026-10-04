package com.carddraft.routers;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.carddraft.agents.CardDraft;
import com.carddraft.services.PipelineOutcome;
import com.carddraft.services.PipelineService;

/**
 * The synchronous entry point: supplier text in, draft card out.
 *
 * <p>Two lines of logic by design. Anything more means something that belongs to a service has
 * leaked into the transport layer.
 *
 * <p>Deliberately synchronous. The local model answers in tens of seconds and a full pipeline in
 * minutes, so holding an HTTP connection open for that is the wrong shape — which is the
 * observation the asynchronous endpoint exists to act on.
 */
@RestController
@RequestMapping("/cards")
public class CardsController {

    private final PipelineService pipeline;

    public CardsController(PipelineService pipeline) {
        this.pipeline = pipeline;
    }

    public record GenerateCardRequest(String supplierText) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    public CardDraft generate(@RequestBody GenerateCardRequest request) {
        PipelineOutcome outcome = pipeline.run(request.supplierText());
        return outcome.draft();
    }

    @PostMapping("/outcome")
    public Map<String, Object> generateWithOutcome(@RequestBody GenerateCardRequest request) {
        PipelineOutcome outcome = pipeline.run(request.supplierText());
        return Map.of(
                "draft", outcome.draft(),
                "attempts", outcome.attempts(),
                "verdict", outcome.verdict());
    }
}
