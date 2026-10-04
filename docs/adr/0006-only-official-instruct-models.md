# Only official instruct-tuned models are eligible for the two tiers

The service runs against a local OpenAI-compatible server that hosts many models, most of
them community merges. We use two: `qwen/qwen3.5-9b` as the main tier for generation, and
`qwen/qwen3-4b-2507` as the utility tier for review, classification, the injection gate,
and judging. Every other model on the machine is excluded, including the uncensored and
abliterated merges.

The reason is measured, not aesthetic. Both eligible models returned clean parseable JSON on
every probe. `gemma-4-12b-it-qat` spent 1200 tokens on internal reasoning and returned no
content at all; `nvidia-nemotron-labs-3-elastic-30b-a3b-nvfp4` did the same at 89 tokens.
This project rests on a strict response contract, and a model that returns nothing usable is
not a cheaper option, it is an unusable one. The utility tier additionally answered a
verdict-shaped request in 294ms and 10 tokens where the 9B spent 5064ms and 231 tokens,
which is why the cheaper model owns verdicts rather than prose.

**Considered Options**

- *Uncensored or abliterated merges as the main tier* — rejected. They would make the
  injection test in the trust step genuinely adversarial, but they degrade exactly the
  instruction-following this project depends on, and their absence of refusal training
  makes the critic a poor judge of hallucinated content.

**Consequences**

Model names are configuration with no fallback list, so a missing model fails at startup
rather than silently degrading a metric run. If the main tier proves too slow on long
contexts, swapping the two names is a configuration change and nothing else.