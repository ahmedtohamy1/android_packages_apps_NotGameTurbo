/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.grewal.notgamemode

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

object TouchFeatureManager {

    private const val TAG = "GameModeTouchFeature"

    private const val KEY_BACKEND = "backend"

    const val TOUCH_GAME_MODE = 0
    const val TOUCH_UP_THRESHOLD = 2
    const val TOUCH_TOLERANCE = 3
    const val TOUCH_AIM_SENSITIVITY = 4
    const val TOUCH_TAP_STABILITY = 5
    const val TOUCH_EXPERT_MODE = 6
    const val TOUCH_EDGE_FILTER = 7
    const val TOUCH_PANEL_ORIENTATION = 8
    const val TOUCH_SUPER_REPORT = 202

    data class ModeRange(val min: Int, val max: Int, val def: Int)

    val TUNING_RANGES =
        linkedMapOf(
            TOUCH_UP_THRESHOLD to ModeRange(0, 4, 3),
            TOUCH_TOLERANCE to ModeRange(0, 4, 2),
            TOUCH_AIM_SENSITIVITY to ModeRange(0, 4, 2),
            TOUCH_TAP_STABILITY to ModeRange(0, 4, 2),
            TOUCH_EDGE_FILTER to ModeRange(0, 3, 2),
        )

    val EXPERT_RANGE = ModeRange(1, 3, 1)

    data class ModeQuery(
        val cur: Int?,
        val def: Int?,
        val min: Int?,
        val max: Int?,
        val values: List<Int>?,
    )

    private val backends = listOf(TouchFeatureBackend, SysfsTouchBackend)

    @Volatile private var prefs: SharedPreferences? = null

    // Prefer the kernel touch nodes; touchfeature is used as a fallback.
    @Volatile private var current: TouchBackend = SysfsTouchBackend

    @Synchronized
    fun attach(context: Context) {
        if (prefs != null) return
        prefs =
            context.applicationContext
                .createDeviceProtectedStorageContext()
                .getSharedPreferences(GamePrefs.NAME, Context.MODE_PRIVATE)
                .also { stored ->
                    val name = stored.getString(KEY_BACKEND, null) ?: return@also
                    val backend = runCatching { Backend.valueOf(name) }.getOrNull() ?: return@also
                    current = backendFor(backend)
                    Log.i(TAG, "restored $backend backend")
                }
    }

    var backend: Backend
        get() = current.id
        set(value) {
            if (current.id == value) return
            current = backendFor(value)
            prefs?.edit()?.putString(KEY_BACKEND, value.name)?.apply()
            Log.i(TAG, "switched to $value backend")
        }

    private fun backendFor(backend: Backend) = backends.first { it.id == backend }

    private fun selectFallbackIfNeeded() {
        if (current.id != Backend.SYSFS || current.isAvailable()) return
        val fallback = TouchFeatureBackend
        if (fallback.isAvailable()) {
            current = fallback
            prefs?.edit()?.putString(KEY_BACKEND, fallback.id.name)?.apply()
            Log.i(TAG, "touch nodes unavailable, fell back to ${fallback.id}")
        }
    }

    fun isAvailable(): Boolean {
        selectFallbackIfNeeded()
        return current.isAvailable()
    }
    fun isAvailable(backend: Backend): Boolean = backendFor(backend).isAvailable()
    fun alternativesTo(backend: Backend): List<Backend> =
        backends.map { it.id }.filter { it != backend }
    private fun setModeValue(mode: Int, value: Int) {
        selectFallbackIfNeeded()
        current.setModeValue(mode, value)
    }
    fun setGameMode(enabled: Boolean) {
        Log.i(TAG, "setGameMode: $enabled")
        setModeValue(TOUCH_GAME_MODE, if (enabled) 1 else 0)
        setSuperReport(enabled)
    }

    fun setSuperReport(enabled: Boolean) {
        Log.i(TAG, "setSuperReport: $enabled")
        setModeValue(TOUCH_SUPER_REPORT, if (enabled) 1 else 0)
    }

    fun setPanelOrientation(rotation: Int) {
        Log.i(TAG, "setPanelOrientation: $rotation")
        setModeValue(TOUCH_PANEL_ORIENTATION, rotation)
    }

    fun setTuning(mode: Int, value: Int) = setModeValue(mode, value)

    fun queryMode(mode: Int): ModeQuery {
        selectFallbackIfNeeded()
        return current.queryMode(mode)
    }
}
