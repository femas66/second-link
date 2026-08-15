# Local Guardian Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a local, event-driven Guardian that detects device incidents, performs bounded recovery, queues Telegram alerts while offline, and exposes one consistent state in Telegram and the Android dashboard.

**Architecture:** Add a pure Kotlin rules/reducer layer, persist tracked incident state and alert outbox in Room, and run one serialized coordinator inside the existing `BotService`. Reuse `AndroidDeviceHealthSource`, route text/menu/cron commands through one dispatcher with a Guardian safety gate, and project the same repository state into Telegram and Compose UI.

**Tech Stack:** Kotlin 2.2.10, Android SDK 26–37, LifecycleService/coroutines/StateFlow, Room 2.8.4 with KSP, Retrofit 3, Gson 2.14, Jetpack Compose Material 3, JUnit 4, AndroidX Room migration testing.

## Global Constraints

- Run all monitoring locally; do not add a server, heartbeat service, Accessibility Service, root behavior, device-owner behavior, or a second permanent Android service.
- Do not silently change network settings, permissions, or other Android settings.
- Never delete user files; cleanup is restricted to explicit files inside SecondLink cache and app-specific download directories.
- Keep Guardian thresholds fixed in version one.
- Send alerts on state transitions, not every sample.
- Retain at most 30 days and 500 incidents.
- Do not persist or send bot tokens, access codes, contacts, IP addresses, Wi-Fi names, download URLs, raw exceptions, or stack traces.
- Keep `/status` and alarm stop available during every safety restriction.
- Use monotonic elapsed time for in-session debounce/backoff and wall time only for display, retention, and local-day reminder limits.
- Preserve reminders, cron jobs, audit logs, credentials, and existing Room data during migration.
- Use the exact thresholds and recovery rules from `docs/superpowers/specs/2026-08-15-local-guardian-design.md`.

---

## File Map

**New production files**

- `app/src/main/java/id/my/femasaf/secondlink/GuardianModels.kt` — domain types, public summaries, transitions, and safety state.
- `app/src/main/java/id/my/femasaf/secondlink/GuardianRules.kt` — snapshot-to-observation threshold rules.
- `app/src/main/java/id/my/femasaf/secondlink/GuardianReducer.kt` — debounce, hysteresis, escalation, acknowledgement, reminders, recovery, and reboot interruption.
- `app/src/main/java/id/my/femasaf/secondlink/GuardianPersistence.kt` — Room entities, mappings, repository, retention, and outbox transaction boundary.
- `app/src/main/java/id/my/femasaf/secondlink/GuardianCoordinator.kt` — serialized sample processing and state flow.
- `app/src/main/java/id/my/femasaf/secondlink/GuardianAlerts.kt` — alert formatting, outbox compaction, and Telegram delivery adapter.
- `app/src/main/java/id/my/femasaf/secondlink/GuardianRecovery.kt` — polling backoff, safety policy, and app-owned cache cleanup.
- `app/src/main/java/id/my/femasaf/secondlink/SecondLinkCommandDispatcher.kt` — shared typed command operations for text, callbacks, and cron.
- `app/src/main/java/id/my/femasaf/secondlink/GuardianUi.kt` — Compose summary card and detail screen.

**Modified production files**

- `app/src/main/java/id/my/femasaf/secondlink/AppDatabase.kt` — Guardian tables, DAO methods, migrations 1→2 and 2→3, and version 3.
- `app/src/main/java/id/my/femasaf/secondlink/AndroidDeviceHealthSource.kt` — implement the Guardian sample source interface without duplicating health reads.
- `app/src/main/java/id/my/femasaf/secondlink/BotService.kt` — coordinator lifecycle, connectivity/battery signals, polling outcomes, outbox flushing, and shared dispatcher wiring.
- `app/src/main/java/id/my/femasaf/secondlink/BootReceiver.kt` — identify boot-origin startup.
- `app/src/main/java/id/my/femasaf/secondlink/BotManager.kt` — expose repository summaries without storing sensitive payloads.
- `app/src/main/java/id/my/femasaf/secondlink/BotViewModel.kt` — collect Guardian summary/history state.
- `app/src/main/java/id/my/femasaf/secondlink/MainActivity.kt` — dashboard navigation to Local Guardian.
- `app/src/main/java/id/my/femasaf/secondlink/TelegramMenuState.kt` — add the Guardian screen.
- `app/src/main/java/id/my/femasaf/secondlink/TelegramKeyboard.kt` — Guardian buttons and callback IDs.
- `app/src/main/java/id/my/femasaf/secondlink/TelegramMenuRouter.kt` — route Guardian actions through the shared dispatcher.
- `app/build.gradle.kts` — Room migration-test dependency.

**New tests**

- `app/src/test/java/id/my/femasaf/secondlink/GuardianRulesTest.kt`
- `app/src/test/java/id/my/femasaf/secondlink/GuardianReducerTest.kt`
- `app/src/test/java/id/my/femasaf/secondlink/GuardianCoordinatorTest.kt`
- `app/src/test/java/id/my/femasaf/secondlink/GuardianOutboxTest.kt`
- `app/src/test/java/id/my/femasaf/secondlink/GuardianRecoveryTest.kt`
- `app/src/test/java/id/my/femasaf/secondlink/GuardianServiceStateTest.kt`
- `app/src/test/java/id/my/femasaf/secondlink/SecondLinkCommandDispatcherTest.kt`
- `app/src/test/java/id/my/femasaf/secondlink/GuardianTelegramTest.kt`
- `app/src/test/java/id/my/femasaf/secondlink/GuardianUiStateTest.kt`
- `app/src/test/java/id/my/femasaf/secondlink/GuardianPrivacyTest.kt`
- `app/src/test/java/id/my/femasaf/secondlink/GuardianEndToEndTest.kt`
- `app/src/androidTest/java/id/my/femasaf/secondlink/AppDatabaseMigrationTest.kt`

---

### Task 1: Pure Guardian domain, thresholds, and transition reducer

**Files:**

- Create: `app/src/main/java/id/my/femasaf/secondlink/GuardianModels.kt`
- Create: `app/src/main/java/id/my/femasaf/secondlink/GuardianRules.kt`
- Create: `app/src/main/java/id/my/femasaf/secondlink/GuardianReducer.kt`
- Test: `app/src/test/java/id/my/femasaf/secondlink/GuardianRulesTest.kt`
- Test: `app/src/test/java/id/my/femasaf/secondlink/GuardianReducerTest.kt`

**Interfaces:**

- Consumes: existing `DeviceHealthInput` from `DeviceHealth.kt`.
- Produces: `GuardianSample`, `GuardianTrackedState`, `GuardianTransition`, `GuardianEvaluation`, `GuardianRules.observe(sample)`, and `GuardianReducer.reduce` for persistence and coordination tasks.

- [ ] **Step 1: Write threshold tests that fail because Guardian rules do not exist**

```kotlin
@Test fun batteryUses15Warning5CriticalAndChargingRecovery() {
    assertEquals(GuardianSeverity.WARNING, observe(battery = 14, charging = false).severity)
    assertEquals(GuardianSeverity.CRITICAL, observe(battery = 4, charging = false).severity)
    assertNull(observe(battery = 4, charging = true).severity)
    assertNull(observe(battery = 20, charging = false).severity)
}

@Test fun storageRequiresBothRecoveryMargins() {
    assertEquals(GuardianSeverity.CRITICAL, observe(free = GIB - 1, total = 20 * GIB).severity)
    assertEquals(GuardianSeverity.WARNING, observe(free = 9 * GIB, total = 100 * GIB).severity)
    assertNull(observe(free = 13 * GIB, total = 100 * GIB).severity)
}

@Test fun pollingEscalatesAfterThreeFailuresAndFifteenMinutes() {
    assertEquals(GuardianSeverity.WARNING, observePolling(3, 0L, 1_000L).severity)
    assertEquals(GuardianSeverity.CRITICAL, observePolling(3, 0L, 900_000L).severity)
}
```

Use a local `healthyInput()` fixture with all access flags true, validated internet, 80% battery, 30°C, and 20 GiB free of 100 GiB.

- [ ] **Step 2: Run the rule tests and verify the missing types fail compilation**

