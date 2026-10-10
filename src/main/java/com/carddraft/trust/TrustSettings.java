package com.carddraft.trust;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How much trouble a document may be in before a person looks at it.
 *
 * <p>Two, and neither is zero.
 *
 * <p>The per-fragment budget is a tolerance rather than a prohibition. A false positive costs a
 * specification, and a supplier document that legitimately quotes a support email or lists a service
 * phone number should not have its cards destroyed over it. The model pass on top of the rules is
 * what makes that tolerance affordable: a fragment the rules flagged gets a second opinion before it
 * is dropped.
 *
 * <p>The document-level budget is the opposite: crossing it means the document as a whole is trying
 * to be something else. A technical specification does not contain three separate attempts to
 * address the model, and at that point the question is not which fragments to keep but whether this
 * document should have been uploaded at all. Two is low on purpose — high enough not to trip on one
 * stray phrase quoted from another vendor's manual, low enough that a document which is
 * mostly attack gets stopped before it is mostly read.
 */
@Validated
@ConfigurationProperties("card.trust")
public record TrustSettings(
        @DefaultValue("2") int maxSuspiciousChunks,

        /**
         * Characters of context a fragment may contain before it is treated as an opaque insertion.
         *
         * <p>The longest legitimate fragment in the supplied documents is well under this, so the
         * rule cannot fire on a real specification; it catches an attack that hides behind
         * tokenisation rather than behind a keyword.
         */
        @DefaultValue("120") int maxOpaqueFragmentLength) {

    public TrustSettings {
        if (maxSuspiciousChunks < 0) {
            throw new IllegalArgumentException(
                    "a negative budget would mean every document is escalated; got " + maxSuspiciousChunks);
        }
    }
}
