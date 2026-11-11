package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import java.awt.Dimension
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle

/** Рабочая область монитора в координатах виртуального рабочего стола без системных панелей. */
internal fun usableBounds(screen: Rectangle, insets: Insets): Rectangle = Rectangle(
    screen.x + insets.left,
    screen.y + insets.top,
    (screen.width - insets.left - insets.right).coerceAtLeast(0),
    (screen.height - insets.top - insets.bottom).coerceAtLeast(0),
)

/** Границы всех рабочих областей мониторов с сохранением отрицательных координат. */
internal fun virtualDesktopBounds(screens: List<Rectangle>): Rectangle? =
    screens.reduceOrNull { desktop, screen -> desktop.union(screen) }

/** Ограничивает окно рабочими областями мониторов и выводит его из промежутков между ними. */
internal fun constrainToScreens(
    x: Int,
    y: Int,
    componentSize: Dimension,
    screens: List<Rectangle>,
    edgeInsets: Insets,
): Point {
    val desktop = virtualDesktopBounds(screens) ?: return Point(x, y)
    val candidate = clampWindowTo(x, y, componentSize, desktop, edgeInsets)
    val window = Rectangle(candidate, componentSize)

    if (screens.any { screen -> !screen.intersection(window).isEmpty }) return candidate

    val nearest = screens.minByOrNull { screen -> squaredDistance(window, screen) } ?: return candidate
    return clampWindowTo(candidate.x, candidate.y, componentSize, nearest, edgeInsets)
}

private fun clampWindowTo(
    x: Int,
    y: Int,
    componentSize: Dimension,
    bounds: Rectangle,
    edgeInsets: Insets,
): Point {
    val minX = bounds.x + edgeInsets.left
    val minY = bounds.y + edgeInsets.top
    val maxX = (bounds.x + bounds.width - componentSize.width - edgeInsets.right)
        .coerceAtLeast(minX)
    val maxY = (bounds.y + bounds.height - componentSize.height - edgeInsets.bottom)
        .coerceAtLeast(minY)

    return Point(
        x.coerceIn(minX, maxX),
        y.coerceIn(minY, maxY),
    )
}

private fun squaredDistance(window: Rectangle, screen: Rectangle): Long {
    val dx = maxOf(
        0,
        screen.x - window.x - window.width,
        window.x - screen.x - screen.width,
    ).toLong()
    val dy = maxOf(
        0,
        screen.y - window.y - window.height,
        window.y - screen.y - screen.height,
    ).toLong()
    return dx * dx + dy * dy
}