Run: `./gradlew testDebugUnitTest --tests 'id.my.femasaf.secondlink.GuardianRulesTest'`

Expected: FAIL with unresolved references to `GuardianSample`, `GuardianRules`, and `GuardianSeverity`.

- [ ] **Step 3: Add exact domain types**

```kotlin
enum class GuardianIncidentType {
    BATTERY, TEMPERATURE, STORAGE, INTERNET, TELEGRAM_POLLING,
    SERVICE, ACCESS_CAMERA, ACCESS_CONTACTS, ACCESS_NOTIFICATIONS, ACCESS_MEDIA
}

enum class GuardianSeverity { WARNING, CRITICAL }
enum class GuardianStateStatus { NORMAL, PENDING, ACTIVE, RECOVERED, INTERRUPTED }
enum class GuardianTransitionKind { STARTED, OPENED, ESCALATED, ACKNOWLEDGED, RECOVERED, INTERRUPTED, REMINDER }

data class GuardianSample(
    val health: DeviceHealthInput,
    val consecutivePollingFailures: Int,
    val pollingFailureStartedElapsedMs: Long?,
    val epochMs: Long,
    val elapsedMs: Long,
    val sessionId: String
)

data class GuardianObservation(
    val type: GuardianIncidentType,
    val severity: GuardianSeverity?,
    val displayValue: String,
    val recoveryEligible: Boolean = false,
    val debounceMs: Long = 0L,
    val recoveryDebounceMs: Long = 0L
)

data class GuardianTrackedState(
    val id: String,
    val type: GuardianIncidentType,
    val status: GuardianStateStatus,
    val severity: GuardianSeverity?,
    val seenHealthy: Boolean,
    val startedAtEpochMs: Long?,
    val updatedAtEpochMs: Long,
    val candidateSinceElapsedMs: Long?,
    val recoverySinceElapsedMs: Long?,
    val displayValue: String,
    val acknowledged: Boolean,
    val reminderDayKey: String?,
    val reminderCount: Int,
    val lastReminderAtEpochMs: Long?,
    val sessionId: String
)

data class GuardianTransition(
    val incidentId: String,
    val type: GuardianIncidentType,
    val kind: GuardianTransitionKind,
    val severity: GuardianSeverity?,
    val displayValue: String,
    val epochMs: Long
)

data class GuardianEvaluation(
    val states: List<GuardianTrackedState>,
    val transitions: List<GuardianTransition>
)
```

- [ ] **Step 4: Implement deterministic threshold observations**

Create `GuardianRules.observe(sample: GuardianSample): List<GuardianObservation>` with these exact results:

```kotlin
object GuardianRules {
    fun observe(sample: GuardianSample): List<GuardianObservation> = listOfNotNull(
        battery(sample.health),
        temperature(sample.health),
        storage(sample.health),
        internet(sample.health),
        polling(sample)
    ) + access(sample.health)
}

private fun battery(input: DeviceHealthInput): GuardianObservation? {
    val percent = input.batteryPercent ?: return null
    val severity = when {
        input.charging == true -> null
        percent < 5 -> GuardianSeverity.CRITICAL
        percent < 15 -> GuardianSeverity.WARNING
        percent >= 20 -> null
        else -> return null // 15–19% is the hysteresis band
    }
    return GuardianObservation(
        type = GuardianIncidentType.BATTERY,
        severity = severity,
        displayValue = "$percent%",
        recoveryEligible = input.charging == true || percent >= 20
    )
}

private fun temperature(input: DeviceHealthInput): GuardianObservation? {
    val value = input.temperatureC ?: return null
    val severity = when {
        value >= 45f -> GuardianSeverity.CRITICAL
        value >= 40f -> GuardianSeverity.WARNING
        value < 38f -> null
        else -> return null // 38–39.9°C retains the current state
    }
    return GuardianObservation(
        type = GuardianIncidentType.TEMPERATURE,
        severity = severity,
        displayValue = "%.1f°C".format(Locale.ROOT, value),
        recoveryEligible = value < 38f,
        debounceMs = 30_000L,
        recoveryDebounceMs = 5 * 60_000L
    )
}
```

Implement storage with warning `<10%`, critical `<5% || <1_073_741_824`, recovery eligibility only when `>=12% && >=1_610_612_736`, and omission inside the hysteresis band. Internet `false` is warning with a 120,000 ms debounce, `true` is recovery eligible, and `null` is omitted. Polling is warning at three failures, critical after 900,000 ms, recovery eligible only at zero failures, and omitted at one or two failures. Access `false` is warning, `true` is recovery eligible, and `null` is omitted. Omitted observations retain prior state and never produce recovery.

- [ ] **Step 5: Write reducer tests for debounce, hysteresis, escalation, acknowledgement, reminders, and interruption**

```kotlin
@Test fun temperatureOpensOnSecondSampleAfterDebounce() {
    val first = reduce(emptyList(), temperatureWarning(elapsed = 0L), epoch = 1_000L, elapsed = 0L)
    assertEquals(GuardianStateStatus.PENDING, first.states.single().status)
    val second = reduce(first.states, temperatureWarning(elapsed = 30_000L), epoch = 31_000L, elapsed = 30_000L)
    assertEquals(GuardianTransitionKind.OPENED, second.transitions.single().kind)
}

@Test fun criticalReminderStopsAfterAcknowledgement() {
    val active = criticalState(acknowledged = true, lastReminderAtEpochMs = 0L)
    val result = GuardianReducer.reduce(listOf(active), criticalObservation(), 21_600_001L, 21_600_001L, "2026-08-15", "session")
    assertTrue(result.transitions.none { it.kind == GuardianTransitionKind.REMINDER })
}

@Test fun rebootInterruptsOldSessionAndCreatesFreshEvaluation() {
    val result = GuardianReducer.reduce(listOf(activeState(sessionId = "old")), healthyObservations(), 2_000L, 50L, "2026-08-15", "new")
    assertTrue(result.transitions.any { it.kind == GuardianTransitionKind.INTERRUPTED })
}
```

- [ ] **Step 6: Run reducer tests and confirm failure before implementation**

Run: `./gradlew testDebugUnitTest --tests 'id.my.femasaf.secondlink.GuardianReducerTest'`

Expected: FAIL because `GuardianReducer` is unresolved.

- [ ] **Step 7: Implement the reducer as a pure state machine**

Expose this signature:

```kotlin
object GuardianReducer {
    fun reduce(
        previous: List<GuardianTrackedState>,
        observations: List<GuardianObservation>,
        epochMs: Long,
        elapsedMs: Long,
        localDayKey: String,
        sessionId: String
    ): GuardianEvaluation
}
```

Apply these exact rules: battery and storage open on their first unhealthy observation; temperature opens after its debounce and recovers below 38°C for five minutes; internet opens after two minutes; escalation emits once; only `recoveryEligible = true` may start or finish recovery; omitted observations retain the current state; first access sample establishes `seenHealthy` without opening if access is already absent; critical reminders require six hours since the last alert, stop after three per local day, and never run when acknowledged; a session mismatch emits `INTERRUPTED`, closes the old state, and evaluates the new observation as a fresh baseline.

- [ ] **Step 8: Run both domain suites**

Run: `./gradlew testDebugUnitTest --tests 'id.my.femasaf.secondlink.GuardianRulesTest' --tests 'id.my.femasaf.secondlink.GuardianReducerTest'`

Expected: PASS.

- [ ] **Step 9: Commit the domain layer**

```bash
git add app/src/main/java/id/my/femasaf/secondlink/GuardianModels.kt app/src/main/java/id/my/femasaf/secondlink/GuardianRules.kt app/src/main/java/id/my/femasaf/secondlink/GuardianReducer.kt app/src/test/java/id/my/femasaf/secondlink/GuardianRulesTest.kt app/src/test/java/id/my/femasaf/secondlink/GuardianReducerTest.kt
git commit -m "feat(guardian): add incident state machine"
```

---

### Task 2: Persist incidents and outbox without destructive migration

**Files:**

- Create: `app/src/main/java/id/my/femasaf/secondlink/GuardianPersistence.kt`
- Modify: `app/src/main/java/id/my/femasaf/secondlink/AppDatabase.kt`
- Modify: `app/build.gradle.kts`
- Test: `app/src/androidTest/java/id/my/femasaf/secondlink/AppDatabaseMigrationTest.kt`
- Generated: `app/schemas/id.my.femasaf.secondlink.AppDatabase/3.json`

