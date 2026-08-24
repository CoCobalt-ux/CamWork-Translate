package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Frame
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.Timer

/**
 * Просит выдать «Универсальный доступ» и сам замечает, когда это сделано.
 *
 * Пользователь не может выдать разрешение, не уходя из приложения: переключатель живёт в
 * системных настройках. Поэтому окно открывает нужную страницу напрямую и опрашивает состояние,
 * пока открыто, — иначе оставалось бы гадать, подействовало ли, и перезапускать приложение
 * наугад.
 */
internal class MacAccessibilityPermissionDialog(
    owner: Frame?,
    private val localizer: LocalizationManager
) : JDialog(owner, false) {

    private val statusLabel = JLabel(" ")

    private val recheckTimer = Timer(RECHECK_INTERVAL_MS) {
        if (MacAccessibilityPermission.isGranted()) {
            onPermissionGranted()
        }
    }

    init {
        title = localizer.getString("macos_permission.title")
        defaultCloseOperation = DISPOSE_ON_CLOSE

        val explanation = JTextArea(localizer.getString("macos_permission.body")).apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            // Иначе JTextArea требует ширину самой длинной строки и растягивает окно.
            preferredSize = Dimension(TEXT_WIDTH, TEXT_HEIGHT)
        }

        val openSettings = JButton(localizer.getString("macos_permission.open_settings")).apply {
            addActionListener {
                MacAccessibilityPermission.openSettings()
                statusLabel.text = localizer.getString("macos_permission.waiting")
            }
        }
        val later = JButton(localizer.getString("macos_permission.later")).apply {
            addActionListener { dispose() }
        }

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            add(later)
            add(openSettings)
        }

        contentPane.layout = BorderLayout(0, GAP)
        (contentPane as JPanel).border = BorderFactory.createEmptyBorder(GAP, GAP, GAP, GAP)
        contentPane.add(explanation, BorderLayout.CENTER)
        contentPane.add(statusLabel, BorderLayout.SOUTH)
        contentPane.add(buttons, BorderLayout.PAGE_END)

        pack()
        setLocationRelativeTo(owner)
        rootPane.defaultButton = openSettings
        recheckTimer.start()
    }

    override fun dispose() {
        recheckTimer.stop()
        super.dispose()
    }

    private fun onPermissionGranted() {
        recheckTimer.stop()
        statusLabel.text = localizer.getString("macos_permission.granted")
        // Короткая пауза, чтобы подтверждение успели прочитать: окно, исчезающее в тот же миг,
        // выглядит как сбой, а не как успех.
        Timer(CONFIRMATION_DELAY_MS) { dispose() }.apply { isRepeats = false }.start()
    }

    internal companion object {
        private const val RECHECK_INTERVAL_MS = 1_500
        private const val CONFIRMATION_DELAY_MS = 1_800
        private const val TEXT_WIDTH = 420
        private const val TEXT_HEIGHT = 110
        private const val GAP = 12

        /**
         * Показывает окно, только если разрешения действительно нет. На других платформах и при
         * уже выданном разрешении не делает ничего.
         */
        fun showIfNeeded(owner: Frame?, localizer: LocalizationManager) {
            if (!MacAccessibilityPermission.isMacOs) return
            if (MacAccessibilityPermission.isGranted()) return
            MacAccessibilityPermissionDialog(owner, localizer).isVisible = true
        }
    }
}
