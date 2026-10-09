package com.carddraft.routers;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * The body of a decision.
 *
 * @param decision {@code approve} or {@code reject}; case and surrounding space are ignored,
 *                 because this is a person's answer rather than a machine's enum
 */
public record DecisionRequest(
        @NotBlank(message = "decision must be approve or reject")
        @Pattern(regexp = "(?i)\\s*(approve|reject)\\s*",
                message = "decision must be approve or reject")
        String decision) {
}
