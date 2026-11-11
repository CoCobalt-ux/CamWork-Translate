package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.core.Logger
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.spellchecker.*
import com.github.ahatem.qtranslate.api.translator.*
import com.github.ahatem.qtranslate.api.tts.TTSAudio
import com.github.ahatem.qtranslate.core.audio.AudioPlayer
import com.github.ahatem.qtranslate.core.document.DocumentTranslationUseCase
import com.github.ahatem.qtranslate.core.history.HistoryRepository
import com.github.ahatem.qtranslate.core.history.HistorySnapshot
import com.github.ahatem.qtranslate.core.main.domain.usecase.*
import com.github.ahatem.qtranslate.core.settings.data.*
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import com.github.ahatem.qtranslate.core.updater.Updater
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.test.*

class MainStoreRecoveryTest {
    @Test
    fun `смена только источника popup сохраняет показанный target при сброшенном override`() = runBlocking {
        withFixture { fixture ->
            fixture.store.dispatch(MainIntent.SelectTargetLanguage(LanguageCode.GERMAN))
            fixture.store.dispatch(MainIntent.AutoTranslateSelection("Hello there", requestId = 93_000))
            fixture.translator.next().succeed("Привет", LanguageCode.ENGLISH)
            fixture.awaitState { it.isQuickTranslateDialogPassive && it.translatedText == "Привет" }
            fixture.store.dispatch(MainIntent.SelectTargetLanguage(LanguageCode.ENGLISH))
            assertEquals(null, fixture.store.state.value.quickTranslateTargetLanguageOverride)
            assertEquals(LanguageCode.RUSSIAN, fixture.store.state.value.resolvedQuickTranslateTargetLanguage)
            fixture.store.dispatch(MainIntent.SelectQuickTranslatePassiveSourceLanguage(LanguageCode.AUTO))
            val changedSource = fixture.translator.next()
            assertEquals(LanguageCode.AUTO, changedSource.request.sourceLanguage)
            assertEquals(LanguageCode.RUSSIAN, changedSource.request.targetLanguage)
            changedSource.succeed("Повторный перевод", LanguageCode.ENGLISH)
            fixture.awaitState { it.translatedText == "Повторный перевод" && !it.isLoading }
            assertEquals(LanguageCode.AUTO, fixture.store.state.value.sourceLanguage)
            assertEquals(LanguageCode.ENGLISH, fixture.store.state.value.targetLanguage)
        }
    }

    @Test
    fun `AUTO сохраняется между разными определениями и перезагрузкой сервисов`() = runBlocking {
        withFixture { fixture ->
            listOf(LanguageCode.ENGLISH, LanguageCode.GERMAN, LanguageCode.UKRAINIAN).forEachIndexed { index, detected ->
                fixture.translate("Исходный текст $index")
                val pending = fixture.translator.next()
                assertEquals(LanguageCode.AUTO, pending.request.sourceLanguage)
                pending.succeed("Результат $index", detected)
                fixture.awaitState { it.translatedText == "Результат $index" }
                assertEquals(LanguageCode.AUTO, fixture.store.state.value.sourceLanguage)
                assertEquals(detected, fixture.store.state.value.detectedSourceLanguage)
            }
            fixture.services.value = emptyMap()
            fixture.awaitState { it.availableServices.isEmpty() }
            fixture.translator.languages = SupportedLanguages.Specific(setOf(LanguageCode.ENGLISH, LanguageCode.GERMAN))
            fixture.services.value = mapOf("audit" to fixture.translator)
            fixture.awaitState { it.availableLanguages.size == 2 }
            assertEquals(LanguageCode.AUTO, fixture.store.state.value.sourceLanguage)
        }
    }

