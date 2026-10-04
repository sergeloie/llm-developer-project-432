package com.carddraft.llm;

import java.util.List;

import com.carddraft.agents.CardDraft;
import com.carddraft.agents.CritiqueReport;
import com.carddraft.agents.SupplierFacts;

/**
 * The application's only route to a language model, and the only place that knows a provider
 * exists. It also owns the call record, so no call site can forget to be accounted for.
 *
 * <p>Methods are typed by intent rather than one prompt-in, string-out method. That is not
 * stylistic: a generic method cannot be substituted meaningfully, and this interface is the seam
 * every pipeline and security test observes. A caller asking for "facts" cannot accidentally
 * receive prose.
 */
public interface LlmClient {

    SupplierFacts extractFacts(String supplierText);

    /**
     * @param issues the reviewer's objections to the previous draft, empty on the first round
     */
    CardDraft draftCard(SupplierFacts facts, List<String> issues);

    CritiqueReport reviewDraft(SupplierFacts facts, CardDraft draft);
}
