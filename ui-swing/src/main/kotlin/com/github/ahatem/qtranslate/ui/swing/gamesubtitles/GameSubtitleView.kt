package com.github.ahatem.qtranslate.ui.swing.gamesubtitles

import java.awt.Rectangle

/** Отделяет жизненный цикл LIVE-перевода от окон и позволяет воспроизводить сбои без UI. */
internal interface GameSubtitleView {
    fun showAt(captureBounds: Rectangle)
    fun setMode(mode: GameSubtitleWindowMode)
    fun showTranslation(text: String, replaceLatest: Boolean)
    fun resetTranslations()
    fun <T> captureWithoutOverlay(action: () -> T): T
    fun dispose()
}
