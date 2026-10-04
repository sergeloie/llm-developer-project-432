package com.carddraft.search;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Retrieval settings.
 *
 * <p>{@code maxVectorDistance} is calibrated, and the calibration is a test rather than a note —
 * {@code EmbeddingThresholdCalibrationTest} embeds every reference probe in
 * {@code data/golden_cards.json} and fails if this value no longer covers them.
 *
 * <p>Measured on the development machine, with the configured prefixes applied:
 *
 * <pre>
 * blender_passport.pdf  power            0.350
 * blender_passport.pdf  bowl volume      0.582
 * kettle_manual.pdf     power            0.354
 * kettle_spec.xlsx      KTL-1700         0.737
 * blender_kp.docx       BLD-800          0.764
 * </pre>
 *
 * <p>The two worst are the article-number and price-fragment probes, and the reason is the shape
 * of the comparison rather than the model: a probe's {@code text_contains} is a short fragment
 * ("2 190") while the characteristic it is checked against is a whole specification row. A short
 * query against a long answer is a harder comparison than production retrieval ever makes, so these
 * two set the ceiling rather than representing it.
 *
 * <p>That is also why the prefixes are load-bearing, and the measurement is the opposite of the
 * intuitive one. A question does <i>not</i> sit closer to its own answer with the prefixes: measured
 * on the same machine, "800 W" against "800 W" is 0.363 with them and 0.305 without. The two
 * templates put a question and an answer into different roles on purpose, so asking them to be
 * nearer each other asks the model to erase the distinction they exist to draw. What the prefixes
 * buy is separation — the gap between an answer and an unrelated fragment for the same question is
 * 0.372 with them and 0.274 without, roughly a third more. That gap is the quantity a threshold
 * can be placed inside, which is why it is the number measured and not the self-similarity.
 *
 * <p><b>Provisional, and the reason is worth stating plainly.</b> The lower bound is measured: every
 * reference probe is admitted, and
 * {@code EmbeddingThresholdCalibrationTest} fails if this value stops covering them. The upper bound
 * rests on a single unrelated fragment, which is an anecdote and not a distribution — the
 * neighbouring specification rows are the ones that will actually crowd a query, and they have not
 * been measured. The value below is therefore known to be generous rather than known to be right.
 * The next step is to embed every chunk of the supplied documents and record the distribution of
 * distances from each probe to everything it does not answer, then move this number to sit inside
 * that distribution. Until then it is recorded here as provisional rather than dressed up as
 * calibrated.
 */
@Validated
@ConfigurationProperties("card.search")
public record SearchSettings(

        @DefaultValue("0.80") double maxVectorDistance,

        /**
         * How deep each side of the fusion looks, and the most the caller will be handed.
         *
         * <p>Both default to 8, and {@code perListLimit} must be at least {@code limit} — otherwise
         * the fusion is asked for more results than either side was allowed to contribute, and the
         * shortfall is silent: a page comes back short with no error to explain it.
         */
        @DefaultValue("8") int perListLimit,

        /** Rank-fusion constant. The value from the original paper; 60 is not a tuned choice. */
        @DefaultValue("60") int rrfK,

        @DefaultValue("8") int limit) {

    public SearchSettings {
        if (maxVectorDistance <= 0 || maxVectorDistance > 2) {
            throw new IllegalArgumentException(
                    "maxVectorDistance is a cosine distance and must be in (0, 2]; got " + maxVectorDistance);
        }
        if (perListLimit < limit) {
            throw new IllegalArgumentException(
                    "perListLimit (" + perListLimit + ") must be at least limit (" + limit
                            + "), or the fusion cannot return a full page");
        }
    }

    /**
     * The settings the application runs with when nothing overrides them.
     *
     * <p>Exists so a test can calibrate against the same numbers production uses. Reading the
     * defaults out of a test's own literals is how a calibration test comes to assert something
     * true and irrelevant — it would still pass after someone changed the configured threshold,
     * which is precisely the drift it exists to catch.
     *
     * <p>Keep in step with the {@code @DefaultValue} literals above; there is no way to have a
     * {@code @DefaultValue} read a constant.
     */
    public static SearchSettings defaults() {
        return new SearchSettings(0.80, 8, 60, 8);
    }
}