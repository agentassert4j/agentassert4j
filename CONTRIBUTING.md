# Contributing to AgentAssert4j

Thanks for your interest in contributing! This page covers the mechanics; the full collaboration
contract — coding style, comment rules, testing standards, review protocol — lives in
[AGENTS.md](AGENTS.md) (in Chinese; it is the repository's single source of collaboration truth).

## Finding your way around

[ARCHITECTURE.md](ARCHITECTURE.md) is the code map — module layering, the life of an interaction,
the SPI surface, and a "where to start, by change" routing table into the per-domain specs.

## Building and testing

You need JDK 17+ to build (the test code itself stays Java 8 compatible, so JDK 8 contributors can run
the full suite too):

```bash
mvn -B test        # the full reactor must be green before any PR
```

Core has zero external dependencies (java.base only) — this is a release promise, verified in CI:

```bash
grep -rn "^import \(com\|org\)\." agentassert4j-core/src/main/java/   # must output nothing
```

## How to contribute

1. Open (or find) an issue describing the problem or proposal first — architectural conclusions listed
   in AGENTS.md are settled; reopen them via a new issue with evidence, not inside unrelated work;
2. Branch from the latest `main`, one concern per branch (`feat/…`, `fix/…`, `docs/…` — the type list
   matches Conventional Commits);
3. Bug fixes come with a failing-test-first reproduction in the same PR;
4. PRs require green CI plus review. Squash or rebase merges keep `main` linear;
5. Commit messages: Conventional Commits, English, imperative mood. Commits generated with AI
   assistance carry the footer `Assisted-by: AI coding agent`.

## Reporting bugs

Run the failing command with `--json` and attach the stdout output (schema-tagged, one document per
line) plus your `agentassert4j.json` (redact keys) and the framework version from
`agentassert4j --version`. Deterministic reproduction steps beat descriptions.

## Documentation

User-facing docs are bilingual pairs (`README.md`/`README.zh.md`, `OPERATIONS.md`/`OPERATIONS.zh.md`,
the two guides under `guide/`). Edit the language you write in — but an accepted change to one side
must land its mirror translation in the same batch; pair existence and link-language consistency are
machine-checked (conventions in AGENTS.md). Contributor docs (`guide/spec/`, AGENTS.md) are
Chinese-only by design.
