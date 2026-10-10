package com.carddraft.metrics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.springframework.stereotype.Component;

import com.carddraft.agents.ProductCard;
import com.carddraft.context.AssembledContext;
import com.carddraft.context.ContextAssembler;
import com.carddraft.documents.DocumentService;
import com.carddraft.llm.LlmClient;
import com.carddraft.repositories.ChunkSearchRepository;
import com.carddraft.repositories.DocumentsRepository;
import com.carddraft.repositories.JobContextRepository;
import com.carddraft.repositories.JobsRepository;
import com.carddraft.search.EmbeddingApplicationService;
import com.carddraft.search.SearchService;
import com.carddraft.trust.EscalatedException;
import com.carddraft.trust.Finding;
import com.carddraft.trust.TrustService;
import com.carddraft.trust.TrustSettings;

import tools.jackson.databind.ObjectMapper;

/**
 * Produces one card per document, through the whole path the service actually takes.
 *
 * <p>Register, parse, chunk, embed, retrieve, screen, assemble, generate, filter — in that
 * order and with no shortcuts. A harness that generated from the reference characteristics
 * would measure its prompts against a perfect extraction, which is exactly the number that
 * never moves when the extraction is what broke. Measuring the real path means a change
 * anywhere in it shows up here.
 *
 * <p>It reuses the service's own processing rather than a parallel copy. A metrics harness
 * with its own parsing would drift from the one the service uses, and the drift would appear
 * as a quality difference that no change to any prompt explains.
 *
 * <p>The context and the card travel as one return value rather than as a getter holding the
 * last card. A field set as a side effect is shared mutable state: two documents measured in
 * any overlap would read each other's cards, and the failure would look like a quality
 * regression rather than a race.
 */
@Component
public class CardGenerator {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CardGenerator.class);

    private final DocumentService documents;
    private final EmbeddingApplicationService embedding;
    private final SearchService search;
    private final ContextAssembler assembler;
    private final TrustService trust;
    private final TrustSettings trustSettings;
    private final JobContextRepository contexts;
    private final JobsRepository jobs;
    private final LlmClient llm;
    private final ObjectMapper mapper;

    /** One document's generation: the text the model was shown, and the card it produced. */
    public record Generated(AssembledContext context, ProductCard card) {}

    /**
     * The job a document's generation is recorded under.
     *
     * <p>One place rather than a format string in two files: the metrics report reads this job's
     * cost rows, and a second spelling of the name here would make the report read another job's
     * rows while the generation billed this one.
     */
    public static String jobIdFor(String filename) {
        return "metrics-" + filename;
    }

    public CardGenerator(
            DocumentService documents,
            EmbeddingApplicationService embedding,
            SearchService search,
            ContextAssembler assembler,
            LlmClient llm,
            TrustService trust,
            TrustSettings trustSettings,
            JobContextRepository contexts,
            JobsRepository jobs,
            ObjectMapper mapper) {
        this.documents = documents;
        this.embedding = embedding;
        this.search = search;
        this.assembler = assembler;
        this.llm = llm;
        this.trust = trust;
        this.trustSettings = trustSettings;
        this.contexts = contexts;
        this.jobs = jobs;
        this.mapper = mapper;
    }

    /**
     * The context the card was generated from, and the card itself.
     *
     * <p>Null never: "this document yielded no usable fragments" is a failure the caller
     * records, not an empty value it must interpret. A document whose fragments are mostly
     * attack is escalated to a person rather than generated from the survivors.
     *
     * @throws EscalatedException when the document as a whole needs a person
     */
    public Generated generate(String filename) {
        String jobId = jobIdFor(filename);
        jobs.createWithId(jobId, "pending", "{\"file\":" + quoted(filename) + "}");
        return com.carddraft.llm.JobLogContext.withJob(jobId, () -> {
            try {
                Generated generated = generateUnderJob(jobId, filename);
                jobs.complete(jobId, "metrics_complete", toJson(generated.card()));
                return generated;
            } catch (RuntimeException e) {
                jobs.fail(jobId, e.getMessage());
                throw e;
            }
        });
    }

    private Generated generateUnderJob(String jobId, String filename) {
        byte[] content = read(filename);

        DocumentsRepository.DocumentRow registered = documents.register(filename, content);
        DocumentsRepository.DocumentRow processed = documents.process(registered.id());

        // The service's own indexing, restricted to this document: a harness that embedded the whole
        // corpus would measure documents it is not currently scoring.
        embedding.embedDocument(processed.id(), 64);
        documents.settle(processed.id());

        var hits = search.search(
                filename, new ChunkSearchRepository.Filter(List.of(processed.id()), null), SearchService.Mode.HYBRID);

        AssembledContext assembled = assembler.assemble(jobId, hits);
        TrustService.Screened screened = trust.screen(assembled.chunks(), trustSettings.maxSuspiciousChunks());
        if (screened.escalated()) {
            throw new EscalatedException("escalated to a person: " + screened.reason());
        }
        if (screened.chunks().isEmpty()) {
            throw new EscalatedException("escalated to a person: all "
                    + assembled.chunks().size()
                    + " retrieved fragments were excluded as suspicious, so there is nothing "
                    + "safe to generate from");
        }

        AssembledContext retained = new AssembledContext(
                jobId, screened.chunks(), assembled.droppedAsDuplicate(), assembled.droppedOverBudget());
        contexts.save(retained);

        ProductCard raw = llm.draftCardFromContext(screened.render(), List.of());
        ProductCard card = filterOutput(jobId, raw);
        return new Generated(retained, card);
    }

    private String quoted(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * Screens the finished draft the same way the service screens it.
     *
     * <p>The model can invent a phone number that appears in no fragment, so screening the
     * input is not enough. Personal data is masked in the stored draft; every finding is
     * logged by kind and count, never by value. The mask keeps JSON structure — it replaces
     * inside string values — so the masked draft still parses.
     */
    private ProductCard filterOutput(String jobId, ProductCard card) {
        String json = toJson(card);
        Finding.Report report = trust.filterOutput(json);
        if (report.isClean()) {
            return card;
        }
        if (log.isWarnEnabled()) {
            log.warn("metrics_output_filtered job={} findings={}", jobId, report.summary());
        }
        boolean personal = report.findings().stream().anyMatch(Finding::isPersonal);
        if (!personal) {
            return card;
        }
        try {
            return mapper.readValue(report.maskedText(), ProductCard.class);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("masked draft no longer parses for " + jobId, e);
        }
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException(
                    "could not serialise " + value.getClass().getSimpleName(), e);
        }
    }

    private byte[] read(String filename) {
        try {
            return Files.readAllBytes(Path.of("data", filename));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not read data/" + filename, e);
        }
    }
}
