# Snapshot pause optimization

User approved the conservative optimization route on 2026-10-16. The baseline is documented in `docs/performance/2026-10-16-snapshot-baseline.md`.

The consistency boundary remains save/flush on the server thread, followed by an isolated snapshot while that thread waits, then world release and asynchronous archiving. No live-world hard links or copies spread across ticking worlds are introduced.

Implement these measured, bounded changes:

1. Record monotonic phase durations for preparation, scan, copy, world release and archive work in logs, without changing chat output.
2. Avoid the second real-path lookup when checking a child whose lexical path is already rooted in the canonical source directory. Compare its followed real path with that absolute normalized lexical path and enforce root containment. Keep identity/size/mtime checks before and after copying. Read destination attributes once instead of querying type and size separately.
3. Evaluate channel-based file copying in bounded chunks with cancellation checks. Retain it only if same-machine benchmarks improve; otherwise keep buffered copying.
4. Move orphan sweeping out of the paused interval only if coordinator ownership and cancellation can be preserved without adding an unsafe cleanup race. Prefer an explicit housekeeping phase on the existing staging worker before server preparation; it must complete before preparation and remain owned through timeout/shutdown. If this requires disproportionate lifecycle changes, leave sweeping in place and document the limitation rather than weakening the existing state machine.

Verify internal and external NTFS junction rejection, changed-source rejection, cancellation/partial-file cleanup, snapshot playerdata isolation and existing watchdog/shutdown tests. Benchmark the three original scenarios, report raw timings and medians with cache caveats, and do not equate stager timing with real game pause.

Package the accepted improvement as local 1.0.1, retaining the 1.0.0 Git tag and release assets. No GitHub publication is part of this optimization request.
