# World-load backup design

## Goal

Add an independently configurable backup that runs once after entering a single-player world. It complements, rather than replaces, scheduled, manual, and exit backups.

## User-visible behavior

- Add `worldLoadEnabled`, defaulting to `true`, to the Forge configuration screen and `worldbackup.cfg`.
- When a new integrated-server instance and its client world are both available, arm a ten-second wall-clock delay.
- After ten seconds, request exactly one `WORLD_LOAD` backup for that world session.
- Leaving the world, switching worlds, or replacing the integrated-server instance resets the state. Re-entering a world arms a fresh backup.
- The master switch disables this trigger. Dedicated servers and screens without a loaded world never arm it.
- If another backup or maintenance operation is busy at the deadline, retain the pending world-load request and retry on later client ticks. Mark it complete only after the coordinator accepts the request, so a transient busy state does not silently lose the backup.
- The existing completion feedback remains authoritative: success shows `备份成功，用时 %s 秒`; failure shows `备份失败（%s 秒）：%s`. The elapsed value is measured with `System.nanoTime()` by the coordinator.
- Backup Manager displays the new trigger as localized `进入世界` / `World load`.

## Architecture

Extend `BackupTrigger` with `WORLD_LOAD`. Extend `WorldBackupConfig` and immutable `ConfigSnapshot` with `worldLoadEnabled`.

Add a small state machine beside `ClientEventHandler.BackupTimer`. It stores the current integrated-server identity, the ten-second deadline, and whether the request has been accepted. It uses wall-clock nanoseconds and saturating deadline arithmetic, matching the scheduled timer. It never calls storage code directly; it only calls `BackupCoordinator.request(server, WORLD_LOAD)`.

The scheduled timer remains independent. Entering a world arms both timers according to their own switches: world-load at ten seconds and scheduled at the configured interval. A successful world-load backup does not move the scheduled deadline.

## Error and concurrency behavior

The coordinator's existing single-flight arbitration remains unchanged. A rejected busy request is retried for the current world session. Once accepted, normal coordinator handling covers save consistency, staging, cancellation, chat feedback, and elapsed duration.

Changing `worldLoadEnabled` to false disarms any pending request immediately. Changing it back to true while the same world remains loaded arms a new ten-second delay. The master switch behaves the same way.

## Testing

Unit tests cover:

- default configuration and immutable snapshot capture;
- no trigger before ten seconds and one accepted trigger afterward;
- busy rejection followed by a later accepted retry;
- no duplicate request after acceptance;
- reset after leaving or switching integrated servers;
- disabling either the master switch or `worldLoadEnabled` disarms the timer;
- re-enabling in the same world starts a fresh ten-second delay;
- scheduled and world-load timers remain independent;
- `WORLD_LOAD` catalog/localization round trips;
- success and failure feedback continue to contain elapsed time.

The final JDK 8 build, JAR audit, README update, and BETA3 installation hash comparison are repeated before delivery.
