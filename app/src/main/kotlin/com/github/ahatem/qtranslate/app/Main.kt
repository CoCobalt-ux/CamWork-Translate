package com.github.ahatem.qtranslate.app

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.shared.notification.AppNotification
import com.github.ahatem.qtranslate.core.shared.notification.NotificationCode
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.ui.swing.main.MainAppFrame
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconSet
import com.github.michaelbull.result.fold
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.File
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

fun main() = runBlocking {

    if (System.getProperty("camwork.packagedSmokeTest").toBoolean()) {
        runPackagedSmokeTest()
        return@runBlocking
    }

    var frame: MainAppFrame? = null
    var frameReady = false
    val pendingFocus = AtomicBoolean(false)
    fun showExistingWindow() {
        if (!frameReady) return
        val current = frame ?: return
        if (!pendingFocus.getAndSet(false)) return
        current.extendedState = current.extendedState and Frame.ICONIFIED.inv()
        current.isVisible = true
        current.toFront()
        current.requestFocus()
    }

    val instanceGuard = SingleInstanceGuard()
    when (val result = instanceGuard.acquire {
        pendingFocus.set(true)
        SwingUtilities.invokeLater { showExistingWindow() }
    }) {
        is InstanceStartResult.Acquired -> Unit
        is InstanceStartResult.AlreadyRunning -> {
            val focusStatus = if (result.focusRequested) "Отправлен запрос показа окна." else "Повторный запуск отменён."
            System.err.println("${AppConstants.APP_NAME}: приложение уже запущено. $focusStatus")
            return@runBlocking
        }
        is InstanceStartResult.Failed -> {
            val message = "Не удалось получить блокировку экземпляра приложения. " +
                "Запуск отменён для защиты настроек. Причина: ${result.error.javaClass.simpleName}."
            System.err.println("${AppConstants.APP_NAME}: $message")
            if (!GraphicsEnvironment.isHeadless()) runCatching {
                SwingUtilities.invokeAndWait {
                    JOptionPane.showMessageDialog(null, message, AppConstants.APP_NAME, JOptionPane.ERROR_MESSAGE)
                }
            }
            return@runBlocking
        }
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        instanceGuard.close()
    })

    AppUiSetup.setSystemProperties()
    AppUiSetup.setRenderingHints()

    val appData = AppDataDirectory.resolve()

    // Before the first logger is asked for. Logback reads its configuration lazily, on the first
    // SLF4J call, and `logback.xml` resolves the log directory from this property with a fallback
    // of a relative `logs/`. Setting it late means the fallback wins and the log lands in whatever
    // the working directory happens to be, which for a double-clicked JAR is anyone's guess.
    System.setProperty("logDir", File(appData, "logs").absolutePath)

    val logFactory = LogbackLoggerFactory()
    val logger     = logFactory.getLogger("Main")

    logger.info("${AppConstants.APP_NAME} ${AppConstants.APP_VERSION} starting...")
    logger.info("App data directory: ${appData.absolutePath}")

    val json         = Json { ignoreUnknownKeys = true; isLenient = true }
    val settingsRepo = SettingsRepository(appData, json, logFactory.getLogger("SettingsRepository"))

    var configLoadTimedOut = false
    val initialConfig = withTimeoutOrNull(AppConstants.CONFIG_LOAD_TIMEOUT_MS) {
        settingsRepo.loadInitialConfiguration()
    } ?: run {
        logger.warn("Configuration load timed out after ${AppConstants.CONFIG_LOAD_TIMEOUT_MS}ms — using defaults")
        configLoadTimedOut = true
        Configuration.DEFAULT
    }

    logger.info("Configuration loaded: theme=${initialConfig.themeId}, scale=${initialConfig.uiScale}")

    val deps = buildDependencies(
        appData       = appData,
        loggerFactory = logFactory,
        settingsRepo  = settingsRepo,
        initialConfig = initialConfig,
        performStartupReconciliation = !configLoadTimedOut && settingsRepo.lastRecovery == null
    )
    AppUiSetup.apply(initialConfig, deps.themeManager)

    // Уведомления отправляются после готовности окна: ранний replay мог истечь за время запуска.
    val startupNotifications = buildList<AppNotification> {
        settingsRepo.lastRecovery?.let { recovery ->
            logger.error("Configuration recovery: ${recovery.reason}")
            add(AppNotification(
                    type = NotificationType.ERROR,
                    code = NotificationCode.Custom(
                        title = "Settings could not be loaded",
                        body = recovery.reason
                    )
                ))
        }
        if (configLoadTimedOut) {
            add(AppNotification(
                    type = NotificationType.WARNING,
                    code = NotificationCode.Custom(
                        title = "Settings took too long to load",
                        body = "${AppConstants.APP_NAME} started with default settings so it would not hang. " +
                            "Your saved settings are still on disk — restarting may load them."
                    )
                ))
        }
        deps.startupWarning?.let { warning ->
            logger.warn("Автозапуск Windows: $warning")
            add(AppNotification(
                type = NotificationType.WARNING,
                code = NotificationCode.Custom(
                    title = "Автозапуск Windows",
                    body = warning
                )
            ))
        }
    }

    // Only when the user has not chosen. Keying this off "en" meant an explicit choice of English
    // was read as no choice at all, so detection ran again on every launch and put the interface
    // back into the machine's language.
    val savedLanguage = if (initialConfig.interfaceLanguage.isBlank()) {
        OsLanguageDetector.detect(deps.localizationManager.availableLanguages)
    } else {
        LanguageCode(initialConfig.interfaceLanguage)
    }
    runCatching {
        deps.localizationManager.loadLanguage(savedLanguage)
        // The resolved tag, not the stored one, which is blank until the user chooses.
        logger.info("Interface language loaded: ${savedLanguage.tag}")
    }.onFailure { e ->
        logger.warn("Failed to load interface language '${savedLanguage.tag}': ${e.message}")
    }

    // The window is shown before plugins are loaded. Everything it needs to paint — theme,
    // scale and interface language — is already resolved, and services reach the UI through
    // SelectActiveServiceUseCase.observe(), so the selector and Plugins panel fill in on
    // their own as plugins finish initialising. Waiting here instead would leave the screen
    // empty for as long as the slowest plugin takes, and several do network work at startup.
    SwingUtilities.invokeLater {
        // Chosen before anything is drawn: icons are built once and held by the components
        // showing them, so this has to be set while there is still nothing on screen.
        // The folder the extra sets live in, beside languages and themes.
        IconSet.installTo(deps.appDataDirectory)
        IconSet.use(deps.settingsStore.state.value.workingConfiguration.iconSetId)

        frame = MainAppFrame(
            mainStore        = deps.mainStore,
            settingsStore    = deps.settingsStore,
            iconManager      = deps.iconManager,
            themeManager     = deps.themeManager,
            localizer        = deps.localizationManager,
            pluginManager    = deps.pluginManager,
            notificationBus  = deps.notificationBus,
            logger           = logFactory.getLogger("MainAppFrame"),
            appSecrets       = deps.appSecrets,
            translateString  = { text, target ->
                deps.translateStringUseCase(text, target).fold(
                    success = { Result.success(it) },
                    failure = { Result.failure(IllegalStateException(it.message)) }
                )
            }
        )
        logger.info("Main window launched")
        // Конструктор отдельно ставит сборку и pack() окна в очередь EDT.
        SwingUtilities.invokeLater {
            frameReady = true
            showExistingWindow()
            if (startupNotifications.isNotEmpty()) deps.appScope.launch {
                startupNotifications.forEach {
                    deps.notificationBus.post(it.copy(timestamp = System.currentTimeMillis()))
                }
            }
        }
    }

    logger.info("Loading plugins...")
    deps.appScope.launch {
        runCatching { deps.pluginManager.loadAndProcessPlugins() }
            .onSuccess { logger.info("Plugins loaded successfully") }
            .onFailure { e -> logger.error("Failed to load plugins", e) }
    }

    Thread.currentThread().join()
}
