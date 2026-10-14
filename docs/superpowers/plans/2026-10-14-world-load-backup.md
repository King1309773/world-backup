# World-load Backup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a default-enabled option that creates one backup ten seconds after each single-player world entry and reports the completed backup duration in chat.

**Architecture:** Extend the existing immutable configuration and backup trigger model, then add a separate wall-clock state machine beside the scheduled timer. The state machine retries a busy coordinator request until it is accepted, but never changes the scheduled timer's deadline. Existing coordinator completion handling remains responsible for localized success/failure messages and elapsed time.

**Tech Stack:** Java 8, Minecraft 1.12.2, Forge 14.23.5.2860, ForgeGradle 2.3, JUnit 4.13.2.

---

## File structure

- Modify `src/main/java/com/rlcmj/worldbackup/config/WorldBackupConfig.java`: declare the new Forge option.
- Modify `src/main/java/com/rlcmj/worldbackup/config/ConfigSnapshot.java`: capture the option immutably for backup jobs.
- Modify `src/main/java/com/rlcmj/worldbackup/core/BackupTrigger.java`: add the persisted `WORLD_LOAD` trigger.
- Modify `src/main/java/com/rlcmj/worldbackup/runtime/ClientEventHandler.java`: own and drive the ten-second entry timer.
- Modify `src/main/resources/assets/worldbackup/lang/en_us.lang`: English configuration and trigger labels.
- Modify `src/main/resources/assets/worldbackup/lang/zh_cn.lang`: Chinese configuration and trigger labels.
- Modify `src/test/java/com/rlcmj/worldbackup/config/ConfigSnapshotTest.java`: verify the new default and captured value.
- Modify `src/test/java/com/rlcmj/worldbackup/runtime/ClientEventHandlerTest.java`: verify entry timing, retries, reset, and timer independence.
- Modify `src/test/java/com/rlcmj/worldbackup/storage/CatalogStoreTest.java`: verify `WORLD_LOAD` persists in catalogs.
- Modify `README.md`: document the ninth option, ten-second behavior, trigger label, and final verification evidence.

### Task 1: Extend configuration and persisted trigger metadata

**Files:**
- Modify: `src/main/java/com/rlcmj/worldbackup/config/WorldBackupConfig.java`
- Modify: `src/main/java/com/rlcmj/worldbackup/config/ConfigSnapshot.java`
- Modify: `src/main/java/com/rlcmj/worldbackup/core/BackupTrigger.java`
- Modify: `src/main/resources/assets/worldbackup/lang/en_us.lang`
- Modify: `src/main/resources/assets/worldbackup/lang/zh_cn.lang`
- Test: `src/test/java/com/rlcmj/worldbackup/config/ConfigSnapshotTest.java`
- Test: `src/test/java/com/rlcmj/worldbackup/storage/CatalogStoreTest.java`

- [ ] **Step 1: Write failing configuration and catalog tests**

Reset `WorldBackupConfig.worldLoadEnabled = true` in the configuration test fixture, then add:

```java
@Test public void capturesWorldLoadBackupSetting() throws Exception {
    WorldBackupConfig.worldLoadEnabled = false;
    ConfigSnapshot snapshot = ConfigSnapshot.capture(temp.newFolder("game").toPath());
    assertFalse(snapshot.isWorldLoadEnabled());
}
```

Add a catalog round-trip assertion using the new enum value:

```java
@Test public void roundTripsWorldLoadTrigger() throws Exception {
    BackupCatalog catalog = BackupCatalog.empty("World1", "World 1");
    catalog.add(BackupVersion.full("load", 1L, BackupTrigger.WORLD_LOAD,
        "load.zip", 1L, 1L, 1L, new SnapshotManifest()));
    store.save(repo, catalog);
    assertEquals(BackupTrigger.WORLD_LOAD,
        store.load(repo).getVersions().get(0).getTrigger());
}
```

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```powershell
.\gradlew.bat test --tests '*ConfigSnapshotTest' --tests '*CatalogStoreTest' --no-daemon
```

