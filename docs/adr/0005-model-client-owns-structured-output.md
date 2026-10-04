# The model client owns structured output, retries, and call accounting

All contact with a language model goes through one `LlmClient`, which owns timeout,
retry policy, response parsing, schema generation, and writing the call record. It is the
only place in the codebase that knows a provider exists. Its methods are typed by intent —
extract facts, draft a card, review a draft, judge, detect injection — not a single
`runAgent(prompt) → String`.

Spring AI's structured-output support was not used for the parsing path, because the
assignment requires handling four distinct response shapes (clean JSON, JSON in markdown
fences, JSON with prose around it, and unparseable garbage) plus repairing a single named
field without regenerating the whole card. Owning the conversion keeps that control visible
instead of delegated. Spring AI's own retry is disabled so that our retry loop is the only
one; otherwise the two multiply, the same failure mode as a retrying SDK client underneath
a hand-written retry cycle.

**Considered Options**

- *`StructuredOutputConverter` / `entity()`* — rejected. It generates the schema and
  parses the response, which is convenient, but the four-shape handling and per-field repair
  are the substance of this step rather than a detail to delegate.
- *Delegating retries to Spring AI* — rejected, for the reason above. The classification of
  which errors are worth retrying is the part worth owning.

**Consequences**

The JSON schema is sent to the model as prompt text, because local servers do not support
strict structured-output mode; the assignment permits this fallback explicitly. Prices per
model are configuration, and cost is `BigDecimal` — for local models the price is zero and
the arithmetic is covered by a unit test with non-zero prices instead, so the cloud path is
proven before it is used. A response that finishes with no content — measured on two of the
candidate models, which spent the whole token budget on reasoning and returned nothing — is
reported as a non-retryable error naming the model and the finish reason, because a retry reproduces the same deterministic failure at full cost.

**The provider address must include the `/v1` prefix.** Without it the SDK posts to
`/chat/completions`, and a local server answers HTTP 200 with an error object rather than a
404 — so the failure surfaces as `choices is not set` from the parser, pointing at the response
format instead of at the address. This is the same class of trap as the
chat-completions-versus-responses split, and it cost real debugging time; the live-model suite
exists partly so that a change of address is caught by a test rather than by a person.