    @Test
    fun `стрелки пассивного popup не превращают AUTO основного окна в определённый язык`() = runBlocking {
        withFixture { fixture ->
            fixture.store.dispatch(MainIntent.SelectTargetLanguage(LanguageCode.GERMAN))
            fixture.store.dispatch(MainIntent.AutoTranslateSelection("Hello there", requestId = 91_000))
            fixture.translator.next().succeed("Привет", LanguageCode.ENGLISH)
            fixture.awaitState { it.isQuickTranslateDialogPassive && it.translatedText == "Привет" }
            fixture.store.dispatch(MainIntent.SwapQuickTranslateLanguages)
            val swapped = fixture.translator.next()
            assertEquals("Привет", swapped.request.text)
            assertEquals(LanguageCode.RUSSIAN, swapped.request.sourceLanguage)
            assertEquals(LanguageCode.ENGLISH, swapped.request.targetLanguage)
            swapped.succeed("Hello there", LanguageCode.RUSSIAN)
            fixture.awaitState { it.translatedText == "Hello there" && !it.isLoading }
            assertEquals(LanguageCode.AUTO, fixture.store.state.value.sourceLanguage)
            assertEquals(LanguageCode.GERMAN, fixture.store.state.value.targetLanguage)
            assertEquals(LanguageCode.RUSSIAN, fixture.store.state.value.quickTranslateSourceLanguageOverride)
            assertEquals(LanguageCode.ENGLISH, fixture.store.state.value.quickTranslateTargetLanguageOverride)
        }
    }

    @Test
    fun `повторный выбор AUTO в пассивном popup публикует новое определение отдельно от выбора`() = runBlocking {
        withFixture { fixture ->
            fixture.translate("Текст основного окна")
            fixture.translator.next().succeed("Основной перевод", LanguageCode.UKRAINIAN)
            fixture.awaitState { it.translatedText == "Основной перевод" }
            fixture.store.dispatch(MainIntent.AutoTranslateSelection("Hello there", requestId = 92_000))
            fixture.translator.next().succeed("Привет", LanguageCode.ENGLISH)
            fixture.awaitState { it.isQuickTranslateDialogPassive && it.translatedText == "Привет" }
            fixture.store.dispatch(MainIntent.SelectQuickTranslatePassiveSourceLanguage(LanguageCode.AUTO))
            val selected = fixture.translator.next()
            assertEquals(LanguageCode.AUTO, selected.request.sourceLanguage)
            selected.succeed("Повторный перевод", LanguageCode.GERMAN)
            fixture.awaitState { it.translatedText == "Повторный перевод" && !it.isLoading }
            assertEquals(LanguageCode.AUTO, fixture.store.state.value.quickTranslateSourceLanguageOverride)
            assertEquals(LanguageCode.GERMAN, fixture.store.state.value.quickTranslateDetectedLanguageOverride)
            assertEquals(LanguageCode.AUTO, fixture.store.state.value.sourceLanguage)
        }
    }

    @Test
    fun `выбор AUTO сохраняется в реальном DataStore после других настроек и повторного чтения`() = runBlocking {
        withFixture { fixture ->
            val json = Json { ignoreUnknownKeys = true }
            val directory = File(fixture.directory, "language-settings").apply { mkdirs() }
            val repository = SettingsRepository(directory, json, RecoveryLogger)
            val initial = Configuration.DEFAULT.copy(preferredSourceLanguage = "de")
            assertTrue(repository.updateConfiguration(initial).isOk)
            val settings = SettingsStore(repository, RecoveryLogger, fixture.scope, initial)
            fixture.store.dispatch(MainIntent.SelectSourceLanguage(LanguageCode.AUTO))
            settings.dispatch(SettingsIntent.ToggleSetting { it.copy(preferredSourceLanguage = "auto") })
            assertTrue(settings.saveChanges().isOk)
            fixture.translate("Hello there")
            fixture.translator.next().succeed("Перевод", LanguageCode.ENGLISH)
            fixture.awaitState { it.translatedText == "Перевод" }
            settings.dispatch(SettingsIntent.ToggleSetting { it.copy(uiScale = 125) })
            assertTrue(settings.saveChanges().isOk)
            val copied = File(fixture.directory, "language-settings-restart/datastore").apply { mkdirs() }
            File(directory, "datastore/app_settings.preferences_pb").copyTo(File(copied, "app_settings.preferences_pb"))
            val restored = SettingsRepository(copied.parentFile, json, RecoveryLogger).loadInitialConfiguration()
            assertEquals("auto", restored.preferredSourceLanguage)
            assertEquals(125, restored.uiScale)
            assertEquals(LanguageCode.AUTO, fixture.store.state.value.sourceLanguage)
        }
    }

