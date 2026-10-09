package com.carddraft.agents;

/**
 * The utility model's answer to the one question the injection gate asks.
 *
 * <p>Lives here rather than beside the detector that frames the question, because the client that
 * parses the answer is in the model layer and the trust layer already depends on that layer. Nesting
 * it in the detector would have made the dependency run the other way.
 *
 * <p>A record rather than a {@code String} for the reason {@code CritiqueReport} is one: the shape of
 * a model's answer is part of the contract, and a shape expressed as prose in one caller and parsed
 * by hand in another is a shape the two can disagree about.
 *
 * @param suspicious whether the fragment is addressed to a model rather than to a reader, or null
 *                    when the model did not say. Null must not read as false: a gate that opens
 *                    when the model is silent is a gate that opens on every outage.
 * @param reason     why, in one sentence. Never blank: a verdict nobody can read is not a verdict,
 *                   and the sentence is what a person reads when a supplier asks why their document
 *                   lost a fragment
 */
public record ModelVerdict(Boolean suspicious, String reason) {
}