package com.duckets.hermesbuddy

import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide foreground tracker. Every activity calls [entered] in onStart
 * and [exited] in onStop so the service knows whether a reply/prompt needs
 * a notification (background) or is already visible (foreground).
 */
object AppVisibility {
    private val counter = AtomicInteger(0)

    val isForeground: Boolean get() = counter.get() > 0

    fun entered() {
        counter.incrementAndGet()
    }

    fun exited() {
        if (counter.decrementAndGet() < 0) counter.set(0)
    }
}
