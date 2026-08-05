# Telegram Button Command Center Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Menjadikan inline keyboard Telegram sebagai cara utama menjalankan seluruh fungsi SecondLink, mempertahankan command teks sebagai fallback, dan mengamankan callback sensitif dengan sesi unlock lima menit.

**Architecture:** Update Telegram diperluas untuk menerima `callback_query`. `TelegramMenuState` menyimpan menu aktif, submenu, input terpandu, dan sesi unlock di memori per Chat ID/user. `TelegramMenuRouter` memvalidasi callback lalu memanggil handler domain yang sama dengan router command teks; `TelegramKeyboard` hanya membangun tombol dan callback data whitelist.

**Tech Stack:** Kotlin, Coroutines, Retrofit/Gson Telegram Bot API, Android foreground `LifecycleService`, Room audit log, JUnit.

## Global Constraints

- Command teks lama tetap berfungsi sebagai fallback dan tidak boleh dihapus.
- Inline keyboard menjadi jalur utama; menu utama dan submenu mengedit satu pesan Telegram yang sama.
- Callback sensitif memerlukan unlock valid selama lima menit, terikat pada Chat ID dan Telegram user ID.
- Kode unlock harus dihapus dari chat sebelum sesi dianggap berhasil.
- Callback data tidak boleh memuat token, kode akses, URL, atau isi pesan pengguna.
- Input terpandu memiliki tombol batal, expiry lima menit, batas panjang, dan validasi whitelist.
- Status/help boleh tanpa unlock; kamera, kontak, scan Wi‑Fi, file, alarm, jadwal mutatif, dan kontrol media wajib unlock.
- Setiap callback diverifikasi action whitelist, versi menu, user, chat, usia, expiry, dan rate limit.
- Tidak mengirim exception detail, rahasia, URL privat, atau kode akses ke Telegram/Logcat/audit.
- Verifikasi wajib: `./gradlew assembleDebug testDebugUnitTest lintDebug assembleRelease`.

---

### Task 1: Model Telegram callback dan API markup

**Files:**
- Modify: `app/src/main/java/com/blok/cuaca/TelegramModels.kt`
- Modify: `app/src/main/java/com/blok/cuaca/TelegramApi.kt`
- Create: `app/src/test/java/com/blok/cuaca/TelegramCallbackModelTest.kt`

**Interfaces:**
- `TelegramUpdate.callbackQuery: TelegramCallbackQuery?`
- `TelegramCallbackQuery(id: String, from: TelegramUser, message: TelegramMessage?, data: String?)`
- `TelegramInlineKeyboardButton(text: String, callbackData: String?)`
- `TelegramReplyMarkup(inlineKeyboard: List<List<TelegramInlineKeyboardButton>>)`
- `TelegramApi.sendMessage(..., replyMarkup: String? = null)`
- `TelegramApi.editMessageText(chatId: String, messageId: Long, text: String, replyMarkup: String? = null)`
- `TelegramApi.answerCallbackQuery(id: String, text: String? = null, showAlert: Boolean = false)`

- [ ] **Step 1: Write failing model serialization tests**

```kotlin
@Test fun callbackQueryIsParsedFromUpdate() {
    val update = Gson().fromJson(jsonWithCallbackQuery, TelegramUpdate::class.java)
    assertEquals("v1:menu:status", update.callbackQuery?.data)
    assertEquals(5562277502L, update.callbackQuery?.from?.id)
}
```

- [ ] **Step 2: Run the focused test and confirm it fails**

Run: `./gradlew testDebugUnitTest --tests '*TelegramCallbackModelTest'`

Expected: FAIL because callback query and markup models do not exist.

- [ ] **Step 3: Add models and JSON API fields**

Use `@SerializedName("callback_query")`, `@SerializedName("reply_markup")`, and serialize markup with Gson before passing it to Retrofit. Keep callback data under Telegram’s callback-data size limit.

- [ ] **Step 4: Add Retrofit methods and send/edit markup parameters**

The API methods must call `answerCallbackQuery` immediately after validation and use `editMessageText` for submenu changes.

