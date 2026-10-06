package com.carddraft.metrics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;

import org.springframework.stereotype.Component;

/**
 * Writes the report where the next run can be compared with it.
 *
 * <p>Plain Markdown on purpose. The comparison the harness exists for is between two runs, and a
 * format that needs a tool to read is a format nobody reads twice. Anything that can diff a file
 * can diff this, which is the requirement.
 *
 * <p>Markdown tables rather than a CSV, because the per-document rows carry lists of missed
 * characteristics, and flattening those into columns loses exactly the detail a person opens the
 * report to find.
 */
@Component
public class MetricsReportWriter {

    public Path write(MetricsReport report, Path destination) throws IOException {
        if (destination.getParent() != null) {
            Files.createDirectories(destination.getParent());
        }
        Files.writeString(destination, render(report), StandardCharsets.UTF_8);
        return destination;
    }

    String render(MetricsReport report) {
        StringBuilder out = new StringBuilder();
        out.append("# Quality metrics\n\n");
        out.append("- run: `").append(report.runId()).append("`\n");
        out.append("- written: ").append(Instant.now()).append('\n');
        out.append("- scope: ").append(report.scope()).append("\n\n");

        out.append("Source support is a model's estimate, not a measurement. The other two are.\n\n");

        out.append("## Averages\n\n");
        out.append("| metric | value |\n");
        out.append("|---|---|\n");
        out.append(row("characteristic match", report.averageCharacteristicMatch()));
        out.append(row("citation precision", report.averageCitationPrecision()));
        long supportCovered = report.perDocument().stream().filter(DocumentMetrics::supportMeasured).count();
        out.append(report.perDocument().stream().anyMatch(m -> !m.supportMeasured())
                ? "| source support (estimate) | not measured for "
                        + (report.perDocument().size() - supportCovered) + " of "
                        + report.perDocument().size() + " documents |\n"
                : row("source support (estimate)", report.averageSourceSupport()));
        out.append('\n');

        out.append("## Per document\n\n");
        out.append("| document | match | precision | support | judged | weakest |\n");
        out.append("|---|---|---|---|---|---|\n");
        for (DocumentMetrics metrics : report.perDocument()) {
            out.append("| ").append(metrics.document())
                    .append(" | ").append(percent(metrics.characteristicMatch()))
                    .append(" | ").append(percent(metrics.citationPrecision()))
                    .append(" | ").append(metrics.supportMeasured()
                            ? percent(metrics.sourceSupport()) : "not measured")
                    .append(" | ").append(metrics.characteristicTotal())
                    .append(" | ").append(metrics.weakestMetric())
                    .append(" |\n");
        }
        out.append('\n');

        out.append("## Weakest\n\n");
        out.append(report.weakestSummary()).append("\n\n");

        for (DocumentMetrics metrics : report.perDocument()) {
            if (!metrics.supportMeasured()) {
                out.append("### ").append(metrics.document()).append(" — support judge unavailable\n\n");
                out.append(metrics.supportUnavailableReason()).append("\n\n");
            }
            if (!metrics.missedCharacteristics().isEmpty()) {
                out.append("### ").append(metrics.document()).append(" — not matched\n\n");
                for (String missed : metrics.missedCharacteristics()) {
                    out.append("- ").append(missed).append('\n');
                }
                out.append('\n');
            }
            if (!metrics.unsupportedClaims().isEmpty()) {
                out.append("### ").append(metrics.document()).append(" — unsupported by their source\n\n");
                for (String claim : metrics.unsupportedClaims()) {
                    out.append("- ").append(claim).append('\n');
                }
                out.append('\n');
            }
        }

        return out.toString();
    }

    private String row(String name, double value) {
        return "| " + name + " | " + percent(value) + " |\n";
    }

    private String percent(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}