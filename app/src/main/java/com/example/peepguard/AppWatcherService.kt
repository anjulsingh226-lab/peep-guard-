package com.example.peepguard

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/** Detect karta hai ki abhi kaunsa app screen pe hai. */
class AppWatcherService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return

        // Apna app, system UI aur keyboard ignore karo
        if (pkg == packageName || pkg == "com.android.systemui" ||
            pkg.contains("inputmethod") || pkg.contains("keyboard")
        ) return

        val protectedApps = getSharedPreferences("guard", MODE_PRIVATE)
            .getStringSet("apps", emptySet()) ?: emptySet()

        Guard.setActive(pkg in protectedApps)
    }

    override fun onInterrupt() {}
}
