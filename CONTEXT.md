# Product Card Drafting

A back-end service that turns supplier documents into a **draft** product card: every
characteristic points at the fragment it came from, and anything the documents did not
contain becomes an explicit missing field instead of an invented value. A human verifies
such a card in a minute, which is the whole point — the alternative is a content manager
spending an hour per product.

The service does not promise a publish-ready card. It promises traceability and honest
refusal, and everything else in this glossary follows from that promise.

## Language

### Inputs and structure

**Supplier Document**:
A file received from a supplier — a product passport, a commercial offer, a specification,
a manual. Treated as untrusted input: it may contain personal data and instructions aimed
at the model.
_Avoid_: File, upload, attachment

**Document State**:
Where a document is in its lifecycle — new, parsing, indexed, or rejected. Rejection is a
legitimate terminal state with a stated reason, not an error.
_Avoid_: Status of file, document stage

**Structural Unit**:
The smallest piece of a document that knows its own page: a page (PDF), a paragraph with
its section heading (DOCX), or a specification row (XLSX). Parsing produces structural
units; everything downstream consumes them.
_Avoid_: Page, block, element

**Section**:
The region of a document a structural unit belongs to, delimited by a heading. Sections
are the source of a chunk's title, which is also the slot the embedding model requires for
document-side text.
_Avoid_: Chapter, heading, category

**Rejection**:
The terminal outcome for a document that cannot be processed — a scan with no text layer,
an unreadable file — carrying a human-readable reason. An empty result without a reason is
a defect, because it is indistinguishable from a broken service.
_Avoid_: Failure, error, skip

### Retrieval units

**Chunk**:
A normalized fragment of text with metadata — document, page, section, article, brand —
and the unit of indexing and retrieval. Chunks come from overlapping slices of prose and
from whole table rows, which are never split.
_Avoid_: Fragment, piece, segment, passage

**Hybrid Search**:
The merge of vector search and full-text search, where a chunk found by both ranks above a
chunk found by one.
_Avoid_: Combined search, blended search

**Relevance Threshold**:
The score below which a retrieved chunk is treated as noise and dropped. It is calibrated
against the reference set, never chosen by judgement.
_Avoid_: Cutoff, minimum score

**Calibration**:
Deriving the relevance threshold from the reference set by locating where correct results
end and noise begins, and recording the reasoning.
_Avoid_: Tuning, threshold picking

**Context Snapshot**:
The exact set of chunks, already masked, that was placed in front of the model for one job.
Retained so citation verification can distinguish "this chunk exists" from "this chunk was
actually shown".
_Avoid_: Prompt, context window, retrieved set

### Generation

**Supplier Facts**:
Structured facts extracted from source text, including what was *not* found. The contract
between the extraction role and everything downstream.
_Avoid_: Extracted data, parsed document

**Draft Card**:
The deliverable. Characteristics that carry a source reference, a list of missing fields,
a confidence value, and the sources themselves. Explicitly not publish-ready.
_Avoid_: Card, product card, generated content

**Missing Field** (недостающие поля):
The name of a field the documents did not contain. The single most important output of the
service: it is what makes a draft honest.
_Avoid_: Null field, empty field, unknown attribute

**Source Reference**:
A chunk identifier cited by a characteristic. Valid only if the chunk both exists in the
database and was part of the context snapshot shown to the model.
_Avoid_: Citation, link, evidence

**Citation Verification**:
Checking every source reference against both conditions — existence and presence in the
context snapshot. The second condition is the one that matters: citing an existing chunk
the model never saw is fabrication with a plausible appearance.
_Avoid_: Reference check, link validation

**Confidence**:
A model-reported value in `[0, 1]` expressing how well the documents support the card.
Below the configured threshold, the job waits for a human rather than reporting success.
_Avoid_: Score, certainty

**Rewrite Loop**:
The bounded cycle of generate-then-review in which a failing review sends the draft back
with its issues. Budget is finite and explicit; an unbounded loop is a property of the
model, not a bug.
_Avoid_: Retry, refinement pass

**Approved / Awaiting Human / Rejected / Failed**:
The four terminal outcomes. The first three are business outcomes; only the last is an
incident. Merging them makes alerting impossible and success rate uncomputable.
_Avoid_: Done, error state, final status

### Trust

**Masking**:
Replacing detected personal data with typed labels before text reaches any model or log.
Applied to the chunk being assembled, never to the stored original, so that full-text
search still finds the values that were masked.
_Avoid_: Redaction, scrubbing, anonymisation

**Injection** (prompt injection):
An instruction embedded in a supplier document aimed at the model's behaviour rather than
at the reader. Supplier documents are a delivery path for it.
_Avoid_: Attack, hostile input, jailbreak

**Injection Gate**:
The check that decides whether a chunk may enter the context: cheap text rules first, then
a narrow question to the utility model for the chunks those rules flagged. When the
utility model is unreachable, a flagged chunk is treated as suspicious — a defence that
opens the door on its own failure is not a defence.
_Avoid_: Filter, sanitizer, moderation

**Output Filter**:
The final check of the finished card for leaked personal data and traces of injected
instructions, because the model may have complied partially.
_Avoid_: Post-check, sanitisation

### Accounting and evaluation

**Model Tier**:
The role a model plays — main for generation, utility for review, classification, the
injection gate, and judging. Two tiers, two settings entries.
_Avoid_: Model role, model kind

**Call Record**:
One model invocation with its token usage, duration, tier, model name, and cost. Written
by the model client itself, so that no call site can forget to record one.
_Avoid_: Log entry, usage record

**Reference Set**:
The per-document expectations used for measurement: expected characteristics, expected
missing fields, and probes naming the page where an answer should be found.
_Avoid_: Ground truth, golden set, fixtures

**Attribute Match**, **Citation Precision**, **Judge Verdict**:
The three quality metrics. The first compares values after normalisation, the second asks
whether a cited fragment really contains what was cited, the third is a model's assessment
of whether sources support the card — an estimate, not a truth.
_Avoid_: Accuracy, quality, score