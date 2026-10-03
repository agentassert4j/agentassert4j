# A Behavior-Regression Loop for AI

> A human-readable assessment for AI-host integrators and harness developers: why deterministic
> verdicts are a natural fit for AI self-correction loops, and how to drive the MCP surface
> responsibly. The machine contract (manifest descriptions, error envelopes, JSON schemas) is
> authoritative in the tool surface's self-description; this document covers the selection argument
> and the operating discipline.
> Companion pieces: [OPERATIONS §6.2 MCP integration](../OPERATIONS.md#62-mcp-integration-the-ai-self-verification-loop),
> [README](../README.md).

## 1. The missing piece of the self-correction loop

Let an AI edit its own prompts, run its own tests, iterate on its own — the biggest risk is not that
it edits badly, but that **it breaks something elsewhere and doesn't know**: one prompt tightened,
another task's tool selection flips; a tool description reworded, the parameter format collapses. The
model looks at its own output in the moment and sees "fine" — because nothing holds up a ruler that
says "relative to the behavior you last approved, this changed".

That is the piece AgentAssert4j supplies: it turns "is the behavior still what it was" into a
**deterministic binary verdict**. What the AI receives is not an evaluation that needs re-interpretation
but a fact, programmable as-is.

## 2. Why deterministic verdicts are particularly AI-friendly

- **Binary, reproducible**: PASS / CHANGED is computed deterministically from the four structural
  fingerprint dimensions (tool-call set, parameter types, output field paths and types, plus the
  content rules and behavior constraints pinned into the baseline) — the same input re-judged at any
  time returns the same result. The AI never handles "82 this time, 85 last time" score drift, and
  there is no "the judge model is in a mood today" noise.
- **Zero-call judgment**: check / diff spends no LLM call and needs no API key — the loop can run it
  at high frequency; only an explicit re-drive (a controlled review) spends real calls, capped by the
  budget pool.
- **A complete machine contract**: every report is a single-line JSON (schema tag included); on
  failure the error envelope (`error/1`) carries `errorCode` / `hints[]` / `nextAction` — **the next
  action is self-explanatory**, so an AI host can self-heal without a manual.
- **Clean gate semantics**: exit codes split three ways (0 no difference / 1 a difference awaiting
  human adjudication / 2 environment or evidence trouble) — loop logic branches directly, no gray zone.
- **"Same or different" is separated from "better or worse"**: the verdict answers only the former.
  Direction is left to upstream policy or a human — that is a protection for the AI, not a limitation:
  you don't want your optimization loop standing on a black box that makes value judgments for you.

## 3. How the loop turns

```
record (real interactions — raw wire JSON in, three protocols)
  → check / diff (against the approved shape set, zero-call judgment)
  → CHANGED → a candidate lands → a human (or an authorized flow) accepts it into the set / rejects it
  → recheck green (PASS (shape i of n)) → continue to the next iteration
  → audit for reconciliation (every governance write: verb / actor / time / code anchor)
```

Two semantics especially useful during iteration:

- **Drafts don't block the gate**: judgment reads each invocation's **latest execution** only — the
  intermediate drafts of an AI's trial-and-error round never turn the whole loop red; unapproved
  earlier executions are disclosed transparently as `unapprovedEarlier` counts.
- **The stability probe**: before approving into the set (accept), measure stability with
  member-check — the `matched k of N` count plus the list of matching sessions. Read the count:
  `matched 2 of 3` recent neighbors is stable reproduction; `1 of N` hitting one ancient session is
  archaeology, not evidence. The JSON `isMember` boolean means only "any historical hit" and carries
  no threshold — **AI consumers should trust the count, not the boolean, before accepting**.

## 4. Governance discipline (the AI side's self-restraint)

- **Declare an identity**: governance verbs (establish / accept / reject / rollback) must carry an
  `approver`; the AI declares itself as `agent:<name>`; a governance write without an approver is
  refused by the server outright.
- **Mutation verbs under human authorization**: record / check / diff / report / verify are free to
  call autonomously any time; actions that change baseline truth (establish / accept / rollback)
  should run inside a human instruction or an authorized flow — the manifest descriptions state this
  requirement on each verb.
- **Carry the code anchor**: attaching `ref` (say, the current HEAD) when accepting costs nearly
  nothing, yet lets every governance write in audit answer "at which commit was this behavior last
  approved" — the self-correction loop's audit chain closes completely.
- **Three shared-database rules**: when sharing a database with other hosts — `--ci` failing closed
  on unestablished keys is design, not a fault; judge your own domain (narrow check/diff with
  task/invocation) and establish your own keys; a whole-database gate is legitimate only once every
  key has been established.

## 5. Honest boundaries

- The verdict never rates "better or worse"; wording differences never enter the verdict (they appear
  in reports as low-confidence references only). To pin wording, declare content rules
  (keywords/regexes) — and declarations bind **at the moment they are pinned into a baseline**.
- Rules and behavior constraints apply only to baselines the declaration was pinned into — editing the
  rules file on the candidate side never changes the verdict.
- A shared invocation legitimately holding several shapes is a normal citizen: approve each shape
  once, and every chain ending in an approved shape rechecks green; rollback restores the whole-set
  snapshot.
- The framework never drives your product's execution (a re-drive is a controlled review, not a
  product re-run); recording would rather drop data than block a business request.

## 6. Onboarding

Seventeen tools mirror the CLI verbs: `record` (the ingestion entry — raw wire JSON for
openai-chat / anthropic-messages / openai-responses), `check` / `diff` / `report` / `verify` /
`export` / `doctor` / `rules` / `graph` / `record-show`, the four governance verbs (`establish` /
`accept` / `reject` / `rollback`) + `audit`, `member-check`, `re-drive`. stdio transport, one jar to
start; the registration recipe and client matrix are in
[OPERATIONS §6.2](../OPERATIONS.md#62-mcp-integration-the-ai-self-verification-loop).
