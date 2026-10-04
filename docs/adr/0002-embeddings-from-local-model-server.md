# Embeddings are computed by the local model server, not inside the JVM

Document chunks and queries are embedded by `text-embedding-embeddinggemma-300m` running in
the local OpenAI-compatible server, reached over its `/v1/embeddings` endpoint. We do not
load an ONNX copy of the model into the application. The task prefix templates required by
the model card are applied by our own `EmbeddingModel`, configured as settings, with separate
`embedQuery` and `embedDocument` entry points.

The deciding factor: we already depend on that server for chat completions, so this choice
makes the embedding model a configuration value rather than a bundled weight. It also
avoids reimplementing mean pooling and MRL re-normalisation by hand, where a subtle
mismatch with the model card would silently degrade retrieval.

**Considered Options**

- *ONNX Runtime in the JVM with a DJL tokenizer* — rejected for now. It is the more literal
  reading of "the model is loaded once", and it stays available behind the `EmbeddingModel`
  interface. Rejected because Gemma-3's ONNX export quality is unverified, manual pooling
  must match the card exactly, and it puts hundreds of megabytes of weights into the
  application's heap.

**Consequences**

The application's HTTP timeout for embeddings must absorb model load time, measured at 6s
cold for this model. Because the server keeps only one model resident, alternating between
embedding and chat calls costs 5–30s per switch; call records therefore separate
`coldStartMs` from `durationMs`.

The relevance threshold is calibrated against the reference set *with prefixes applied*, and
the calibration is a test rather than a note: measured cosine similarity for a correct
pair drops from 0.529 to 0.500 when the prescribed prefixes are added, while the margin
between a correct pair and an unrelated query rises from 0.374 to 0.447. A threshold
calibrated under one prefix configuration does not transfer to the other.