    @Test
    fun `ошибка физической очистки видна пользователю а повтор после восстановления подтверждён`() = runBlocking {
        val storage = HangingHistoryStore(hangWriteNumber = 2)
        withFixture(storage = storage, storageTimeoutMillis = 50, consumeEvents = false) { fixture ->
            fixture.store.dispatch(MainIntent.ClearHistory)
            val failure = withTimeout(5_000) {
                fixture.store.events.filterIsInstance<MainEvent.UpdateStatusBar>()
                    .first { it.code is StatusCode.UnexpectedError }
            }
            assertEquals(NotificationType.ERROR, failure.type)
            assertTrue((failure.code as StatusCode.UnexpectedError).summary.contains("физическая очистка"))
            assertTrue(fixture.store.state.value.history.isEmpty())
            assertTrue(storage.data.first()[stringPreferencesKey("translation_history_json")].orEmpty().contains("Old entry"))
            assertTrue(fixture.history.clearHistory())
            assertTrue(storage.data.first().asMap().isEmpty())
        }
    }

    @Test
    fun `новый перевод после восстановления истории задаёт правильный курсор undo redo`() = runBlocking {
        withFixture { fixture ->
            fixture.translate("Первый новый")
            fixture.translator.next().succeed("First new")
            fixture.awaitState { it.translatedText == "First new" && it.history.size == 2 }
            fixture.translate("Второй новый")
            fixture.translator.next().succeed("Second new")
            fixture.awaitState { it.translatedText == "Second new" && it.history.size == 3 }
            fixture.store.dispatch(MainIntent.UndoTranslation)
            assertEquals("First new", fixture.store.state.value.translatedText)
            fixture.store.dispatch(MainIntent.RestoreHistoryEntry(fixture.history.entries.value.first()))
            fixture.translate("Третий новый")
            fixture.translator.next().succeed("Third new")
            fixture.awaitState { it.translatedText == "Third new" && it.historyIndex == 4 }
            assertEquals(4, fixture.history.entries.value.size)
            fixture.store.dispatch(MainIntent.UndoTranslation)
            assertEquals("Second new", fixture.store.state.value.translatedText)
            fixture.store.dispatch(MainIntent.RedoTranslation)
            assertEquals("Third new", fixture.store.state.value.translatedText)
        }
    }

    @Test
    fun `таймаут диска не блокирует показ ответа и следующие переводы`() = runBlocking {
        val storage = HangingHistoryStore(hangWriteNumber = 2)
        withFixture(storage = storage, storageTimeoutMillis = 50) { fixture ->
            fixture.translate("Первый при отказе диска")
            fixture.translator.next().succeed("First result")
            withTimeout(5_000) { storage.hung.await() }
            assertEquals("First result", fixture.store.state.value.translatedText)
            withTimeout(5_000) { storage.released.await() }
            fixture.awaitState { it.history.size == 2 }
            fixture.translate("Следующий после таймаута диска")
            fixture.translator.next().succeed("Next result")
            fixture.awaitState { it.translatedText == "Next result" && it.history.size == 3 }
            val persisted = storage.data.first()[stringPreferencesKey("translation_history_json")].orEmpty()
            assertTrue(persisted.contains("First result"))
            assertTrue(persisted.contains("Next result"))
        }
    }