Expected: compilation fails because `worldLoadEnabled`, `isWorldLoadEnabled`, and `WORLD_LOAD` do not exist.

- [ ] **Step 3: Add the configuration field, snapshot value, enum, and language keys**

Add the Forge option directly after `scheduledEnabled`:

```java
@Config.LangKey("worldbackup.config.world_load_enabled")
public static boolean worldLoadEnabled = true;
```

Add `worldLoadEnabled` to the `ConfigSnapshot` constructor, `capture` call, immutable field, and getter:

```java
public boolean isWorldLoadEnabled() {
    return worldLoadEnabled;
}
```

Extend the enum without renaming existing persisted values:

```java
public enum BackupTrigger {
    SCHEDULED,
    EXIT,
    MANUAL,
    WORLD_LOAD
}
```

Add matching language-key names/comments using the existing Forge config-key convention, plus:

```properties
worldbackup.trigger.world_load=World Load
```

```properties
worldbackup.trigger.world_load=进入世界
```

- [ ] **Step 4: Run the focused tests and verify GREEN**

Run the same focused Gradle command. Expected: both test classes pass and no existing trigger round trip changes.

- [ ] **Step 5: Commit the model change**

```powershell
git add src/main/java/com/rlcmj/worldbackup/config src/main/java/com/rlcmj/worldbackup/core/BackupTrigger.java src/main/resources/assets/worldbackup/lang src/test/java/com/rlcmj/worldbackup/config/ConfigSnapshotTest.java src/test/java/com/rlcmj/worldbackup/storage/CatalogStoreTest.java
git commit -m "feat: configure backups when entering worlds"
```

### Task 2: Trigger one accepted backup ten seconds after world entry

**Files:**
- Modify: `src/main/java/com/rlcmj/worldbackup/runtime/ClientEventHandler.java`
- Test: `src/test/java/com/rlcmj/worldbackup/runtime/ClientEventHandlerTest.java`

- [ ] **Step 1: Write failing state-machine tests**

Introduce a package-private `WorldLoadTimer` whose `poll` method returns whether the handler should attempt a request and whose `accepted` method marks the session complete. Test with `DELAY_NANOS = TimeUnit.SECONDS.toNanos(10)`:

```java
@Test public void worldLoadWaitsTenSecondsAndFiresOnceAfterAcceptance() {
    ClientEventHandler.WorldLoadTimer timer = new ClientEventHandler.WorldLoadTimer();
    Object server = new Object();
    assertFalse(timer.poll(server, true, 0L));
    assertFalse(timer.poll(server, true, DELAY_NANOS - 1L));
    assertTrue(timer.poll(server, true, DELAY_NANOS));
    timer.accepted(server);
    assertFalse(timer.poll(server, true, DELAY_NANOS + 1L));
}

@Test public void busyWorldLoadRequestRetriesUntilAccepted() {
    ClientEventHandler.WorldLoadTimer timer = new ClientEventHandler.WorldLoadTimer();
    Object server = new Object();
    timer.poll(server, true, 0L);
    assertTrue(timer.poll(server, true, DELAY_NANOS));
    assertTrue(timer.poll(server, true, DELAY_NANOS + 1L));
    timer.accepted(server);
    assertFalse(timer.poll(server, true, DELAY_NANOS + 2L));
}
```

Also test leaving the world with `poll(null, false, now)`, switching server identity, disabling and re-enabling in the same world, and independence from a separate `BackupTimer` instance.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
.\gradlew.bat test --tests '*ClientEventHandlerTest' --no-daemon
```

Expected: compilation fails because `WorldLoadTimer` does not exist.

- [ ] **Step 3: Implement the timer state machine**

Add a constant and state object:

```java
private static final long WORLD_LOAD_DELAY_NANOS = TimeUnit.SECONDS.toNanos(10L);
private final WorldLoadTimer worldLoadTimer = new WorldLoadTimer();
```

The timer records server identity, deadline, armed state, and accepted state. `poll` must reset when `server == null || !enabled`; a new identity or a newly enabled state arms `deadline = saturatingAdd(now, WORLD_LOAD_DELAY_NANOS)`; elapsed pending polls return `true`; `accepted(server)` only completes the matching session.

Make the saturating-add helper shared by both timer classes rather than duplicating overflow logic.

- [ ] **Step 4: Integrate polling after manual input and before the scheduled timer**

In `onClientTick`, compute:

```java
boolean worldLoadEnabled = loaded
    && WorldBackupConfig.enabled
    && WorldBackupConfig.worldLoadEnabled;
