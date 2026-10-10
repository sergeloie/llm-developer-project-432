package com.carddraft.llm;

import java.util.List;

import com.carddraft.agents.CritiqueReport;
import com.carddraft.agents.ModelVerdict;
import com.carddraft.agents.ProductCard;
import com.carddraft.agents.ReviewIssue;
import com.carddraft.agents.SupplierFacts;
import com.carddraft.agents.SupportJudgement;

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
     * The next draft, given the reviewer's objections to the previous one.
     *
     * @param issues the reviewer's objections to the previous draft, empty on the first round
     */
    ProductCard draftCard(SupplierFacts facts, List<ReviewIssue> issues);

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

    /**
     * Drafts from retrieved fragments rather than from extracted facts.
     *
     * <p>A separate method because the two are genuinely different requests, not variants of one.
     * The facts path summarises a body of text; the context path reads a numbered set of fragments
     * and must attribute each claim to one of them. A single prompt-in, string-out method could not
     * express that difference, and a caller asking for a card would be unable to say which kind it
     * wanted — which is the mistake this interface exists to prevent.
     *
     * @param contextText the labelled fragments, as assembled and retained for verification
     * @param issues      citation failures from a previous round, empty on the first
     */
    ProductCard draftCardFromContext(String contextText, List<ReviewIssue> issues);

    /**
     * Reviews a card against the fragments it cites, rather than against extracted facts.
     *
     * <p>The reviewer's job differs in kind: it is checking attribution. Whether a value is in the
     * facts was already established; what has to be checked is whether the fragment the card names
     * for that value actually says it.
     */
    CritiqueReport reviewCardAgainstContext(String contextText, ProductCard draft);

    /**
     * Judges whether a card's claims are supported by the fragments it cites, for the metrics
     * harness.
     *
     * <p>A separate method because this is the one call in the service whose answer is used as a
     * measurement rather than as a decision, and that difference has to be visible at the call
     * site. A reviewer that says REGENERATE changes the card; a judge that says "unsupported"
     * produces a number in a report, and conflating them would make the harness's own uncertainty
     * indistinguishable from the pipeline's.
     * <p>A {@link SupportJudgement} rather than the raw text, for the same reason as
     * {@link #judgeInjection}: the prompt asks for an object, and a caller that re-parsed the text
     * would be a second place that shape is written down. Handing back text and parsing it in the
     * caller is what let the two disagree about the shape — the client was asked for a String, the
     * model answered an object, and no judgement could ever be read. Here the disagreement had no
     * catch above it either, so one unreadable answer ended the whole metrics run and wrote no
     * report at all.
     */
    SupportJudgement judgeSupport(String judgePrompt);

    /**
     * Judges whether a supplier fragment carries instructions aimed at a model.
     *
     * <p>A separate method from {@link #judgeSupport} even though both are judgements, because the
     * two are used for different decisions: this one decides whether a fragment is dropped, the
     * other only produces a number in a report. A method that did both would make it far too easy to
     * change a security decision by changing a reporting one.
     *
     * <p>A {@link ModelVerdict} rather than the raw text, because the prompt asks for an object and a
     * caller that re-parsed the text would be the second place that shape is written down. Handing
     * back text and parsing it here is what let the client and the caller disagree about the shape
     * in the first place: the client was asked for a String, the model answered an object, and every
     * verdict was unreadable — so every flagged fragment was dropped and the model pass did nothing.
     */
    ModelVerdict judgeInjection(String injectionPrompt);
}
