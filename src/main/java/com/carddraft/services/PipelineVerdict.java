package com.carddraft.services;

/**
 * Whether a draft was accepted by the reviewer, or the budget ran out with a draft still in
 * hand.
 *
 * <p>Rejection here is a business outcome, not an incident. It is kept distinct from a failure
 * precisely so alerting and success rates can treat them differently.
 */
public enum PipelineVerdict {
    APPROVED,
    REJECTED
}
