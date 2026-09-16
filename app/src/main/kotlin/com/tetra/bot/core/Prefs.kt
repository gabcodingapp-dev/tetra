package com.tetra.bot.core

import android.content.Context
import com.tetra.bot.engine.Solvers
import com.tetra.bot.vision.Roi

object Prefs {
    private const val NAME = "tetra_prefs"

    private fun sp() = TetraApp.ctx().getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var roi: Roi?
        get() {
            val l = sp().getFloat("roi_left", -1f)
            val t = sp().getFloat("roi_top", -1f)
            val r = sp().getFloat("roi_right", -1f)
            val b = sp().getFloat("roi_bottom", -1f)
            if (l < 0f || t < 0f || r < 0f || b < 0f) return null
            return Roi(l, t, r, b)
        }
        set(value) {
            val e = sp().edit()
            if (value == null) {
                e.remove("roi_left").remove("roi_top").remove("roi_right").remove("roi_bottom")
            } else {
                e.putFloat("roi_left", value.left)
                    .putFloat("roi_top", value.top)
                    .putFloat("roi_right", value.right)
                    .putFloat("roi_bottom", value.bottom)
            }
            e.apply()
        }

    var speedMs: Long
        get() = sp().getLong("speed_ms", 500L)
        set(v) = sp().edit().putLong("speed_ms", v).apply()

    var strategyId: String
        get() = sp().getString("strategy", Solvers.OPTIONS[0].first)!!
        set(v) = sp().edit().putString("strategy", v).apply()
}