**Interfaces:**

- Consumes: `GuardianTrackedState` and `GuardianTransition` from Task 1.
- Produces: `GuardianRepository.loadTrackedStates()`, `applyEvaluation()`, `acknowledge()`, active/history flows, `pendingOutbox()`, `markOutboxSent()`, and `prune()`.

- [ ] **Step 1: Add migration-test support**

```kotlin
androidTestImplementation("androidx.room:room-testing:2.8.4")
```

- [ ] **Step 2: Write migration tests before changing the database**

```kotlin
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test fun migrate2To3PreservesExistingRowsAndCreatesGuardianTables() {
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL("INSERT INTO reminders VALUES ('r1','wake',1000,0)")
            execSQL("INSERT INTO cronjobs VALUES ('c1','/status',7,30,1)")
            execSQL("INSERT INTO audit_logs VALUES (1,'actor','/status',1000,'SUCCESS',NULL)")
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB, 3, true, AppDatabase.MIGRATION_2_3).use { db ->
            assertEquals(1, db.query("SELECT COUNT(*) FROM reminders").singleInt())
            assertEquals(0, db.query("SELECT COUNT(*) FROM guardian_states").singleInt())
            assertEquals(0, db.query("SELECT COUNT(*) FROM guardian_outbox").singleInt())
        }
    }

    private fun Cursor.singleInt(): Int = use {
        check(it.moveToFirst())
        it.getInt(0)
    }
}
```

Also add a 1→3 test that passes both `MIGRATION_1_2` and `MIGRATION_2_3` and verifies reminder/cron rows survive.

- [ ] **Step 3: Run the migration test and verify version 3 is unavailable**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=id.my.femasaf.secondlink.AppDatabaseMigrationTest`

Expected: FAIL because the migrations and schema version 3 do not exist.

- [ ] **Step 4: Add the Guardian entities and DAO contract**

```kotlin
@Entity(tableName = "guardian_states")
data class GuardianStateEntity(
    @PrimaryKey val type: String,
    val incidentId: String,
    val status: String,
    val severity: String?,
    val seenHealthy: Boolean,
    val startedAtEpochMs: Long?,
    val updatedAtEpochMs: Long,
    val candidateSinceElapsedMs: Long?,
    val recoverySinceElapsedMs: Long?,
    val displayValue: String,
    val acknowledged: Boolean,
    val reminderDayKey: String?,
    val reminderCount: Int,
    val lastReminderAtEpochMs: Long?,
    val sessionId: String
)

@Entity(tableName = "guardian_incidents", indices = [Index("type"), Index("updatedAtEpochMs")])
data class GuardianIncidentEntity(
    @PrimaryKey val id: String,
    val type: String,
    val severity: String?,
    val status: String,
    val startedAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val resolvedAtEpochMs: Long?,
    val displayValue: String,
    val acknowledged: Boolean,
    val recoveryAttempts: Int,
    val recoveryStatus: String?
)

@Entity(tableName = "guardian_outbox", indices = [Index("incidentId"), Index("createdAtEpochMs")])
data class GuardianOutboxEntity(
    @PrimaryKey val id: String,
    val incidentId: String,
    val type: String,
    val transitionKind: String,
    val severity: String?,
    val displayValue: String,
    val createdAtEpochMs: Long,
    val attemptCount: Int,
    val status: String
)

data class GuardianIncidentSummary(
    val id: String,
    val type: GuardianIncidentType,
    val severity: GuardianSeverity?,
    val status: GuardianStateStatus,
    val startedAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val resolvedAtEpochMs: Long?,
    val displayValue: String,
    val acknowledged: Boolean
)

interface GuardianRepository {
    val activeIncidents: Flow<List<GuardianIncidentSummary>>
    val incidentHistory: Flow<List<GuardianIncidentSummary>>
    suspend fun loadTrackedStates(): List<GuardianTrackedState>
    suspend fun applyEvaluation(evaluation: GuardianEvaluation)
    suspend fun recordServiceStart(transition: GuardianTransition)
    suspend fun acknowledge(incidentId: String, epochMs: Long): Boolean
    suspend fun pendingOutbox(): List<GuardianOutboxEntity>
    suspend fun markOutboxSent(ids: List<String>)
    suspend fun incrementOutboxAttempt(id: String)
    suspend fun pendingOutboxCount(): Int
    suspend fun recordRecovery(incidentId: String, attempts: Int, status: GuardianRecoveryStatus)
    suspend fun prune(nowEpochMs: Long)
}

```

Implement `class RoomGuardianRepository(private val db: AppDatabase, private val dao: BotDao) : GuardianRepository` in `GuardianPersistence.kt`; every interface method above must map enum names with `enumValueOf`, use `db.withTransaction` for evaluation/start transitions, and expose DAO `Flow` queries through entity-to-summary mappings.

Add DAO queries for all states, active/recent incidents as `Flow`, pending outbox ordered oldest-first, transactional evaluation application, acknowledgement, sent deletion, 30-day deletion, and trimming incidents beyond the newest 500.

- [ ] **Step 5: Add explicit migrations and remove destructive fallback**

Set `@Database(entities = [ReminderEntity::class, CronJobEntity::class, AuditLogEntity::class, GuardianStateEntity::class, GuardianIncidentEntity::class, GuardianOutboxEntity::class], version = 3)` and add:

```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS audit_logs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, chatId TEXT NOT NULL, command TEXT NOT NULL, timestamp INTEGER NOT NULL, status TEXT NOT NULL, response TEXT)")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS guardian_states (type TEXT NOT NULL PRIMARY KEY, incidentId TEXT NOT NULL, status TEXT NOT NULL, severity TEXT, seenHealthy INTEGER NOT NULL, startedAtEpochMs INTEGER, updatedAtEpochMs INTEGER NOT NULL, candidateSinceElapsedMs INTEGER, recoverySinceElapsedMs INTEGER, displayValue TEXT NOT NULL, acknowledged INTEGER NOT NULL, reminderDayKey TEXT, reminderCount INTEGER NOT NULL, lastReminderAtEpochMs INTEGER, sessionId TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS guardian_incidents (id TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, severity TEXT, status TEXT NOT NULL, startedAtEpochMs INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL, resolvedAtEpochMs INTEGER, displayValue TEXT NOT NULL, acknowledged INTEGER NOT NULL, recoveryAttempts INTEGER NOT NULL, recoveryStatus TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS guardian_outbox (id TEXT NOT NULL PRIMARY KEY, incidentId TEXT NOT NULL, type TEXT NOT NULL, transitionKind TEXT NOT NULL, severity TEXT, displayValue TEXT NOT NULL, createdAtEpochMs INTEGER NOT NULL, attemptCount INTEGER NOT NULL, status TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_guardian_incidents_type ON guardian_incidents(type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_guardian_incidents_updatedAtEpochMs ON guardian_incidents(updatedAtEpochMs)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_guardian_outbox_incidentId ON guardian_outbox(incidentId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_guardian_outbox_createdAtEpochMs ON guardian_outbox(createdAtEpochMs)")
    }
}
```

Register both migrations with `.addMigrations(MIGRATION_1_2, MIGRATION_2_3)` and delete `.fallbackToDestructiveMigration()`.

- [ ] **Step 6: Implement repository mappings and retention**

`RoomGuardianRepository.applyEvaluation(evaluation)` must upsert every tracked state and append incident/outbox rows for transitions inside one `db.withTransaction`. `prune(nowEpochMs)` must delete incidents older than `now - 30L * 24 * 60 * 60 * 1000` and then keep the newest 500; outbox rows still pending must not be deleted by incident pruning.

- [ ] **Step 7: Run migration and schema verification**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=id.my.femasaf.secondlink.AppDatabaseMigrationTest`

Expected: PASS and `app/schemas/id.my.femasaf.secondlink.AppDatabase/3.json` is generated.

- [ ] **Step 8: Commit persistence**

