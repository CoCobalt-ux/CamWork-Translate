package com.github.ahatem.qtranslate.ui.swing.main

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconSet
import java.awt.Dimension
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.SwingConstants

/** Официальные ресурсы CamWork, полученные с camwork.club без перекрашивания. */
internal object CamWorkBrand {
    const val HORIZONTAL_RESOURCE = IconSet.CAMWORK_HORIZONTAL
    const val HORIZONTAL_WIDTH = 152
    const val HORIZONTAL_HEIGHT = 28
    const val LEFT_INSET = 12
    const val RIGHT_INSET = 6
    const val TOP_INSET = 8
    const val BOTTOM_INSET = 6

    fun insets(scale: (Int) -> Int = UIScale::scale): Insets = Insets(
        scale(TOP_INSET),
        scale(LEFT_INSET),
        scale(BOTTOM_INSET),
        scale(RIGHT_INSET)
    )
}

/**
 * Постоянный продуктовый lockup в шапке, созданный на базе официального знака CamWork.
 * PNG содержит прозрачный фон, фирменные цвета и встроенную подпись `TRANSLATE`, поэтому
 * компонент не добавляет отдельный системный заголовок, цветную плашку или перекрашивание.
 * Размер фиксируется после загрузки, чтобы `BoxLayout` не сжимал его до нуля при пересчёте меню.
 */
internal class CamWorkBrandLabel(brandIcon: Icon) : JLabel("", brandIcon, SwingConstants.LEADING) {

    constructor(iconManager: IconManager) : this(
        iconManager.getIcon(
            CamWorkBrand.HORIZONTAL_RESOURCE,
            CamWorkBrand.HORIZONTAL_WIDTH,
            CamWorkBrand.HORIZONTAL_HEIGHT
        )
    )

    init {
        isOpaque = false
        toolTipText = AppConstants.APP_NAME
        border = CamWorkBrand.insets().let { insets ->
            BorderFactory.createEmptyBorder(insets.top, insets.left, insets.bottom, insets.right)
        }

        val stableSize = Dimension(preferredSize)
        minimumSize = stableSize
        preferredSize = stableSize
        maximumSize = stableSize
    }
}