- [ ] **Step 5: Run tests and lint**

Run: `./gradlew testDebugUnitTest --tests '*TelegramCallbackModelTest' lintDebug`

Expected: PASS; lint may report only pre-existing non-error warnings.

---

### Task 2: Keyboard definitions and menu state/unlock

**Files:**
- Create: `app/src/main/java/com/blok/cuaca/TelegramKeyboard.kt`
- Create: `app/src/main/java/com/blok/cuaca/TelegramMenuState.kt`
- Create: `app/src/test/java/com/blok/cuaca/TelegramMenuStateTest.kt`

**Interfaces:**
- `enum class MenuScreen { MAIN, STATUS, MEDIA, CAMERA, DEVICE, SCHEDULE, FILE, SECURITY }`
- `data class UnlockSession(val chatId: String, val userId: Long, val nonce: String, val expiresAt: Long)`
- `data class PendingInput(val chatId: String, val userId: Long, val kind: InputKind, val expiresAt: Long, val values: Map<String, String> = emptyMap())`
- `class TelegramMenuState { fun unlock(...): Boolean; fun isUnlocked(...): Boolean; fun lock(...); fun beginInput(...); fun consumeInput(...); fun clear(...); fun purgeExpired(now: Long) }`
- `object TelegramKeyboard { fun main(...): TelegramReplyMarkup; fun screen(...): TelegramReplyMarkup; fun callback(action: String, nonce: String? = null): String }`

- [ ] **Step 1: Write failing state tests**

```kotlin
@Test fun unlockExpiresAfterFiveMinutes() {
    val state = TelegramMenuState(clock = FakeClock(1_000L))
    state.unlock("chat", 7L)
    assertTrue(state.isUnlocked("chat", 7L))
    clock.advanceBy(5 * 60 * 1000L + 1L)
    assertFalse(state.isUnlocked("chat", 7L))
}

@Test fun anotherUserCannotReuseUnlock() { /* assert false for user 8 */ }
@Test fun pendingInputIsBoundToOwnerAndExpires() { /* assert owner/expiry */ }
```

- [ ] **Step 2: Run focused tests and verify failure**

Run: `./gradlew testDebugUnitTest --tests '*TelegramMenuStateTest'`

Expected: FAIL because state types do not exist.

- [ ] **Step 3: Implement bounded in-memory state**

Use a synchronized map keyed by `(chatId, userId)`, five-minute expiry constants, random nonce generation, and explicit `lock`/`clear` operations. Never persist unlock state to Room, SharedPreferences, or backup.

- [ ] **Step 4: Implement keyboard builders**

Build short versioned callback values such as `v1:menu:media`, `v1:media:play`, and `v1:security:unlock`. Do not embed URLs, codes, reminder text, or package names; use numbered selection state instead.

- [ ] **Step 5: Run state tests and verify callback limits**

Run: `./gradlew testDebugUnitTest --tests '*TelegramMenuStateTest'`

Expected: PASS, including expiry, user binding, input ownership, and callback-data length tests.

---

### Task 3: Callback router and shared command dispatch

**Files:**
- Create: `app/src/main/java/com/blok/cuaca/TelegramMenuRouter.kt`
- Modify: `app/src/main/java/com/blok/cuaca/BotService.kt`
- Modify: `app/src/main/java/com/blok/cuaca/BotManager.kt`
- Create: `app/src/test/java/com/blok/cuaca/TelegramMenuRouterTest.kt`

**Interfaces:**
- `class TelegramMenuRouter(private val state: TelegramMenuState, private val dispatcher: CommandDispatcher, private val clock: () -> Long)`
- `suspend fun handle(callback: TelegramCallbackQuery): RouterResult`
- `sealed interface RouterResult { data class EditMenu(...); data class SendMessage(...); data class Answer(...); data object Ignored }`
- `interface CommandDispatcher { suspend fun dispatch(command: String, chatId: String, userId: Long): CommandResult }`

- [ ] **Step 1: Write failing router tests**

