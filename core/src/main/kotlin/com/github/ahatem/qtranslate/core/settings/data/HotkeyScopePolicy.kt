package com.github.ahatem.qtranslate.core.settings.data

/** Действия, для которых область действия определяется самим сценарием приложения. */
val HotkeyAction.requiredScope: HotkeyScope?
    get() = when (this) {
        HotkeyAction.SHOW_MAIN_WINDOW,
        HotkeyAction.REPLACE_WITH_TRANSLATION -> HotkeyScope.GLOBAL

        HotkeyAction.FOCUS_INPUT,
        HotkeyAction.FOCUS_OUTPUT,
        HotkeyAction.FOCUS_EXTRA_OUTPUT,
        HotkeyAction.COPY_TRANSLATION,
        HotkeyAction.CLEAR_INPUT,
        HotkeyAction.SWAP_LANGUAGES,
        HotkeyAction.OPEN_SETTINGS,
        HotkeyAction.SHOW_HISTORY,
        HotkeyAction.TRANSLATE_DOCUMENT -> HotkeyScope.LOCAL

        else -> null
    }

fun HotkeyBinding.normalizeScope(): HotkeyBinding =
    action.requiredScope?.takeIf { it != scope }?.let { copy(scope = it) } ?: this

fun Configuration.normalizeHotkeyScopes(): Configuration {
    val normalized = hotkeys.map(HotkeyBinding::normalizeScope)
    return if (normalized == hotkeys) this else copy(hotkeys = normalized)
}