```bash
git add app/build.gradle.kts app/src/main/java/id/my/femasaf/secondlink/AppDatabase.kt app/src/main/java/id/my/femasaf/secondlink/GuardianPersistence.kt app/src/androidTest/java/id/my/femasaf/secondlink/AppDatabaseMigrationTest.kt app/schemas/id.my.femasaf.secondlink.AppDatabase/3.json
git commit -m "feat(guardian): persist incidents and outbox"
```

---

### Task 3: Coordinate samples and expose one Guardian state

**Files:**

- Create: `app/src/main/java/id/my/femasaf/secondlink/GuardianCoordinator.kt`
- Modify: `app/src/main/java/id/my/femasaf/secondlink/AndroidDeviceHealthSource.kt`
- Test: `app/src/test/java/id/my/femasaf/secondlink/GuardianCoordinatorTest.kt`

**Interfaces:**

- Consumes: `GuardianRules`, `GuardianReducer`, and `GuardianRepository` from Tasks 1–2.
- Produces: `GuardianSampleSource.read`, `GuardianCoordinator.checkNow`, `acknowledge`, `recordServiceStart`, and `summary: StateFlow<GuardianSummary>`.

- [ ] **Step 1: Write coordinator tests using handwritten fakes**

```kotlin
@Test fun simultaneousSignalsAreSerializedAndCreateOneIncident() = runTest {
    val repository = FakeGuardianRepository()
    val coordinator = GuardianCoordinator(repository, FakeSampleSource(lowBatteryInput()), fixedClock)
    coroutineScope {
        launch { coordinator.checkNow(GuardianTrigger.BATTERY) }
        launch { coordinator.checkNow(GuardianTrigger.PERIODIC) }
    }
    assertEquals(1, repository.activeIncidents().count { it.type == GuardianIncidentType.BATTERY })
}

@Test fun unknownSensorDoesNotRecoverAnActiveIncident() = runTest {
    val repository = FakeGuardianRepository(activeTemperatureIncident())
    val coordinator = GuardianCoordinator(repository, FakeSampleSource(healthWithTemperature(null)), fixedClock)
    coordinator.checkNow(GuardianTrigger.PERIODIC)
    assertEquals(GuardianStateStatus.ACTIVE, repository.state(GuardianIncidentType.TEMPERATURE).status)
}
```

- [ ] **Step 2: Run the coordinator test and confirm it fails**

Run: `./gradlew testDebugUnitTest --tests 'id.my.femasaf.secondlink.GuardianCoordinatorTest'`

Expected: FAIL because coordinator interfaces are unresolved.

- [ ] **Step 3: Add coordinator interfaces and summaries**

```kotlin
enum class GuardianTrigger { STARTUP, BOOT, BATTERY, CONNECTIVITY, PERIODIC, POLLING, MANUAL }

interface GuardianClock {
    fun epochMs(): Long
    fun elapsedMs(): Long
    fun localDayKey(): String
    fun sessionId(): String
}

interface GuardianSampleSource {
    suspend fun read(
        botStatus: String?,
        pollingFailures: Int,
        pollingFailureStartedElapsedMs: Long?,
        epochMs: Long,
        elapsedMs: Long,
        sessionId: String
    ): GuardianSample
}

data class GuardianSummary(
    val status: String = "Aktif",
    val activeCount: Int = 0,
    val primaryIncident: GuardianIncidentSummary? = null,
    val lastCheckedAtEpochMs: Long? = null,
    val pendingAlerts: Int = 0
)

class GuardianCoordinator(
    private val repository: GuardianRepository,
    private val source: GuardianSampleSource,
    private val clock: GuardianClock
) {
    suspend fun checkNow(trigger: GuardianTrigger): GuardianEvaluation
    suspend fun recordServiceStart(trigger: GuardianTrigger): GuardianTransition
    suspend fun acknowledge(incidentId: String): Boolean
    val summary: StateFlow<GuardianSummary>
}
```

`GuardianClock` provides `epochMs()`, `elapsedMs()`, `localDayKey()`, and `sessionId()` so tests never depend on real time.

- [ ] **Step 4: Adapt the existing health source**

Make `AndroidDeviceHealthSource` implement `GuardianSampleSource`. Its implementation must call the existing `read(botStatus)` exactly once and wrap the result in `GuardianSample`; do not duplicate battery, storage, permission, or network reads.

- [ ] **Step 5: Implement serialized evaluation**

Use a `Mutex` around `loadTrackedStates → source.read → GuardianRules.observe → GuardianReducer.reduce → repository.applyEvaluation`. `recordServiceStart` creates a `STARTED` transition for `SERVICE`, persists it as an already resolved history event, and queues one startup message; it never creates an active safety incident. Refresh `summary` after each transaction. Cancellation must be rethrown; other read failures update summary status to `Terbatas` while preserving the last successful state.

- [ ] **Step 6: Run coordinator and existing Device Health tests**

Run: `./gradlew testDebugUnitTest --tests 'id.my.femasaf.secondlink.GuardianCoordinatorTest' --tests 'id.my.femasaf.secondlink.DeviceHealth*Test'`

Expected: PASS.

- [ ] **Step 7: Commit coordination**

```bash
git add app/src/main/java/id/my/femasaf/secondlink/GuardianCoordinator.kt app/src/main/java/id/my/femasaf/secondlink/AndroidDeviceHealthSource.kt app/src/test/java/id/my/femasaf/secondlink/GuardianCoordinatorTest.kt
git commit -m "feat(guardian): coordinate device signals"
```

---

### Task 4: Alert formatting, offline compaction, and bounded recovery

**Files:**

- Create: `app/src/main/java/id/my/femasaf/secondlink/GuardianAlerts.kt`
- Create: `app/src/main/java/id/my/femasaf/secondlink/GuardianRecovery.kt`
- Test: `app/src/test/java/id/my/femasaf/secondlink/GuardianOutboxTest.kt`
- Test: `app/src/test/java/id/my/femasaf/secondlink/GuardianRecoveryTest.kt`

**Interfaces:**

- Consumes: transitions/outbox repository from Tasks 1–2.
- Produces: `GuardianAlertFormatter`, `GuardianAlertDispatcher.flush()`, `PollingBackoff`, `GuardianSafetyPolicy`, and `AppOwnedCacheCleaner`.

- [ ] **Step 1: Write outbox compaction and redaction tests**

```kotlin
@Test fun offlineOpenEscalateRecoverBecomesOneResolvedSummary() = runTest {
    repository.enqueue(openedBattery())
    repository.enqueue(escalatedBattery())
    repository.enqueue(recoveredBattery())
    val compacted = GuardianOutboxCompactor.compact(repository.pendingOutbox())
    assertEquals(1, compacted.size)
    assertTrue(compacted.single().body.contains("pulih"))
    assertFalse(compacted.single().body.contains("token"))
}

@Test fun successfulFlushMarksOnlyDeliveredRows() = runTest {
    val sender = SequenceSender(true, false)
    dispatcher.flush(sender)
    assertEquals(listOf("first"), repository.sentIds)
}
```

- [ ] **Step 2: Write recovery policy tests**

```kotlin
@Test fun pollingBackoffUsesBoundedSequence() {
    val backoff = PollingBackoff()
    assertEquals(listOf(5_000L, 15_000L, 30_000L, 60_000L, 300_000L, 300_000L),
        List(6) { backoff.nextDelayMs() })
    backoff.reset()
    assertEquals(5_000L, backoff.nextDelayMs())
}

@Test fun safetyPolicyAlwaysAllowsStatusAndAlarmStop() {
    val state = GuardianSafetyState(temperatureCritical = true, batteryCritical = true, storageCritical = true)
    assertTrue(GuardianSafetyPolicy.decision(CommandKind.STATUS, state).allowed)
    assertTrue(GuardianSafetyPolicy.decision(CommandKind.ALARM_STOP, state).allowed)
    assertFalse(GuardianSafetyPolicy.decision(CommandKind.CAMERA, state).allowed)
    assertFalse(GuardianSafetyPolicy.decision(CommandKind.DOWNLOAD, state).allowed)
}

@Test fun recoveryStopsActiveRetriesAndRequiresUserAction() = runTest {
    val operation = AlwaysFailRecoveryOperation()
    val controller = GuardianRecoveryController(operation)
    (1..4).forEach { attempt ->
        assertEquals(GuardianRecoveryStatus.FAILED, controller.run(GuardianRecoveryAction.RECONNECT_POLLING, attempt).status)
    }
    assertEquals(GuardianRecoveryStatus.USER_ACTION_REQUIRED, controller.run(GuardianRecoveryAction.RECONNECT_POLLING, 5).status)
    assertEquals(4, operation.calls)
}
```