```kotlin
@Test fun foreignUserCallbackIsIgnored() { /* callback chat/user mismatch */ }
@Test fun sensitiveCallbackRequiresUnlock() { /* expect unlock prompt */ }
@Test fun staleMenuVersionIsRejected() { /* expect refresh result */ }
@Test fun validCallbackDispatchesWhitelistedCommand() { /* expect media play dispatch */ }
```

- [ ] **Step 2: Run focused tests and confirm failure**

Run: `./gradlew testDebugUnitTest --tests '*TelegramMenuRouterTest'`

Expected: FAIL because router and dispatcher interfaces do not exist.

- [ ] **Step 3: Extract shared domain dispatch from `handleUpdate`**

Keep the existing text parser as an adapter. Both text commands and callback actions must call the same bounded handlers for status, camera, media, devices, schedules, and files.

- [ ] **Step 4: Add callback validation and unlock enforcement**

Validate callback age, message ownership, Chat ID, user ID, `v1` version, action whitelist, input/session expiry, and rate limit before dispatch. Always answer callback queries, including rejected ones.

- [ ] **Step 5: Integrate callback updates into polling**

Process `update.callbackQuery` before `update.message`, save the Telegram update offset exactly once, and preserve the existing command mutex. Audit only action names and outcome.

- [ ] **Step 6: Run router tests**

Run: `./gradlew testDebugUnitTest --tests '*TelegramMenuRouterTest'`

Expected: PASS for valid, foreign, stale, locked, rate-limited, and unknown callbacks.

---

### Task 4: Main menu and all submenus

**Files:**
- Modify: `app/src/main/java/com/blok/cuaca/BotService.kt`
- Modify: `app/src/main/java/com/blok/cuaca/TelegramKeyboard.kt`
- Modify: `app/src/main/java/com/blok/cuaca/TelegramMenuRouter.kt`
- Modify: `app/src/test/java/com/blok/cuaca/TelegramKeyboardTest.kt`

**Interfaces:**
- `suspend fun sendMainMenu(chatId: String, userId: Long, messageId: Long? = null)`
- `fun render(screen: MenuScreen, state: MenuRenderState): TelegramReplyMarkup`

- [ ] **Step 1: Write keyboard mapping tests**

```kotlin
@Test fun everyMainMenuButtonUsesKnownAction() { /* assert whitelist */ }
@Test fun mediaScreenContainsAllSupportedControls() { /* assert action IDs */ }
@Test fun sensitiveButtonsDoNotEmbedSecretsOrRawInput() { /* assert callback data */ }
```

- [ ] **Step 2: Implement main menu and screen renderers**

Render status/security/media readiness in the menu text. Use compact two-column rows and a universal `back`/`main` action.

- [ ] **Step 3: Wire existing handlers to submenu actions**

Map camera, contact, Wi‑Fi, alarm, cron, reminder, download, and media buttons to shared dispatch commands. Results should edit or refresh the menu rather than create unbounded menu messages.

- [ ] **Step 4: Add menu refresh and close behavior**

Store the latest menu message ID per owner. Refresh creates a new menu only when the old message cannot be edited; close clears menu state and removes markup where Telegram permits.

- [ ] **Step 5: Run keyboard tests**

Run: `./gradlew testDebugUnitTest --tests '*TelegramKeyboardTest'`

Expected: PASS with all screens reachable and no secret-bearing callback data.

---

### Task 5: Guided input flows and five-minute security unlock

**Files:**
- Modify: `app/src/main/java/com/blok/cuaca/TelegramMenuRouter.kt`
- Modify: `app/src/main/java/com/blok/cuaca/TelegramMenuState.kt`
- Modify: `app/src/main/java/com/blok/cuaca/BotService.kt`
- Create: `app/src/test/java/com/blok/cuaca/GuidedInputFlowTest.kt`

**Interfaces:**
- `enum class InputKind { ACCESS_CODE, DOWNLOAD_URL, REMINDER_DURATION, REMINDER_MESSAGE, CRON_TIME, CRON_COMMAND }`
- `suspend fun startUnlock(chatId: String, userId: Long): RouterResult`
- `suspend fun acceptTextInput(message: TelegramMessage): RouterResult`
- `fun cancelInput(chatId: String, userId: Long): RouterResult`

