package com.github.ahatem.qtranslate.ui.swing.main.selector

import com.github.ahatem.qtranslate.ui.swing.shared.util.clearBorder
import com.formdev.flatlaf.FlatClientProperties
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.core.settings.data.ServiceSelectorAppearance
import com.github.ahatem.qtranslate.core.settings.data.ServiceSelectorStyle
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import com.github.ahatem.qtranslate.ui.swing.main.layout.ResponsiveUi
import com.formdev.flatlaf.util.UIScale
import net.miginfocom.swing.MigLayout
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons

/** Роли, которые имеют постоянное место в основном окне. */
internal val MAIN_SELECTOR_ROLES: Set<ServiceRole> = setOf(ServiceRole.TRANSLATOR)

class TranslatorSelector(
    private val iconManager: IconManager,
    private val onServiceSelected: (ServiceRole, String) -> Unit,
    private val onConfigureService: (String) -> Unit = {}
) : JPanel(CardLayout()), Renderable<TranslatorSelectorState> {
    private companion object {
        const val CLASSIC = "classic"
        const val ENHANCED = "enhanced"
        const val ICON_SIZE = 16
        const val MAX_CLASSIC_TEXT_WIDTH = 160
    }

    private var state = TranslatorSelectorState(emptyList(), null, false)
    private val classicButtons = JPanel(FlowLayout(FlowLayout.LEADING, 2, 0)).apply { isOpaque = false }
    private val classicScroll = JScrollPane(classicButtons).apply {
        clearBorder(); isOpaque = false; viewport.isOpaque = false
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        horizontalScrollBar.unitIncrement = 24
        mouseWheelListeners.forEach(::removeMouseWheelListener)
        addMouseWheelListener { e -> scrollClassic(e.wheelRotation * horizontalScrollBar.unitIncrement) }
    }
    private val scrollBack = createScrollButton(Icons.NAV_BACK, -96, "Previous services")
    private val scrollForward = createScrollButton(Icons.NAV_FORWARD, 96, "More services")
    private val configureActive = JButton(iconManager.getIcon(Icons.SETTINGS, 16, 16)).apply {
        putClientProperty(FlatClientProperties.BUTTON_TYPE, "toolBarButton")
        toolTipText = "Configure active translation service"; isFocusable = false
        addActionListener { state.selectedTranslatorId?.let(onConfigureService) }
    }
    private val classicControls = JPanel(FlowLayout(FlowLayout.TRAILING, 0, 0)).apply {
        isOpaque = false; add(scrollForward); add(configureActive)
    }
    private val classic = JPanel(BorderLayout(2, 0)).apply {
        isOpaque = false; add(scrollBack, BorderLayout.LINE_START); add(classicScroll); add(classicControls, BorderLayout.LINE_END)
    }
    private var isRenderingEnhanced = false
    private var enhancedServices: List<ServiceInfo> = emptyList()
    private val enhancedCombo = JComboBox<ServiceInfo>().apply {
        renderer = ServiceRenderer()
        minimumSize = Dimension(0, preferredSize.height)
        addActionListener {
            if (!isRenderingEnhanced) {
                (selectedItem as? ServiceInfo)?.let { service ->
                    toolTipText = service.name
                    onServiceSelected(MAIN_SELECTOR_ROLES.single(), service.id)
                }
            }
        }
    }
    private val configureEnhanced = JButton(iconManager.getIcon(Icons.SETTINGS, 16, 16)).apply {
        putClientProperty(FlatClientProperties.BUTTON_TYPE, "toolBarButton")
        toolTipText = "Configure active translation service"
        isFocusable = false
        addActionListener { (enhancedCombo.selectedItem as? ServiceInfo)?.id?.let(onConfigureService) }
    }
    private val enhanced = JPanel(
        MigLayout("insets 0, fillx, hidemode 3", "[grow,fill][]", "[]")
    ).apply {
        isOpaque = false
        add(enhancedCombo, "growx, pushx, wmin 0")
        add(configureEnhanced)
    }

    init {
        isOpaque = false; add(classic, CLASSIC); add(enhanced, ENHANCED)
        classicScroll.viewport.addChangeListener { updateClassicOverflowControls() }
    }

    override fun render(state: TranslatorSelectorState) {
        this.state = state
        if (state.style == ServiceSelectorStyle.CLASSIC) rebuildClassic() else renderEnhanced()
        (layout as CardLayout).show(this, if (state.style == ServiceSelectorStyle.CLASSIC) CLASSIC else ENHANCED)
        revalidate(); repaint()
    }

    private fun rebuildClassic() {
        classicButtons.removeAll()
        val group = ButtonGroup()
        var selectedButton: JToggleButton? = null
        state.availableTranslators.forEach { service ->
            val serviceIcon = loadIcon(service)
            val button = JToggleButton().apply {
                icon = if (state.appearance == ServiceSelectorAppearance.TEXT_ONLY) null else serviceIcon
                val fullName = service.name
                val shortName = ResponsiveUi.elideText(
                    fullName,
                    UIScale.scale(MAX_CLASSIC_TEXT_WIDTH),
                    getFontMetrics(font)::stringWidth
                )
                text = when (state.appearance) {
                    ServiceSelectorAppearance.ICONS_ONLY -> shortName.takeIf { serviceIcon == null }
                    else -> shortName
                }
                toolTipText = fullName; isSelected = service.id == state.selectedTranslatorId
                isEnabled = !state.isLoading; isFocusable = false; isOpaque = false
                putClientProperty(FlatClientProperties.BUTTON_TYPE, "toolBarButton")
                margin = Insets(4, 6, 4, 6)
                addActionListener { onServiceSelected(MAIN_SELECTOR_ROLES.single(), service.id) }
                addMouseListener(object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent) { if (SwingUtilities.isRightMouseButton(e)) onConfigureService(service.id) }
                })
            }
            if (button.isSelected) selectedButton = button
            group.add(button); classicButtons.add(button)
        }
        configureActive.isEnabled = !state.isLoading && state.selectedTranslatorId != null
        classicButtons.revalidate()
        SwingUtilities.invokeLater {
            selectedButton?.let { it.scrollRectToVisible(it.bounds) }
            updateClassicOverflowControls()
        }
    }

    private fun renderEnhanced() {
        isRenderingEnhanced = true
        if (enhancedServices != state.availableTranslators) {
            enhancedCombo.model = DefaultComboBoxModel(state.availableTranslators.toTypedArray())
            enhancedServices = state.availableTranslators
        }
        val selected = state.availableTranslators.find { it.id == state.selectedTranslatorId }
        if (selected != null && enhancedCombo.selectedItem != selected) enhancedCombo.selectedItem = selected
        enhancedCombo.isEnabled = !state.isLoading && state.availableTranslators.isNotEmpty()
        enhancedCombo.toolTipText = (enhancedCombo.selectedItem as? ServiceInfo)?.name
        configureEnhanced.isEnabled = !state.isLoading && enhancedCombo.selectedItem != null
        isRenderingEnhanced = false
    }

    private fun createScrollButton(iconPath: String, amount: Int, tooltip: String) =
        JButton(iconManager.getIcon(iconPath, 16, 16)).apply {
            putClientProperty(FlatClientProperties.BUTTON_TYPE, "toolBarButton")
            toolTipText = tooltip; isFocusable = false
            addActionListener { scrollClassic(amount) }
        }

    private fun scrollClassic(amount: Int) {
        val bar = classicScroll.horizontalScrollBar
        bar.value = (bar.value + amount).coerceIn(bar.minimum, bar.maximum - bar.visibleAmount)
        updateClassicOverflowControls()
    }

    private fun updateClassicOverflowControls() {
        val bar = classicScroll.horizontalScrollBar
        val overflowing = classicButtons.preferredSize.width > classicScroll.viewport.extentSize.width
        val visibilityChanged = scrollBack.isVisible != overflowing || scrollForward.isVisible != overflowing
        scrollBack.isVisible = overflowing
        scrollForward.isVisible = overflowing
        scrollBack.isEnabled = overflowing && bar.value > bar.minimum
        scrollForward.isEnabled = overflowing && bar.value + bar.visibleAmount < bar.maximum
        if (visibilityChanged) classic.revalidate()
    }

    private fun loadIcon(service: ServiceInfo): Icon? = service.iconPath?.let { iconManager.getIcon(service.id, it, ICON_SIZE, ICON_SIZE) }

    private inner class ServiceRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component =
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).apply {
                val service = value as? ServiceInfo
                val fullName = service?.name.orEmpty()
                text = if (index == -1 && enhancedCombo.width > 0) {
                    val availableWidth = (enhancedCombo.width - 52).coerceAtLeast(0)
                    ResponsiveUi.elideText(fullName, availableWidth, getFontMetrics(font)::stringWidth)
                } else {
                    fullName
                }
                icon = service?.let(::loadIcon)
                toolTipText = fullName.takeIf { it.isNotBlank() }
            }
    }
}