- [ ] **Step 3: Run both suites and confirm missing implementations**

Run: `./gradlew testDebugUnitTest --tests 'id.my.femasaf.secondlink.GuardianOutboxTest' --tests 'id.my.femasaf.secondlink.GuardianRecoveryTest'`

Expected: FAIL with unresolved alert/recovery types.

- [ ] **Step 4: Implement safe alert formatting and compaction**

`GuardianAlertFormatter.format(alert)` must use a fixed title per incident type, the persisted `displayValue`, duration, and a fixed recovery message. It must not accept arbitrary exception text. `GuardianOutboxCompactor.compact(rows)` groups by incident ID; if the last transition is recovered, output one recovered summary; otherwise output only the latest severity plus original start time.

Expose delivery through:

```kotlin
enum class GuardianRecoveryNote { POLLING_RECONNECTED, INTERNET_RESTORED, ACTIVITY_REDUCED, USER_ACTION_REQUIRED }
enum class GuardianRecoveryAction { RECONNECT_POLLING, REDUCE_ACTIVITY, CLEAN_APP_CACHE }
enum class GuardianRecoveryStatus { NOT_NEEDED, SUCCEEDED, FAILED, USER_ACTION_REQUIRED }

data class GuardianRecoveryResult(
    val action: GuardianRecoveryAction,
    val attempt: Int,
    val status: GuardianRecoveryStatus
)

fun interface GuardianRecoveryOperation {
    suspend fun execute(action: GuardianRecoveryAction): Boolean
}

class GuardianRecoveryController(
    private val operation: GuardianRecoveryOperation,
    private val maxActiveAttempts: Int = 4
) {
    suspend fun run(action: GuardianRecoveryAction, attempt: Int): GuardianRecoveryResult {
        if (attempt > maxActiveAttempts) {
            return GuardianRecoveryResult(action, attempt, GuardianRecoveryStatus.USER_ACTION_REQUIRED)
        }
        val status = if (operation.execute(action)) GuardianRecoveryStatus.SUCCEEDED else GuardianRecoveryStatus.FAILED
        return GuardianRecoveryResult(action, attempt, status)
    }
}

data class CompactedGuardianAlert(
    val sourceIds: List<String>,
    val incidentId: String,
    val type: GuardianIncidentType,
    val transitionKind: GuardianTransitionKind,
    val severity: GuardianSeverity?,
    val displayValue: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long?,
    val recoveryNote: GuardianRecoveryNote?
)

object GuardianOutboxCompactor {
    fun compact(rows: List<GuardianOutboxEntity>): List<CompactedGuardianAlert>
}

class GuardianAlertFormatter {
    fun format(alert: CompactedGuardianAlert): String
}

fun interface GuardianMessageSender {
    suspend fun send(text: String, replyMarkup: TelegramReplyMarkup?): Boolean
}

class GuardianAlertDispatcher(
    private val repository: GuardianRepository,
    private val formatter: GuardianAlertFormatter
) {
    suspend fun flush(sender: GuardianMessageSender): Int
}
```

Stop flushing on the first failed send to preserve ordering.

- [ ] **Step 5: Implement bounded recovery helpers**

```kotlin
class PollingBackoff {
    private val delays = longArrayOf(5_000L, 15_000L, 30_000L, 60_000L, 300_000L)
    private var index = 0
    fun nextDelayMs(): Long = delays[index.coerceAtMost(delays.lastIndex)].also {
        if (index < delays.lastIndex) index++
    }
    fun reset() { index = 0 }
}

enum class CommandKind {
    STATUS, ALARM_STOP, CAMERA, DOWNLOAD, NETWORK_SCAN, CRON_HEAVY,
    CONTACTS, MEDIA, ALARM_START, SCHEDULE, ECHO, GUARDIAN_READ
}

data class SafetyDecision(val allowed: Boolean, val reason: String? = null)
data class GuardianSafetyState(
    val temperatureCritical: Boolean,
    val batteryCritical: Boolean,
    val storageCritical: Boolean
)

object GuardianSafetyPolicy {
    fun decision(kind: CommandKind, state: GuardianSafetyState): SafetyDecision = when {
        kind == CommandKind.STATUS || kind == CommandKind.ALARM_STOP || kind == CommandKind.GUARDIAN_READ ->
            SafetyDecision(true)
        state.storageCritical && kind == CommandKind.DOWNLOAD ->
            SafetyDecision(false, "Penyimpanan kritis. Unduhan ditolak sementara.")
        (state.temperatureCritical || state.batteryCritical) &&
            kind in setOf(CommandKind.CAMERA, CommandKind.DOWNLOAD, CommandKind.NETWORK_SCAN, CommandKind.CRON_HEAVY) ->
            SafetyDecision(false, "Kondisi perangkat kritis. Pekerjaan berat ditolak sementara.")
        else -> SafetyDecision(true)
    }
}

object AppOwnedCacheCleaner {
    internal fun isContained(root: File, candidate: File): Boolean {
        val rootPath = root.canonicalFile.path + File.separator
        return candidate.canonicalFile.path.startsWith(rootPath)
    }

    fun clean(context: Context): Int {
        val cache = context.cacheDir
        val downloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val candidates = buildList {
            cache.listFiles()?.filterTo(this) { it.isFile && it.name.startsWith("temp_photo_") }
            downloads?.listFiles()?.filterTo(this) { it.isFile && it.name.endsWith(".part") }
        }
        return candidates.count { file ->
            val root = if (file.name.startsWith("temp_photo_")) cache else downloads
            root != null && isContained(root, file) && file.delete()
        }
    }
}
```

Map critical temperature to block CAMERA, DOWNLOAD, NETWORK_SCAN, and CRON_HEAVY; critical battery to block the same nonessential heavy work; critical storage to block DOWNLOAD. `AppOwnedCacheCleaner.clean(context)` may delete only files under `cacheDir` whose names start with `temp_photo_` and `.part` files under `getExternalFilesDir(DIRECTORY_DOWNLOADS)` after canonical-path containment checks.

Map polling failures to `RECONNECT_POLLING`, critical temperature/battery to `REDUCE_ACTIVITY`, and critical storage to one `CLEAN_APP_CACHE` attempt per incident. Persist every result through `recordRecovery`. After four active reconnect attempts, mark `USER_ACTION_REQUIRED` and stop the fast retry sequence; the ordinary five-minute health probe may still detect recovery without creating a tight recovery loop. Permission incidents become `USER_ACTION_REQUIRED` immediately and never call an operation.

- [ ] **Step 6: Run the alert and recovery suites**

Run: `./gradlew testDebugUnitTest --tests 'id.my.femasaf.secondlink.GuardianOutboxTest' --tests 'id.my.femasaf.secondlink.GuardianRecoveryTest'`

Expected: PASS.

- [ ] **Step 7: Commit alerts and recovery**

```bash
git add app/src/main/java/id/my/femasaf/secondlink/GuardianAlerts.kt app/src/main/java/id/my/femasaf/secondlink/GuardianRecovery.kt app/src/test/java/id/my/femasaf/secondlink/GuardianOutboxTest.kt app/src/test/java/id/my/femasaf/secondlink/GuardianRecoveryTest.kt
git commit -m "feat(guardian): queue alerts and bound recovery"
```

---

### Task 5: Integrate Guardian with the service lifecycle and Android signals

**Files:**

- Modify: `app/src/main/java/id/my/femasaf/secondlink/BotService.kt`
- Modify: `app/src/main/java/id/my/femasaf/secondlink/BootReceiver.kt`
- Modify: `app/src/main/java/id/my/femasaf/secondlink/BotManager.kt`
- Modify: `app/src/main/java/id/my/femasaf/secondlink/GuardianRecovery.kt`
- Test: `app/src/test/java/id/my/femasaf/secondlink/GuardianServiceStateTest.kt`

**Interfaces:**

- Consumes: coordinator, dispatcher, backoff, and outbox sender from Tasks 3–4.
- Produces: service-origin triggers, polling success/failure signals, 30-second/5-minute adaptive checks, and repository state available to UI.