    @Test
    fun `таймаут чтения истории не затирает существующие записи`() = runBlocking {
        val old = HistorySnapshot("Сохранено раньше", "Saved earlier", "ru", "en", "audit")
        val preferences = emptyPreferences().toMutablePreferences().apply {
            this[stringPreferencesKey("translation_history_json")] = Json.encodeToString(listOf(old))
        }
        val storage = HangingHistoryStore(initial = preferences, hangFirstRead = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val directory = Files.createTempDirectory("camwork-history-read-").toFile()
        try {
            val history = HistoryRepository(directory, RecoveryLogger, Json, scope, storage, 50)
            assertTrue(history.loadHistory().isEmpty())
            assertEquals(0, storage.writeAttempts.get())
            val next = HistorySnapshot("Новый", "New", "ru", "en", "audit")
            assertTrue(history.append(next, history.generation()))
            assertEquals(listOf(old.id, next.id), history.loadHistory().map { it.id })
            assertTrue(storage.data.first()[stringPreferencesKey("translation_history_json")].orEmpty().contains("Saved earlier"))
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `поздний ответ не восстанавливает очищенную историю на диске`() = runBlocking {
        withFixture { fixture ->
            fixture.translate("Текущий запрос")
            val pending = fixture.translator.next()
            fixture.store.dispatch(MainIntent.ClearHistory)
            fixture.awaitState { it.history.isEmpty() }
            pending.succeed("Current request")
            fixture.awaitState { it.translatedText == "Current request" }
            assertTrue(fixture.reloadHistoryFromDisk().isEmpty())
        }
    }

    @Test
    fun `главное окно и Shift не стирают записи друг друга`() = runBlocking {
        withFixture(consumeEvents = false) { fixture ->
            fixture.translate("Текст главного окна")
            val main = fixture.translator.next()
            fixture.store.dispatch(MainIntent.TranslateShiftSelection("Текст выделения", requestId = 7_000_000))
            val selection = fixture.translator.next()
            main.succeed("Main window text")
            fixture.awaitState { it.translatedText == "Main window text" }
            selection.succeed("Selection text")
            val paste = withTimeout(10_000) { fixture.store.events.filterIsInstance<MainEvent.PasteTranslation>().first() }
            assertEquals("Selection text", paste.translatedText)
            assertEquals(7_000_000L, paste.requestId)
            assertEquals(PasteFeedback.ERROR_ONLY, paste.feedback)
            fixture.awaitState { it.history.size == 3 }
            assertEquals(setOf("Старая запись", "Текст главного окна", "Текст выделения"),
                fixture.reloadHistoryFromDisk().map { it.inputText }.toSet())
        }
    }

    @Test
    fun `правило сохраняет фактическую цель без изменения пользовательского выбора`() = runBlocking {
        withFixture(rules = listOf(TranslationRule("en", "de"))) { fixture ->
            fixture.translate("Hello")
            fixture.translator.next().succeed("Привет", LanguageCode.ENGLISH)
            val retry = fixture.translator.next()
            assertEquals("de", retry.request.targetLanguage.tag)
            retry.succeed("Hallo", LanguageCode.ENGLISH)
            fixture.awaitState { it.translatedText == "Hallo" }
            assertEquals("ru", fixture.store.state.value.targetLanguage.tag)
            assertEquals("de", fixture.store.state.value.translatedTargetLanguage?.tag)
            assertEquals("de", fixture.history.loadHistory().last().targetLanguage)
        }
    }

    @Test
    fun `отмена дополнительного вывода сохраняет уже показанный основной перевод`() = runBlocking {
        withFixture(extra = ExtraOutputType.BackwardTranslate) { fixture ->
            fixture.store.dispatch(MainIntent.SelectSourceLanguage(LanguageCode.ENGLISH))
            fixture.translate("Primary text")
            fixture.translator.next().succeed("Основной перевод", LanguageCode.ENGLISH)
            fixture.translator.next()
            fixture.awaitState { it.isExtraOutputLoading }
            fixture.store.dispatch(MainIntent.CancelTranslation)
            fixture.awaitState { !it.isExtraOutputLoading }
            assertEquals("Основной перевод", fixture.reloadHistoryFromDisk().last().translatedText)
        }
    }

    @Test
    fun `завершение отмены старого запроса не гасит загрузку нового`() = runBlocking {
        withFixture { fixture ->
            fixture.translate("Первый запрос")
            val first = fixture.translator.next()
            val cleanup = CompletableDeferred<Unit>()
            first.cleanupGate = cleanup
            try {
                fixture.translate("Второй запрос")
                val second = fixture.translator.next()
                assertTrue(fixture.store.state.value.isLoading)
                cleanup.complete(Unit)
                withTimeout(5_000) { first.finished.await() }
                assertNull(withTimeoutOrNull(200) { fixture.store.state.first { !it.isLoading } })
                assertFalse(second.response.isCompleted)
                second.succeed("Second request")
                fixture.awaitState { it.translatedText == "Second request" && !it.isLoading }
            } finally { cleanup.complete(Unit) }
        }
    }

    @Test
    fun `проверка орфографии не публикует исправления старого текста`() = runBlocking {
        withFixture(spelling = true) { fixture ->
            fixture.store.dispatch(MainIntent.UpdateInputText("eror"))
            val first = fixture.spellChecker.next()
            fixture.store.dispatch(MainIntent.UpdateInputText("другой текст"))
            first.response.complete(Ok(SpellCheckResponse("error", listOf(Correction("eror", 0, 4, listOf("error"))))))
            first.finished.await()
            assertTrue(fixture.store.state.value.spellCheckCorrections.isEmpty())
            val second = fixture.spellChecker.next()
            second.response.complete(Ok(SpellCheckResponse("другой текст", emptyList())))
        }
    }

    @Test
    fun `исключение spell-check не убивает наблюдатель до перезапуска`() = runBlocking {
        withFixture(spelling = true) { fixture ->
            fixture.store.dispatch(MainIntent.UpdateInputText("первый текст"))
            fixture.spellChecker.next().response.completeExceptionally(IllegalStateException("Управляемый отказ"))
            fixture.store.dispatch(MainIntent.UpdateInputText("eror"))
            val next = fixture.spellChecker.next()
            assertEquals("eror", next.request.text)
            next.response.complete(Ok(SpellCheckResponse("error", listOf(Correction("eror", 0, 4, listOf("error"))))))
            fixture.awaitState { it.spellCheckCorrections.size == 1 }
        }
    }

    @Test
    fun `коррекция меняет выбранное повторение и отклоняет устаревший диапазон`() = runBlocking {
        withFixture { fixture ->
            fixture.store.dispatch(MainIntent.UpdateInputText("eror eror"))
            fixture.store.dispatch(MainIntent.ApplyCorrection("eror", "error", 5, 9, "eror eror"))
            assertEquals("eror error", fixture.store.state.value.inputText)
            fixture.store.dispatch(MainIntent.ApplyCorrection("eror", "error", 0, 4, "eror eror"))
            assertEquals("eror error", fixture.store.state.value.inputText)
            fixture.store.dispatch(MainIntent.ApplyCorrection("eror", "error", -1, 4, "eror error"))
            assertEquals("eror error", fixture.store.state.value.inputText)
        }
    }

    @Test
    fun `автоперевод продолжает работать после серии исключений и отмен провайдера`() = runBlocking {
        withFixture(instant = true) { fixture ->
            repeat(8) { index ->
                fixture.store.dispatch(MainIntent.UpdateInputText("Отказ $index"))
                val pending = fixture.translator.next()
                pending.response.completeExceptionally(if (index % 2 == 0) IllegalStateException("Управляемый отказ")
                    else CancellationException("Управляемая отмена провайдера"))
                fixture.awaitState { !it.isLoading }
            }
            fixture.store.dispatch(MainIntent.UpdateInputText("Работа после отказов"))
            fixture.translator.next().succeed("Recovered")
            fixture.awaitState { it.translatedText == "Recovered" }
        }
    }

    @Test
    fun `последний быстрый ввод не теряется при нагрузке`() = runBlocking {
        withFixture(instant = true) { fixture ->
            repeat(2_000) { fixture.store.dispatch(MainIntent.UpdateInputText("Редакция $it")) }
            val last = fixture.translator.next()
            assertEquals("Редакция 1999", last.request.text)
            last.succeed("Latest revision")
            fixture.awaitState { it.translatedText == "Latest revision" }
        }
    }

    @Test
    fun `обновление списка сервисов не меняет выбранные пользователем языки`() = runBlocking {
        withFixture { fixture ->
            fixture.store.dispatch(MainIntent.SelectSourceLanguage(LanguageCode.ENGLISH))
            fixture.store.dispatch(MainIntent.SelectTargetLanguage(LanguageCode.GERMAN))
            fixture.services.value = emptyMap()
            fixture.awaitState { it.availableServices.isEmpty() }
            fixture.translator.languages = SupportedLanguages.Specific(setOf(LanguageCode.ENGLISH, LanguageCode.RUSSIAN))
            fixture.services.value = mapOf("audit" to fixture.translator)
            fixture.awaitState { it.availableLanguages.size == 2 }
            assertEquals(LanguageCode.ENGLISH, fixture.store.state.value.sourceLanguage)
            assertEquals(LanguageCode.GERMAN, fixture.store.state.value.targetLanguage)
        }
    }

    @Test
    fun `ответ старого языка не перезаписывает результат после явной смены пары`() = runBlocking {
        withFixture { fixture ->
            fixture.translate("Исходный текст")
            val pending = fixture.translator.next()
            fixture.store.dispatch(MainIntent.SelectTargetLanguage(LanguageCode.GERMAN))
            pending.succeed("Устаревший результат")
            pending.finished.await()
            assertNotEquals("Устаревший результат", fixture.store.state.value.translatedText)
            fixture.store.dispatch(MainIntent.Translate())
            val current = fixture.translator.next()
            assertEquals(LanguageCode.GERMAN, current.request.targetLanguage)
            current.succeed("Aktuell")
            fixture.awaitState { it.translatedText == "Aktuell" }
        }
    }

    @Test
    fun `более 64 завершений LIVE не блокируют перевод без UI-потребителя`() = runBlocking {
        withFixture(consumeEvents = false) { fixture ->
            repeat(100) { index ->
                fixture.store.dispatch(MainIntent.TranslateLiveLensText("", index.toLong()))
            }
            fixture.translate("После нагрузки")
            fixture.translator.next().succeed("Still working")
            fixture.awaitState { it.translatedText == "Still working" }
            val terminals = withTimeout(10_000) {
                fixture.store.events.filterIsInstance<MainEvent.LiveLensTranslationFinished>().take(100).toList()
            }
            assertEquals((0L..99L).toSet(), terminals.map { it.requestId }.toSet())
        }
    }

    @Test
    fun `параллельные добавления и адресные дополнения сохраняют все записи`() = runBlocking {
        withFixture { fixture ->
            val generation = fixture.history.generation()
            val entries = List(150) { HistorySnapshot("Ввод $it", "Вывод $it", "ru", "en", "audit") }
            withContext(Dispatchers.Default) {
                entries.map { entry -> async { assertTrue(fixture.history.append(entry, generation)) } }.awaitAll()
                entries.map { entry -> async {
                    assertTrue(fixture.history.updateExtraOutput(entry.id, generation, "Дополнение ${entry.inputText}", "Rewrite"))
                } }.awaitAll()
            }
            val saved = fixture.history.loadHistory()
            assertEquals(151, saved.size)
            assertEquals(150, saved.count { it.extraOutputType == "Rewrite" })
            fixture.history.clearHistory()
            entries.forEach { assertFalse(fixture.history.append(it, generation)) }
            assertTrue(fixture.history.loadHistory().isEmpty())
        }
    }
}

private suspend fun withFixture(
    extra: ExtraOutputType = ExtraOutputType.None,
    rules: List<TranslationRule> = emptyList(),
    spelling: Boolean = false,
    instant: Boolean = false,
    consumeEvents: Boolean = true,
    storage: DataStore<Preferences>? = null,
    storageTimeoutMillis: Long = 1_000L,
    block: suspend (RecoveryFixture) -> Unit
) {
    val fixture = RecoveryFixture.create(extra, rules, spelling, instant, consumeEvents, storage, storageTimeoutMillis)
    try { withTimeout(40_000) { block(fixture) } } finally { fixture.close() }
}

private class RecoveryFixture(
    val directory: File,
    val scope: CoroutineScope,
    val client: HttpClient,
    val translator: RecoveryTranslator,
    val spellChecker: RecoverySpellChecker,
    val services: MutableStateFlow<Map<String, Service>>,
    val history: HistoryRepository,
    val store: MainStore
) {
    fun translate(text: String) {
        store.dispatch(MainIntent.UpdateInputText(text))
        store.dispatch(MainIntent.Translate())
    }
    suspend fun awaitState(predicate: (MainState) -> Boolean): MainState =
        withTimeout(10_000) { store.state.first(predicate) }
    suspend fun close() {
        scope.coroutineContext[Job]?.cancelAndJoin()
        client.close()
        directory.deleteRecursively()
    }
    suspend fun reloadHistoryFromDisk(): List<HistorySnapshot> {
        scope.coroutineContext[Job]?.cancelAndJoin()
        val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return try {
            HistoryRepository(directory, RecoveryLogger, Json { ignoreUnknownKeys = true }, readerScope).loadHistory()
        } finally { readerScope.coroutineContext[Job]?.cancelAndJoin() }
    }

    companion object {
        suspend fun create(extra: ExtraOutputType, rules: List<TranslationRule>, spelling: Boolean,
                           instant: Boolean, consumeEvents: Boolean, storage: DataStore<Preferences>?,
                           storageTimeoutMillis: Long): RecoveryFixture {
            val directory = Files.createTempDirectory("camwork-core-regression-").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val translator = RecoveryTranslator()
            val spellingService = RecoverySpellChecker()
            val services = MutableStateFlow<Map<String, Service>>(mapOf("audit" to translator, "spelling" to spellingService))
            val config = MutableStateFlow(Configuration(
                isInstantTranslationEnabled = instant, isSpellCheckingEnabled = spelling, autoCheckForUpdates = false,
                preferredSourceLanguage = "auto", preferredTargetLanguage = "ru", modelLanguage = "ru",
                shiftTapTranslationMode = ShiftTapTranslationMode.BIDIRECTIONAL, isShiftTapTranslateEnabled = true,
                servicePresets = listOf(ServicePreset("audit", "Проверка", mapOf(
                    ServiceRole.TRANSLATOR to "audit", ServiceRole.SPELL_CHECKER to "spelling"))),
                activeServicePresetId = "audit", extraOutputType = extra, translationRules = rules
            ))
            val manager = ActiveServiceManager(services, config)
            val history = HistoryRepository(directory, RecoveryLogger, Json { ignoreUnknownKeys = true }, scope,
                storage, storageTimeoutMillis)
            history.saveHistory(listOf(HistorySnapshot("Старая запись", "Old entry", "ru", "en", "audit")))
            val client = HttpClient(RecoveryDisabledEngine())
            val summary = SummarizeUseCase(manager, RecoveryLoggerFactory)
            val rewrite = RewriteUseCase(manager, RecoveryLoggerFactory)
            val translate = TranslateTextUseCase(scope, config, manager, history, summary, rewrite, RecoveryLoggerFactory)
            val store = MainStore(
                scope, config, history,
                CheckForUpdatesUseCase("1", config, Updater("audit", "audit", client, RecoveryLogger),
                    NotificationBus(), false, RecoveryLoggerFactory),
                HandleTextToSpeechUseCase(manager, config, RecoveryAudioPlayer, RecoveryLoggerFactory),
                PerformSpellCheckUseCase(manager, RecoveryLoggerFactory),
                SelectActiveServiceUseCase(services, config, manager, scope, RecoveryLoggerFactory),
                translate, SwapLanguagesUseCase(), OcrAndTranslateUseCase(manager, RecoveryLoggerFactory),
                TranslateImageUseCase(manager, RecoveryLoggerFactory), summary, rewrite,
                LookupWordUseCase(scope, manager, RecoveryLoggerFactory), SearchImagesUseCase(scope, manager, RecoveryLoggerFactory),
                FetchInlineDefinitionUseCase(scope, manager, RecoveryLoggerFactory), DocumentTranslationUseCase(manager, RecoveryLoggerFactory)
            )
            if (consumeEvents) scope.launch { store.events.collect { } }
            val fixture = RecoveryFixture(directory, scope, client, translator, spellingService, services, history, store)
            fixture.awaitState { it.history.size == 1 && it.availableLanguages.isNotEmpty() }
            return fixture
        }
    }
}

private class PendingRecoveryTranslation(val request: TranslationRequest) {
    val response = CompletableDeferred<Result<TranslationResponse, ServiceError>>()
    val finished = CompletableDeferred<Unit>()
    @Volatile var cleanupGate: CompletableDeferred<Unit>? = null
    fun succeed(text: String, detected: LanguageCode = LanguageCode.RUSSIAN) {
        response.complete(Ok(TranslationResponse(text, detected)))
    }
}

private class RecoveryTranslator : Translator {
    override val key = "audit-translator"
    override val name = "Проверочный переводчик"
    override val version = "1"
    @Volatile var languages: SupportedLanguages = SupportedLanguages.All
    override val supportedLanguages: SupportedLanguages get() = languages
    private val requests = Channel<PendingRecoveryTranslation>(Channel.UNLIMITED)
    override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> {
        val pending = PendingRecoveryTranslation(request)
        requests.send(pending)
        try { return pending.response.await() } finally {
            pending.cleanupGate?.let { gate -> withContext(NonCancellable) { gate.await() } }
            pending.finished.complete(Unit)
        }
    }
    suspend fun next(): PendingRecoveryTranslation = withTimeout(10_000) { requests.receive() }
}

private class PendingRecoverySpelling(val request: SpellCheckRequest) {
    val response = CompletableDeferred<Result<SpellCheckResponse, ServiceError>>()
    val finished = CompletableDeferred<Unit>()
}

private class RecoverySpellChecker : SpellChecker {
    override val key = "audit-spelling"
    override val name = "Проверочная орфография"
    override val version = "1"
    override val supportedLanguages = SupportedLanguages.All
    private val requests = Channel<PendingRecoverySpelling>(Channel.UNLIMITED)
    override suspend fun check(request: SpellCheckRequest): Result<SpellCheckResponse, ServiceError> {
        val pending = PendingRecoverySpelling(request)
        requests.send(pending)
        try { return pending.response.await() } finally { pending.finished.complete(Unit) }
    }
    suspend fun next(): PendingRecoverySpelling = withTimeout(10_000) { requests.receive() }
}

private object RecoveryLogger : Logger {
    override fun debug(message: String) = Unit
    override fun info(message: String) = Unit
    override fun warn(message: String) = Unit
    override fun error(message: String, error: Throwable?) = Unit
}
private object RecoveryLoggerFactory : LoggerFactory {
    override fun getLogger(name: String): Logger = RecoveryLogger
}
private object RecoveryAudioPlayer : AudioPlayer {
    override val isPlaying: StateFlow<Boolean> = MutableStateFlow(false)
    override fun play(audio: TTSAudio.Bytes) = Unit
    override fun stop() = Unit
    override fun close() = Unit
}
@OptIn(io.ktor.utils.io.InternalAPI::class)
private class RecoveryDisabledEngine : HttpClientEngineBase("проверка-без-сети") {
    override val config = HttpClientEngineConfig()
    override suspend fun execute(data: HttpRequestData): HttpResponseData = error("Сеть в этой проверке запрещена")
}

private class HangingHistoryStore(
    initial: Preferences = emptyPreferences(),
    private val hangFirstRead: Boolean = false,
    private val hangWriteNumber: Int = 0
) : DataStore<Preferences> {
    private val preferences = MutableStateFlow(initial)
    private val readAttempts = AtomicInteger(0)
    val writeAttempts = AtomicInteger(0)
    private val mutex = Mutex()
    val hung = CompletableDeferred<Unit>()
    val released = CompletableDeferred<Unit>()
    override val data: Flow<Preferences> = flow {
        if (hangFirstRead && readAttempts.incrementAndGet() == 1) awaitCancellation()
        emit(preferences.value)
    }
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
        if (writeAttempts.incrementAndGet() == hangWriteNumber) {
            hung.complete(Unit)
            try { awaitCancellation() } finally { released.complete(Unit) }
        }
        transform(preferences.value).also { preferences.value = it }
    }
}
