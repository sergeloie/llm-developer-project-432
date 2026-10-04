package com.carddraft.llm;

import java.util.List;

import com.carddraft.agents.CritiqueReport;
import com.carddraft.agents.ProductCard;
import com.carddraft.agents.SupplierFacts;

/**
 * The application's only route to a language model, and the only place that knows a provider
 * exists. It also owns the call record, so no call site can forget to be accounted for.
 *
 * <p>Methods are typed by intent rather than one prompt-in, string-out method. That is not
 * stylistic: a generic method cannot be substituted meaningfully, and this interface is the seam
 * every pipeline and security test observes. A caller asking for "facts" cannot accidentally
 * receive prose.
 *
 * <p>It also owns the response contract. Every method here returns something the application has
 * already checked, or throws with a message that says what was wrong — a caller never receives a
 * half-formed result and has to decide what to do about it.
 */
public interface LlmClient {

    SupplierFacts extractFacts(String supplierText);

    /**
     * @param issues the reviewer's objections to the previous draft, empty on the first round
     */
    ProductCard draftCard(SupplierFacts facts, List<String> issues);

    CritiqueReport reviewDraft(SupplierFacts facts, ProductCard draft);

    /**
     * Repairs one named field of the current draft, leaving the rest as it is.
     *
     * <p>Cheaper and faster than regenerating the whole card, and the difference is visible in the
     * call records: one call, and the untouched fields are identical afterwards.
     *
     * @param field   the field to fix
     * @param problem what is wrong with it, in the wording the contract uses
     */
    ProductCard repairCardField(ProductCard current, String field, String problem);
}