- [ ] **Step 1: Extract and test service state decisions before editing `BotService`**

```kotlin
@Test fun criticalBatteryUsesFiveMinuteChecksAndNormalUsesThirtySeconds() {
    assertEquals(300_000L, GuardianServicePolicy.periodicIntervalMs(batteryCritical = true, temperatureCritical = false))
    assertEquals(30_000L, GuardianServicePolicy.periodicIntervalMs(batteryCritical = false, temperatureCritical = false))
}

@Test fun startupActionMapsBootAndStickyRestart() {
    assertEquals(GuardianTrigger.BOOT, GuardianServicePolicy.startTrigger(BotService.ACTION_BOOT_START, BotService.ACTION_BOOT_START))
    assertEquals(GuardianTrigger.STARTUP, GuardianServicePolicy.startTrigger(null, BotService.ACTION_BOOT_START))
}
```

- [ ] **Step 2: Run the policy test and verify failure**

Run: `./gradlew testDebugUnitTest --tests 'id.my.femasaf.secondlink.GuardianServiceStateTest'`

Expected: FAIL because `GuardianServicePolicy` and `ACTION_BOOT_START` do not exist.

- [ ] **Step 3: Implement the service decision policy**

Add to `GuardianRecovery.kt`:

```kotlin
object GuardianServicePolicy {
    fun periodicIntervalMs(batteryCritical: Boolean, temperatureCritical: Boolean): Long =
        if (batteryCritical || temperatureCritical) 300_000L else 30_000L

    fun startTrigger(action: String?, bootAction: String): GuardianTrigger =
        if (action == bootAction) GuardianTrigger.BOOT else GuardianTrigger.STARTUP
}
```

- [ ] **Step 4: Replace legacy one-shot battery alert flags**

Delete `batteryAlert20Sent`, `batteryAlert10Sent`, `batteryAlert5Sent`, `isOverheatAlertSent`, and direct Telegram alerts from `handleBatteryChanged`. Keep charging diagnostics only if they are represented as a Guardian observation; otherwise remove the legacy direct alerts so two engines cannot notify for the same event. The receiver must now call `guardianCoordinator.checkNow(GuardianTrigger.BATTERY)`.

- [ ] **Step 5: Wire startup, periodic, connectivity, and shutdown**

In `onCreate`, initialize repository/coordinator/dispatcher after `BotManager.init`, register a `ConnectivityManager.NetworkCallback`, and start one periodic coroutine. Use 30 seconds normally and five minutes when critical battery or temperature requires reduced activity. In `onDestroy`, cancel the periodic job, unregister network callback and battery receiver, cancel polling, and close no global singleton resources.

Add `ACTION_BOOT_START = "id.my.femasaf.secondlink.action.BOOT_START"`. `BootReceiver` must start the foreground service with that action. Keep `START_STICKY`. After initialization, call `recordServiceStart(BOOT)` for that action and `recordServiceStart(STARTUP)` for a normal or sticky start. Deduplicate repeated start calls inside the same `sessionId`.

- [ ] **Step 6: Replace fixed polling retry with tracked outcomes and backoff**

On each successful `getUpdates`, reset `PollingBackoff`, set consecutive failures to zero, check Guardian with `POLLING`, and flush the outbox after the response is processed. On non-timeout failure, increment failures, record the first failure elapsed time, check Guardian, then delay `backoff.nextDelayMs()`. Socket timeout remains a healthy long-poll outcome and must not increment failures.

- [ ] **Step 7: Run service policy and existing service-adjacent tests**

Run: `./gradlew testDebugUnitTest --tests 'id.my.femasaf.secondlink.GuardianServiceStateTest' --tests 'id.my.femasaf.secondlink.TelegramMenu*Test' --tests 'id.my.femasaf.secondlink.SecurityPolicyTest'`

Expected: PASS.

- [ ] **Step 8: Commit service integration**

```bash
git add app/src/main/java/id/my/femasaf/secondlink/BotService.kt app/src/main/java/id/my/femasaf/secondlink/BootReceiver.kt app/src/main/java/id/my/femasaf/secondlink/BotManager.kt app/src/main/java/id/my/femasaf/secondlink/GuardianRecovery.kt app/src/test/java/id/my/femasaf/secondlink/GuardianServiceStateTest.kt
git commit -m "feat(guardian): monitor service events"
```

---

### Task 6: Unify text, callback, and cron execution behind a safety gate

**Files:**

- Create: `app/src/main/java/id/my/femasaf/secondlink/SecondLinkCommandDispatcher.kt`
- Modify: `app/src/main/java/id/my/femasaf/secondlink/BotService.kt`
- Modify: `app/src/main/java/id/my/femasaf/secondlink/TelegramMenuRouter.kt`
- Test: `app/src/test/java/id/my/femasaf/secondlink/SecondLinkCommandDispatcherTest.kt`

**Interfaces:**

- Consumes: `CommandDispatcher`, `GuardianSafetyPolicy`, and existing bounded handlers.
- Produces: one `SecondLinkCommandDispatcher.dispatch(CommandRequest)` path for text, callbacks, and cron.

- [ ] **Step 1: Write parity and safety tests first**

```kotlin
@Test fun textCallbackAndCronResolveStatusToSameOperation() = runTest {
    val operations = FakeSecondLinkOperations()
    val dispatcher = dispatcher(operations, safeState())
    dispatcher.dispatch(CommandRequest("/status", CommandSource.TEXT, "chat", 1L))
    dispatcher.dispatch(CommandRequest("/status", CommandSource.CALLBACK, "chat", 1L))
    dispatcher.dispatch(CommandRequest("/status", CommandSource.CRON, "chat", 0L))
    assertEquals(listOf(CommandKind.STATUS, CommandKind.STATUS, CommandKind.STATUS), operations.calls)
}

@Test fun criticalTemperatureSkipsCronCameraWithoutLateQueue() = runTest {
    val result = hotDispatcher.dispatch(CommandRequest("/camera", CommandSource.CRON, "chat", 0L))
    assertEquals(CommandExecutionStatus.SKIPPED_SAFETY, result.status)
    assertTrue(fakeOperations.calls.isEmpty())
}
```

- [ ] **Step 2: Run the dispatcher test and confirm missing types**

Run: `./gradlew testDebugUnitTest --tests 'id.my.femasaf.secondlink.SecondLinkCommandDispatcherTest'`

Expected: FAIL because the shared dispatcher is unresolved.

- [ ] **Step 3: Add typed command request and operation boundary**

```kotlin
enum class CommandSource { TEXT, CALLBACK, CRON, INTERNAL }
enum class CommandExecutionStatus { SUCCESS, FAILED, UNKNOWN, SKIPPED_SAFETY }

data class CommandRequest(
    val text: String,
    val source: CommandSource,
    val chatId: String,
    val userId: Long
)

data class CommandExecution(
    val status: CommandExecutionStatus,
    val message: String? = null,
    val replyMarkup: TelegramReplyMarkup? = null
)

interface SecondLinkOperations {
    suspend fun execute(kind: CommandKind, argument: String?, request: CommandRequest): CommandExecution
}

class SecondLinkCommandDispatcher(
    private val operations: SecondLinkOperations,
    private val safetyState: () -> GuardianSafetyState,
    private val audit: (CommandRequest, CommandExecutionStatus) -> Unit
) {
    suspend fun dispatch(request: CommandRequest): CommandExecution
}
```

Create one parser that maps all existing commands plus Guardian read commands to `CommandKind` and bounded arguments. It must preserve current limits: 4,096-character inbound text, 500-character echo/cron payload, 25 MB download, 20 cron jobs, and 30-day reminder delay.

- [ ] **Step 4: Implement safety and audit in one dispatcher**

`SecondLinkCommandDispatcher.dispatch(request)` parses once, obtains `GuardianSafetyState`, applies `GuardianSafetyPolicy`, calls `SecondLinkOperations`, then writes a redacted audit status. Cron rejections use `SKIPPED_SAFETY`; interactive rejections return a clear Indonesian reason and are not queued.

- [ ] **Step 5: Replace the three existing dispatch branches**

