package com.example.peepguard

import android.os.Handler
import android.os.Looper

/** Dono services ke beech ka chhota sa bridge. */
object Guard {
    var onActiveChanged: ((Boolean) -> Unit)? = null
    private var active = false
    private val main = Handler(Looper.getMainLooper())

    fun setActive(value: Boolean) {
        if (value == active) return
        active = value
        main.post { onActiveChanged?.invoke(value) }
    }

    /** Service dobara start/stop hone par purani state saaf karne ke liye. */
    fun reset() {
        active = false
    }
}