long now = System.nanoTime();
if (worldLoadTimer.poll(worldLoadEnabled ? server : null, worldLoadEnabled, now)
        && !coordinator.isBusy()) {
    if (coordinator.request(server, BackupTrigger.WORLD_LOAD)) {
        worldLoadTimer.accepted(server);
    }
}
```

Pass the same `now` value to the scheduled timer so the two clocks are deterministic but independent. Do not reset or advance `BackupTimer` when the world-load request is accepted.

- [ ] **Step 5: Run runtime and coordinator tests**

Run:

```powershell
.\gradlew.bat test --tests '*ClientEventHandlerTest' --tests '*BackupCoordinatorTest' --no-daemon
```

Expected: all timer, retry, single-flight, success-duration, and failure-duration tests pass.

- [ ] **Step 6: Commit the runtime change**

```powershell
git add src/main/java/com/rlcmj/worldbackup/runtime/ClientEventHandler.java src/test/java/com/rlcmj/worldbackup/runtime/ClientEventHandlerTest.java
git commit -m "feat: back up worlds after joining"
```

### Task 3: Document, verify, rebuild, and reinstall BETA3

**Files:**
- Modify: `README.md`
- Build: `build/libs/worldbackup-1.0.0.jar`
- Install: `E:/PCL/.minecraft/versions/RLCMJ V4.0 BETA3/mods/worldbackup-1.0.0.jar`

- [ ] **Step 1: Update user documentation**

Change “8 settings” to “9 settings”, add:

```markdown
| `worldLoadEnabled` | `true` | 进入单人世界 10 秒后备份一次；忙碌时稍后重试 |
```

Document `WORLD_LOAD` in the trigger descriptions and state that its successful and failed chat messages include elapsed seconds through the same coordinator path as every other trigger.

- [ ] **Step 2: Run a fresh Java 8 build**

```powershell
$env:JAVA_HOME = (Resolve-Path '.\.toolchains\jdk8\jdk8u504-b01').Path
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:http_proxy = 'http://127.0.0.1:9'
$env:https_proxy = 'http://127.0.0.1:9'
.\gradlew.bat '-Djava.net.preferIPv4Stack=true' '-Dhttps.protocols=TLSv1.2' '-Dnet.minecraftforge.gradle.test_certs=false' clean test build --offline --no-daemon --max-workers=1
```

Expected: `BUILD SUCCESSFUL`, every test has zero failures/errors, and `reobfJar` completes.

- [ ] **Step 3: Audit the release JAR**

Confirm it contains `WorldBackup.class`, both language files, `META-INF/worldbackup_at.cfg`, `ClientEventHandler$WorldLoadTimer.class`, and no test classes, credentials, or absolute workspace paths. Record size and SHA-256.

- [ ] **Step 4: Atomically replace only the BETA3 installation**

Verify there is exactly one active `worldbackup-*.jar`. Copy the freshly verified build to `worldbackup-1.0.0.jar.installing`, compare hashes, then move it over `mods/worldbackup-1.0.0.jar`. Confirm source and installed SHA-256 match. Do not modify BETA4 or BETA5.

- [ ] **Step 5: Commit documentation and verification evidence**

```powershell
git add README.md
git commit -m "docs: document world-load backups"
git status --short
```

Expected: a clean working tree on `feature/world-backup`.

Manual follow-up remains honest: because the legacy development client currently fails inside ForgeGradle before the main menu, do not claim the in-game toggle or chat message was visually verified unless a real BETA3 launch reaches the menu. Automated tests must still prove the ten-second timing, retry, trigger persistence, and elapsed-message path.