Make direct text handling call the shared dispatcher after its existing authentication, rate limit, code verification, and secret-message deletion. Make `dispatchMenuCommand` a thin adapter to the same dispatcher. Make `executeInternalCommand` build `CommandSource.CRON`. Remove duplicate command `when` blocks only after parity tests cover `/status`, media, camera, contacts, scan, download, echo, cron add/list/stop, alarm start/stop, reminder, and Guardian read actions.

- [ ] **Step 6: Run dispatcher, router, and guided-flow tests**

Run: `./gradlew testDebugUnitTest --tests 'id.my.femasaf.secondlink.SecondLinkCommandDispatcherTest' --tests 'id.my.femasaf.secondlink.TelegramMenuRouterTest' --tests 'id.my.femasaf.secondlink.GuidedInputFlowTest'`

Expected: PASS.

- [ ] **Step 7: Commit command unification**

```bash
git add app/src/main/java/id/my/femasaf/secondlink/SecondLinkCommandDispatcher.kt app/src/main/java/id/my/femasaf/secondlink/BotService.kt app/src/main/java/id/my/femasaf/secondlink/TelegramMenuRouter.kt app/src/test/java/id/my/femasaf/secondlink/SecondLinkCommandDispatcherTest.kt
git commit -m "refactor(commands): unify execution paths"
```

---

### Task 7: Add Guardian Telegram menu and incident actions

**Files:**

- Modify: `app/src/main/java/id/my/femasaf/secondlink/TelegramMenuState.kt`
- Modify: `app/src/main/java/id/my/femasaf/secondlink/TelegramKeyboard.kt`
- Modify: `app/src/main/java/id/my/femasaf/secondlink/TelegramMenuRouter.kt`
- Modify: `app/src/main/java/id/my/femasaf/secondlink/BotService.kt`
- Test: `app/src/test/java/id/my/femasaf/secondlink/GuardianTelegramTest.kt`
- Test: `app/src/test/java/id/my/femasaf/secondlink/TelegramKeyboardTest.kt`
- Test: `app/src/test/java/id/my/femasaf/secondlink/TelegramMenuRouterTest.kt`

**Interfaces:**

- Consumes: Guardian repository summaries and shared command dispatcher.
- Produces: `MenuScreen.GUARDIAN`, fixed callbacks, formatted Guardian menu, check/test/ack actions.

- [ ] **Step 1: Write keyboard and routing tests**

```kotlin
@Test fun mainMenuContainsGuardianAndCallbacksStayWithinTelegramLimit() {
    val markup = TelegramKeyboard.render(MenuScreen.MAIN, MenuRenderState())
    val guardian = markup.inlineKeyboard.flatten().single { it.text == "Guardian" }
    assertTrue(guardian.callbackData.toByteArray().size <= 64)
}

@Test fun acknowledgeUsesOpaqueIncidentSlotNotDatabaseId() = runTest {
    val result = router.handle(callback("v1:guardian:ack_1:nonce"))
    assertTrue(dispatcher.commands.contains("/guardian_ack 1"))
    assertFalse(callbackData(result).contains("incident-uuid"))
}
```

- [ ] **Step 2: Run Telegram tests and verify failure**

Run: `./gradlew testDebugUnitTest --tests 'id.my.femasaf.secondlink.GuardianTelegramTest' --tests 'id.my.femasaf.secondlink.TelegramKeyboardTest'`

Expected: FAIL because the Guardian screen/actions do not exist.

- [ ] **Step 3: Add fixed Guardian presentation state and callbacks**

Add `GUARDIAN` to `MenuScreen` and extend `MenuRenderState`:

```kotlin
data class MenuRenderState(
    val unlocked: Boolean = false,
    val mediaReady: Boolean = false,
    val mediaSessionCount: Int = 0,
    val selectedMediaSession: Int? = null,
    val guardianStatus: String = "Aktif",
    val guardianActiveCount: Int = 0,
    val guardianPendingAlerts: Int = 0,
    val guardianIncidentSlots: Int = 0
)
```

Add scope `guardian` with actions `active`, `history`, `check`, `test`, `ack_1` through `ack_8`, and `back`. Callback data contains only fixed action IDs, slot numbers, and nonce; it never contains incident UUIDs or values.

- [ ] **Step 4: Route read-only Guardian actions**

Map `active`, `history`, `check`, and `ack_N` to `/guardian_active`, `/guardian_history`, `/guardian_check`, and `/guardian_ack N` through the shared dispatcher. Resolve slot N against the current ordered active-incident list inside the service. Reject an expired/missing slot with `Insiden sudah berubah. Buka ulang menu Guardian.`

`/guardian_test` must require an unlocked session and create a clearly labeled synthetic Telegram message without persisting a real incident or changing safety state.

- [ ] **Step 5: Format menu and alert action markup**

The Guardian menu text shows status, active count, last check, and pending messages. Alert markup exposes **Periksa Lagi**, **Lihat Status**, **Akui**, and **Buka Menu Guardian**. Keep existing owner binding, callback age, rate limiting, and nonce validation.

- [ ] **Step 6: Run all Telegram suites**

Run: `./gradlew testDebugUnitTest --tests 'id.my.femasaf.secondlink.GuardianTelegramTest' --tests 'id.my.femasaf.secondlink.TelegramKeyboardTest' --tests 'id.my.femasaf.secondlink.TelegramMenuRouterTest' --tests 'id.my.femasaf.secondlink.TelegramCallbackModelTest'`

Expected: PASS.

- [ ] **Step 7: Commit Telegram UX**

```bash
git add app/src/main/java/id/my/femasaf/secondlink/TelegramMenuState.kt app/src/main/java/id/my/femasaf/secondlink/TelegramKeyboard.kt app/src/main/java/id/my/femasaf/secondlink/TelegramMenuRouter.kt app/src/main/java/id/my/femasaf/secondlink/BotService.kt app/src/test/java/id/my/femasaf/secondlink/GuardianTelegramTest.kt app/src/test/java/id/my/femasaf/secondlink/TelegramKeyboardTest.kt app/src/test/java/id/my/femasaf/secondlink/TelegramMenuRouterTest.kt
git commit -m "feat(guardian): add Telegram incident menu"
```

---

### Task 8: Add Local Guardian to the Android dashboard

**Files:**

- Create: `app/src/main/java/id/my/femasaf/secondlink/GuardianUi.kt`
- Modify: `app/src/main/java/id/my/femasaf/secondlink/BotViewModel.kt`
- Modify: `app/src/main/java/id/my/femasaf/secondlink/MainActivity.kt`
- Test: `app/src/test/java/id/my/femasaf/secondlink/GuardianUiStateTest.kt`

**Interfaces:**

- Consumes: repository summary/history from Tasks 2–3.
- Produces: `GuardianLoadState`, dashboard card, detail screen, manual refresh, and acknowledgement action.

- [ ] **Step 1: Write UI-state reducer tests without Compose instrumentation**

```kotlin
@Test fun criticalIncidentBecomesPrimaryAndStatusNeedsAction() {
    val state = GuardianUiReducer.reduce(listOf(warningBattery(), criticalTemperature()), pendingAlerts = 2, checkedAt = 1_000L)
    assertEquals("Perlu tindakan", state.summary.status)
    assertEquals(GuardianIncidentType.TEMPERATURE, state.summary.primaryIncident?.type)
    assertEquals(2, state.summary.pendingAlerts)
}

@Test fun unavailableRefreshKeepsLastSuccessfulData() {
    val failed = GuardianUiReducer.failed(previousLoadedState())
    assertNotNull(failed.summary.lastCheckedAtEpochMs)
    assertEquals("Pemeriksaan gagal. Coba lagi.", failed.error)
}
```

- [ ] **Step 2: Run the UI-state test and verify failure**

Run: `./gradlew testDebugUnitTest --tests 'id.my.femasaf.secondlink.GuardianUiStateTest'`

Expected: FAIL because Guardian UI state does not exist.

- [ ] **Step 3: Add Guardian state to `DashboardUiState` and ViewModel**

