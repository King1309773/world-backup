# Snapshot Performance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce consistent snapshot staging time on large/many-file worlds with measured evidence.

**Architecture:** Keep the existing isolated staging and single-flight lifecycle. Optimize duplicate filesystem calls, evaluate bounded channel transfers, and log real stage timing. Use generated-world benchmarks and consistency/path/timeout regressions.

**Tech Stack:** Java 8, Forge 1.12.2, JUnit, PowerShell diagnostic launcher.

## Task 1: Preserve baseline and add behavioral regressions

Files: `tools/performance/SnapshotBaseline.java`, `tools/performance/run-baseline.ps1`, `docs/performance/2026-10-16-snapshot-baseline.md`, `src/test/java/com/rlcmj/worldbackup/runtime/WorldSnapshotControllerTest.java`.

- [x] Save and compile the existing diagnostic harness; commit baseline evidence on `perf/snapshot-staging`.
- [x] Add real filesystem tests for an internal junction and a source directory swapped to a junction during copy. Require `IOException`, no surviving stage, and unchanged external canary.
- [x] Add cancellation and staged metadata/content tests before changing copy logic. Observe a failing test for new timing visibility or the specific enhanced boundary, keeping existing safety tests green.

```java
assertThrows(IOException.class, () -> stager.stage(world));
assertFalse(Files.exists(staged));
assertArrayEquals(original, Files.readAllBytes(canary));
```

## Task 2: Optimize and measure the stager

Files: `src/main/java/com/rlcmj/worldbackup/runtime/WorldSnapshotController.java`, `src/main/java/com/rlcmj/worldbackup/runtime/BackupCoordinator.java`.

- [x] Add immutable phase measurements on staged snapshots/handles, and log prepare/release/archive boundaries with `System.nanoTime()`.
- [x] Use canonical source plus lexical child equality to replace the redundant no-follow real-path lookup. Retain source containment and pre/post identity checks.
- [x] Replace destination type/size double lookup with one `Files.readAttributes(..., NOFOLLOW_LINKS)` result.
- [x] Evaluate file-channel transfers in 8 MiB maximum chunks; cancellation before/after each chunk; fall back on bounded buffer copying if transfer makes no progress. Test exact contents, timestamps and empty files.
- [x] Run focused snapshot/coordinator tests, then `tools/performance/run-baseline.ps1`. Retain copying changes only when benchmarks justify them. Record any rejected candidate.
- [x] Inspect orphan-cleanup lifecycle. Move it only with explicit ownership/cancellation regressions; otherwise record why it stays in the paused interval.

## Task 3: Verify and hand off local 1.0.1

Files: `build.gradle`, `src/main/java/com/rlcmj/worldbackup/WorldBackup.java`, `README.md`, `docs/performance/2026-10-16-snapshot-results.md`.

- [x] Set build/mod version consistently to `1.0.1`; document phase log and measured results with the original baseline caveats.
- [x] Execute Java 8 `clean test build --offline --no-daemon --max-workers=1` with project TLS/proxy compatibility flags. Require zero failures/errors and inspect all skips.
- [x] Audit release JAR resources/classes and absence of tests/absolute local paths. Commit changes; keep `v1.0.0` unchanged.
- [x] Install the verified local 1.0.1 JAR into BETA3, move the known old 1.0.0 JAR to a non-`.jar` rollback filename, and compare source/installed SHA-256. Keep one active World Backup JAR. Leave GitHub release untouched.
- [x] Report measured improvements and any remaining real-game verification gap without claiming FPS or save/flush gains from synthetic tests.
