package com.github.ahatem.qtranslate.ui.swing.main.layout

import com.formdev.flatlaf.util.UIScale

/**
 * Общие правила компактного режима главного окна.
 *
 * Они вынесены из Swing-компонентов, чтобы все панели переходили в компактный режим
 * на одной ширине и чтобы правило можно было проверить без запуска окна.
 */
object ResponsiveUi {
    private const val COMPACT_TOOLBAR_WIDTH = 560

    fun shouldUseCompactToolbar(width: Int): Boolean =
        width in 1 until UIScale.scale(COMPACT_TOOLBAR_WIDTH)

    /**
     * Сокращает строку по реальной ширине шрифта и сохраняет целые Unicode-символы.
     */
    fun elideText(text: String, maxWidth: Int, measure: (String) -> Int): String {
        if (text.isEmpty()) return text
        if (maxWidth <= 0) return ""
        if (measure(text) <= maxWidth) return text

        val ellipsis = "…"
        if (measure(ellipsis) > maxWidth) return ""

        val codePoints = text.codePoints().toArray()
        var low = 0
        var high = codePoints.size
        while (low < high) {
            val middle = (low + high + 1) / 2
            val candidate = String(codePoints, 0, middle).trimEnd() + ellipsis
            if (measure(candidate) <= maxWidth) low = middle else high = middle - 1
        }
        return String(codePoints, 0, low).trimEnd() + ellipsis
    }
}