```kotlin
data class GuardianLoadState(
    val summary: GuardianSummary = GuardianSummary(),
    val active: List<GuardianIncidentSummary> = emptyList(),
    val history: List<GuardianIncidentSummary> = emptyList(),
    val isRefreshing: Boolean = false,
    val error: String? = null
)

object GuardianUiReducer {
    fun reduce(
        incidents: List<GuardianIncidentSummary>,
        pendingAlerts: Int,
        checkedAt: Long
    ): GuardianLoadState

    fun failed(previous: GuardianLoadState): GuardianLoadState =
        previous.copy(isRefreshing = false, error = "Pemeriksaan gagal. Coba lagi.")
}
```

Add `guardian: GuardianLoadState` to `DashboardUiState`. Collect repository flows in `viewModelScope`; expose `refreshGuardian()` and `acknowledgeGuardianIncident(id)`. Preserve the last successful snapshot on errors.

- [ ] **Step 4: Build focused Compose components**

Create:

```kotlin
@Composable fun GuardianSummaryCard(state: GuardianLoadState, onOpen: () -> Unit)

@Composable fun GuardianScreen(
    state: GuardianLoadState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onAcknowledge: (String) -> Unit
)

@Composable fun GuardianIncidentRow(item: GuardianIncidentSummary, onAcknowledge: (String) -> Unit)
```

Use text plus icon for all levels. Show `Aktif`, `Terbatas`, or `Perlu tindakan`; active count; primary incident; last check; pending outbox; active incidents; and 30-day history. Do not expose raw database fields.

- [ ] **Step 5: Add dashboard navigation**

In `DashboardScreen`, add `showGuardian` with `rememberSaveable`, place `GuardianSummaryCard` immediately after `DeviceHealthSummaryCard`, and use the existing local-state navigation/back-handler pattern. Manual refresh calls `viewModel.refreshGuardian()`.

- [ ] **Step 6: Run UI-state and build verification**

Run: `./gradlew testDebugUnitTest --tests 'id.my.femasaf.secondlink.GuardianUiStateTest' && ./gradlew assembleDebug`

Expected: both commands PASS.

- [ ] **Step 7: Commit Android UI**

```bash
git add app/src/main/java/id/my/femasaf/secondlink/GuardianUi.kt app/src/main/java/id/my/femasaf/secondlink/BotViewModel.kt app/src/main/java/id/my/femasaf/secondlink/MainActivity.kt app/src/test/java/id/my/femasaf/secondlink/GuardianUiStateTest.kt
git commit -m "feat(guardian): add Android incident dashboard"
```

---

### Task 9: Complete retention, privacy, and end-to-end behavior

**Files:**

- Modify: `app/src/main/java/id/my/femasaf/secondlink/GuardianPersistence.kt`
- Modify: `app/src/main/java/id/my/femasaf/secondlink/GuardianAlerts.kt`
- Modify: `app/src/main/java/id/my/femasaf/secondlink/GuardianCoordinator.kt`
- Modify: `app/src/main/java/id/my/femasaf/secondlink/BotService.kt`
- Test: `app/src/test/java/id/my/femasaf/secondlink/GuardianPrivacyTest.kt`
- Test: `app/src/test/java/id/my/femasaf/secondlink/GuardianEndToEndTest.kt`

**Interfaces:**

- Consumes: completed Guardian stack.
- Produces: verified retention, offline lifecycle, reboot interruption, and redaction guarantees.

- [ ] **Step 1: Write privacy and retention tests**

```kotlin
@Test fun persistedAndFormattedDataExcludeSecrets() {
    val forbidden = listOf("123456:botToken", "accessCode42", "192.168.1.2", "HomeWifi", "https://example.com/file")
    val serialized = fixtureRepositoryDump() + fixtureAlertBody()
    forbidden.forEach { assertFalse(serialized.contains(it)) }
}

@Test fun retentionKeepsNewest500WithinThirtyDays() = runTest {
    repository.insertIncidents(older = 10, recent = 510)
    repository.prune(NOW)
    assertEquals(500, repository.recentIncidents(1000).size)
    assertTrue(repository.recentIncidents(1000).all { it.updatedAtEpochMs >= NOW - THIRTY_DAYS })
}
```

- [ ] **Step 2: Write an offline end-to-end test**

```kotlin
@Test fun offlineOpenEscalateRecoverSendsOneSummary() = runTest {
    harness.setOnline(false)
    harness.sample(lowBatteryWarning())
    harness.sample(lowBatteryCritical())
    harness.sample(healthyCharging())
    assertTrue(harness.sentMessages.isEmpty())
    harness.setOnline(true)
    harness.flush()
    assertEquals(1, harness.sentMessages.size)
    assertTrue(harness.sentMessages.single().contains("pulih"))
}
```

Add cases for reboot interruption, access initially missing without an alert, access revoked after healthy baseline, three-reminder daily cap, and `USER_ACTION_REQUIRED` after bounded recovery attempts.

- [ ] **Step 3: Run the new tests and fix only concrete failures**

Run: `./gradlew testDebugUnitTest --tests 'id.my.femasaf.secondlink.GuardianPrivacyTest' --tests 'id.my.femasaf.secondlink.GuardianEndToEndTest'`

Expected: PASS after retention is invoked following state transactions and alert formatter inputs are restricted to typed/redacted fields.

- [ ] **Step 4: Run the complete unit suite and migration test**

Run: `./gradlew testDebugUnitTest && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=id.my.femasaf.secondlink.AppDatabaseMigrationTest`

Expected: PASS.

- [ ] **Step 5: Commit hardening**

```bash
git add app/src/main/java/id/my/femasaf/secondlink/GuardianPersistence.kt app/src/main/java/id/my/femasaf/secondlink/GuardianAlerts.kt app/src/main/java/id/my/femasaf/secondlink/GuardianCoordinator.kt app/src/main/java/id/my/femasaf/secondlink/BotService.kt app/src/test/java/id/my/femasaf/secondlink/GuardianPrivacyTest.kt app/src/test/java/id/my/femasaf/secondlink/GuardianEndToEndTest.kt
git commit -m "test(guardian): harden offline incident flow"
```

---

### Task 10: Device verification and release readiness

**Files:**

- Modify only if a verified defect requires it: files owned by Tasks 1–9.
- Update: `docs/superpowers/specs/2026-08-15-local-guardian-design.md` status to `Implemented and verified` only after every check passes.

**Interfaces:**

- Consumes: completed feature and all automated suites.
- Produces: a verified debug APK and a manual evidence checklist.

- [ ] **Step 1: Run static diff checks and all local tests**

Run: `git diff --check && ./gradlew testDebugUnitTest lintDebug assembleDebug`

Expected: all commands exit 0; APK exists at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 2: Run database migration instrumentation**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=id.my.femasaf.secondlink.AppDatabaseMigrationTest`

Expected: PASS on an API 26+ emulator/device.

- [ ] **Step 3: Verify device scenarios in this exact order**

1. Start service normally and confirm Telegram plus Android show `Aktif` with the same check time.
2. Enable airplane mode for less than two minutes and confirm no incident is emitted.
3. Enable airplane mode for more than two minutes, restore internet, and confirm one duration summary arrives.
4. Revoke and restore camera, contacts, notifications, and Notification Listener access after a healthy baseline; confirm one open and one recovery transition per access.
5. Trigger the synthetic test alert and confirm it is labeled as a test and creates no persistent incident.
6. Acknowledge a critical test fixture and confirm reminders stop while the incident stays active.
7. Reboot with bot enabled and confirm boot-origin startup is reported once after Telegram reconnects.
8. While a critical safety fixture is active, confirm camera/download/scan/heavy cron are rejected, `/status` works, and alarm stop works.
9. Create app-owned temporary files, invoke cleanup, and confirm only canonical paths under SecondLink cache/app downloads are removed.
10. Open Guardian on a small-screen emulator and confirm the card/detail screen scroll without clipped actions.

- [ ] **Step 4: Update the spec status after evidence is complete**

Change only the status line to:

```markdown
Status: Implemented and verified
```

- [ ] **Step 5: Re-run final verification after any device-found fix**

Run: `git diff --check && ./gradlew testDebugUnitTest lintDebug assembleDebug`

Expected: PASS with no uncommitted generated noise outside the schema/APK outputs expected by the project.

- [ ] **Step 6: Commit final verification state**

```bash
git add docs/superpowers/specs/2026-08-15-local-guardian-design.md
git commit -m "docs(guardian): mark implementation verified"
```
