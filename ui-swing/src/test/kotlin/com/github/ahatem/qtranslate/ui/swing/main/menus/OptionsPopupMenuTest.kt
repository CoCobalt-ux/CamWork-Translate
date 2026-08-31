package com.github.ahatem.qtranslate.ui.swing.main.menus

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import javax.swing.JCheckBoxMenuItem
import javax.swing.JMenuItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OptionsPopupMenuTest {

    @Test
    fun `быстрый пункт отражает и изменяет перевод выделенного текста`() {
        var toggled: Boolean? = null
        val menu = createMenu(
            config = Configuration.DEFAULT.copy(
                isAutoSelectionTranslateEnabled = true,
                isInstantTranslationEnabled = false
            ),
            onSelectionTranslation = { toggled = it }
        )
        val item = selectionItem(menu)

        assertTrue(item.isSelected)
        item.doClick()
        assertEquals(false, toggled)
    }

    @Test
    fun `legacy перевод при вводе не влияет на быстрый пункт`() {
        val menu = createMenu(
            config = Configuration.DEFAULT.copy(
                isAutoSelectionTranslateEnabled = false,
                isInstantTranslationEnabled = true
            )
        )

        assertFalse(selectionItem(menu).isSelected)
    }

    @Test
    fun `пункт OCR присутствует в главном меню и запускает выделение области`() {
        var clicked = false
        val menu = createMenu(
            config = Configuration.DEFAULT,
            onRecognizeText = { clicked = true }
        )

        menu.components
            .filterIsInstance<JMenuItem>()
            .first { it.text == OCR_LABEL }
            .doClick()

        assertTrue(clicked)
    }

    private fun selectionItem(menu: MainMenuPopup): JCheckBoxMenuItem =
        menu.components
            .filterIsInstance<JCheckBoxMenuItem>()
            .first { it.text == SELECTION_LABEL }

    private fun createMenu(
        config: Configuration,
        onSelectionTranslation: (Boolean) -> Unit = {},
        onRecognizeText: () -> Unit = {}
    ): MainMenuPopup = MainMenuPopup(
        config = config,
        actions = MenuActions(
            onToggleSpellCheck = {},
            onToggleSelectionTranslation = onSelectionTranslation,
            onToggleExtraOutput = {},
            onShowDictionary = {},
            onShowImageSearch = {},
            onShowHistory = {},
            onRecognizeText = onRecognizeText,
            onShowLiveLens = {},
            onTranslateDocument = {},
            onShowSettings = {},
            onShowHowToUse = {},
            onShowAboutQTranslate = {},
            onContactUs = {},
            onToggleAutoCheckForUpdates = {},
            onCheckForUpdates = {},
            onExitApplication = {},
            onChangeLayoutPreset = {},
            onToggleHistoryControls = {},
            onToggleLanguageBar = {},
            onToggleServicesPanel = {},
            onToggleStatusBar = {}
        ),
        strings = MenuStrings(
            spellCheck = "spell",
            selectionTranslation = SELECTION_LABEL,
            extraOutput = "extra",
            viewOptions = "view",
            dictionary = "dictionary",
            isDictionaryPanelOpen = false,
            imageSearch = "images",
            history = "history",
            textRecognition = OCR_LABEL,
            liveLens = "live",
            translateDocument = "document",
            settings = "settings",
            help = "help",
            howToUse = "how",
            aboutQTranslate = "about",
            contactUs = "contact",
            autoCheckForUpdates = "auto update",
            checkForUpdates = "updates",
            exit = "exit",
            layoutPresets = "layouts",
            showHistoryControls = "history controls",
            showLanguageBar = "languages",
            showServicesPanel = "services",
            showStatusBar = "status"
        ),
        availableLayouts = emptyList()
    )

    private companion object {
        const val SELECTION_LABEL = "Перевод выделенного текста"
        const val OCR_LABEL = "OCR с экрана"
    }
}
