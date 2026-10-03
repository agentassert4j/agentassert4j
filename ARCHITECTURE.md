# Architecture

> The code map for contributors: how the modules are layered, how an interaction flows from wire to
> verdict, where each SPI sits, and where to start for a given kind of change. Behavior semantics
> live in the per-domain specs under `guide/spec/` (the authoritative contracts, in Chinese — linked
> where relevant); this page records structure, which changes far more slowly than behavior.

## Module layering

Dependencies point one way — a layer never knows the layers above it. Any non-JDK import in core is
a defect (greppable in CI).

```text
L1  agentassert4j-core              model / spi / algorithm / result / util / config — java.base ONLY
L2  agentassert4j-recorder          Disruptor async out-of-band pipeline (core + slf4j-api)
L3  agentassert4j-spring-ai1/-ai2   framework adapters (tool-loop observation decoration)
    agentassert4j-langchain4j       per-round capture adapter (zero Spring)
    agentassert4j-cli               composition root: baseline/status/replay/verify/audit/…
L4  agentassert4j-starter-*         Boot auto-configuration (aggregates L1–L3 + storage)
storage plugin (parallel, core-only):
    agentassert4j-storage-sqlite    SQLite persistence (the v1 backend)
shipping forms:
    agentassert4j-cli-standalone    fully-shaded executable jar (slf4j-nop, single provider)
```

## The life of an interaction

```text
LLM call in the host app
  → adapter captures a record (structured messages; wire payloads only via MCP ingestion)
  → recorder (L2): async out-of-band ring buffer → batches → storage write
      overflow drops and meters (recorded = written + dropped + failed); never blocks the business thread
  → storage-sqlite: append-only interactions table, single file, PRAGMA user_version = 1
  → analysis side (CLI/MCP, separate process, same file):
      InvocationResolver   groups records into invocations (declared label > skeleton > template > request anchor)
                           and chains into tasks (declared taskKey > request text)
      FingerprintExtractor four structural dimensions per execution: tool calls / output structure /
                           content rules / behavior constraints (the latter two only when pinned)
      Comparator           latest execution vs the invocation's approved shape set → PASS / CHANGED (det-v1)
      alignment            per task: the two real chains paired step by step → missing / added / per-step verdicts
      BaselineManager      establish / accept / reject / rollback + governance event timeline
```

Key semantic anchors, all deterministic (no LLM in the verdict path, ever):

- **Identity priority**: declared label > skeleton hash > template hash > request anchor. The label
  survives prompt edits; the skeleton freezes dynamic templates; zero-declaration grouping works
  fully without declarations.
- **Judgment semantics are versioned** (`det-v1`): any change to "what verdict the same diff
  produces" must increment the version — old baselines are never silently reinterpreted.
- **Idempotency**: recordId is the database-global dedup key; writes are append-only.

## core package map

| Package | Owns | Notable types |
|---------|------|---------------|
| `model` | wire-domain value objects | `InteractionRecord`, `ToolCall` (tri-state `Boolean success`), `InvocationProfile`, `DeterministicFingerprint` |
| `spi` | the extension seams | `StorageRepository` (composes seven role stores: write/query/invocation/template/archive/governance/health), `LlmClient`, `RecordingInterceptor` — interfaces stay ≤5 methods each |
| `algorithm` | deterministic engines | `InvocationResolver`, `FingerprintExtractor`, `DeterministicComparator`, `DriftDetector`, `BaselineManager`, `InMemoryDependencyGraph` |
| `result` | verdict/report models | comparison results, drift/alignment reports, verdict enums |
| `util` | pure helpers | `HashUtil`, `RecursiveJsonParser` (depth-capped, degradation-safe), `TextDiffUtils` |
| `config` | JSON config parsing | `agentassert4j.json` / rules-file readers, safe defaults |

## Where to start, by change

| You want to… | Start in | Domain spec |
|--------------|----------|-------------|
| Add or change a CLI command | `agentassert4j-cli` (picocli commands, JSON report emission, exit codes) | `guide/spec/cli.md` (in Chinese) |
| Add a built-in behavior check | core verdict pipeline (the `rules` catalog) + the `rules` command | `guide/spec/judgment.md` (in Chinese) |
| Touch a fingerprint dimension or verdict semantics | `algorithm` (FingerprintExtractor, comparison pipeline) | `guide/spec/judgment.md` (in Chinese) |
| Change identity, grouping or task chains | `algorithm` (InvocationResolver) | `guide/spec/identity.md` (in Chinese) |
| Touch the recording pipeline or capture adapters | `agentassert4j-recorder`, the L3 adapter modules | `guide/spec/recording.md` (in Chinese) |
| Touch baseline governance (establish/accept/reject/rollback, audit) | `BaselineManager`, the governance event timeline | `guide/spec/governance.md` (in Chinese) |
| Add a framework adapter | a new L3 module; mirror the existing adapters field-by-field (see AGENTS.md's parallel-surface rule) | `guide/spec/sdk.md` (in Chinese) |
| Touch storage schema | `agentassert4j-storage-sqlite`; schema changes are add-only, version-gated | `guide/spec/storage.md` (in Chinese) |
| Add MCP tools | `agentassert4j-cli` MCP surface (17 tools mirror CLI verbs) | `guide/spec/mcp.md` (in Chinese) |

House rules that apply to every change are in [AGENTS.md](AGENTS.md) — bilingual doc pairs, comment
style, the three-layer audit, and the assertion-truth-source rules. Build and test mechanics are in
[CONTRIBUTING.md](CONTRIBUTING.md).

## Invariants worth protecting

- **core stays zero-dependency** (java.base only) — a release promise, machine-checkable.
- **Layering stays one-way** — lower layers never know upper layers.
- **`det-v1` never silently changes** — judgment-semantics changes are version bumps, never re-interpretations.
- **The recording pipeline never blocks a business thread** — drop and meter, never wait.
- **Reports are schema-tagged and frozen at birth** (`task-report/1`, `error/1`, …) — consumers rely on them.