- [ ] **Step 1: Write failing flow tests**

```kotlin
@Test fun correctUnlockDeletesCodeBeforeCreatingSession() { /* assert delete then unlock */ }
@Test fun wrongUnlockNeverEnablesSensitiveButtons() { /* assert locked */ }
@Test fun downloadFlowValidatesHttpsAndConfirmsFilename() { /* assert confirmation */ }
@Test fun cancelClearsPendingInput() { /* assert no later dispatch */ }
```

- [ ] **Step 2: Run focused tests and confirm failure**

Run: `./gradlew testDebugUnitTest --tests '*GuidedInputFlowTest'`

Expected: FAIL because guided flow handlers do not exist.

- [ ] **Step 3: Implement unlock flow**

Ask for the existing access code, verify it through `Config.verifyAccessCode`, call `deleteMessage`, and only then create the in-memory five-minute session. If deletion fails, keep the session locked.

- [ ] **Step 4: Implement download/reminder/cron/media selection flows**

Each flow stores only bounded temporary values, validates at each step, supports cancel, and dispatches only after final confirmation. Reuse existing `SecurityPolicy`, reminder limits, cron whitelist, and media package selection.

- [ ] **Step 5: Handle normal text commands alongside pending flows**

Only the owner’s next message can advance a pending flow. `/cancel` always clears it. Other text commands remain available as fallback and do not inherit accidental pending-flow data.

- [ ] **Step 6: Run flow tests**

Run: `./gradlew testDebugUnitTest --tests '*GuidedInputFlowTest'`

Expected: PASS for unlock ordering, expiry, cancellation, invalid input, and owner binding.

---

### Task 6: Verification, manual test matrix, and release checks

**Files:**
- Modify: `app/src/test/java/com/blok/cuaca/TelegramCallbackModelTest.kt`
- Modify: `app/src/test/java/com/blok/cuaca/TelegramMenuStateTest.kt`
- Modify: `app/src/test/java/com/blok/cuaca/TelegramMenuRouterTest.kt`
- Modify: `app/src/test/java/com/blok/cuaca/GuidedInputFlowTest.kt`
- Modify: `app/src/test/java/com/blok/cuaca/TelegramKeyboardTest.kt`

- [ ] **Step 1: Run the complete unit test suite**

Run: `./gradlew testDebugUnitTest`

Expected: PASS.

- [ ] **Step 2: Run lint and debug build**

Run: `./gradlew lintDebug assembleDebug`

Expected: no lint errors and a debug APK produced.

- [ ] **Step 3: Run release build with R8**

Run: `./gradlew assembleRelease`

Expected: release APK produced with minification and resource shrinking enabled.

- [ ] **Step 4: Perform the manual Telegram matrix**

Verify main menu, every submenu, back/refresh/close, stale callback, second Telegram user, wrong/expired unlock, five-minute expiry, cancel flow, offline recovery, media Notification access, Spotify/YouTube Music/local player capability differences, and all legacy text commands.

- [ ] **Step 5: Check final manifest and secrets**

Confirm the merged manifest contains the notification-listener binding permission, no cleartext traffic, backup disabled, and no exported application service. Search source and APK inputs for token literals, access-code literals, raw URLs, and callback payload secrets.

---

## Self-review

- Spec coverage: menu, submenus, guided input, unlock, callback validation, errors, state, tests, and command fallback each have dedicated tasks.
- Placeholder scan: no TODO/TBD/FIXME or undefined future work appears in the plan.
- Type consistency: `TelegramMenuState`, `TelegramMenuRouter`, `CommandDispatcher`, `RouterResult`, `InputKind`, and keyboard interfaces are defined before later tasks consume them.
- Scope: all tasks belong to one cohesive Telegram interaction subsystem; media-specific behavior is reused rather than redesigned.
- Repository constraint: the workspace currently is not recognized as a Git repository, so commit steps are omitted from execution and changes must be reviewed through the filesystem/build outputs.
