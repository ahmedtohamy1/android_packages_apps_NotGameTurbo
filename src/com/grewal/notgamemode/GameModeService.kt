/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.grewal.notgamemode

import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log

class GameModeService : Service() {

    private lateinit var prefs: GamePrefs
    private lateinit var usageStats: UsageStatsManager
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler

    private var lastForeground: String? = null
    private var gameModeActive = false
    private var activePkg: String? = null
    private var savedTuning: Map<Int, Int> = emptyMap()

    private val poll =
        object : Runnable {
            override fun run() {
                update()
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }

    override fun onCreate() {
        super.onCreate()
        TouchFeatureManager.attach(this)
        prefs = GamePrefs(this)
        usageStats = getSystemService(UsageStatsManager::class.java)
        thread = HandlerThread(TAG).apply { start() }
        handler = Handler(thread.looper)
        handler.post(poll)
        Log.i(TAG, "started")
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        thread.quitSafely()
        if (gameModeActive) {
            stopGameMode()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (::handler.isInitialized) {
            handler.post { update(force = intent?.action == ACTION_RELOAD) }
        }
        return START_STICKY
    }

    private fun update(force: Boolean = false) {

        if (manualOverride) {
            return
        }

        val foreground = currentForegroundPackage() ?: lastForeground
        lastForeground = foreground

        val global = prefs.isGlobalEnabled
        val shouldEnable = global || (foreground != null && prefs.isEnabled(foreground))
        val targetPkg = if (global) GamePrefs.GLOBAL_PKG else foreground

        if (shouldEnable && targetPkg != null) {
            if (!gameModeActive) {
                gameModeActive = true
                activePkg = targetPkg

                saveTuning()
                applyTuning(targetPkg)
                TouchFeatureManager.setGameMode(true)
                TouchFeatureManager.setSuperReport(prefs.isSuperReport(targetPkg))
                setOrientationTracking(true)
            } else if (targetPkg != activePkg || force) {
                activePkg = targetPkg
                applyTuning(targetPkg)
                TouchFeatureManager.setSuperReport(prefs.isSuperReport(targetPkg))
            }
        } else if (gameModeActive) {
            stopGameMode()
        }
    }

    private fun saveTuning() {
        savedTuning =
            (TouchFeatureManager.TUNING_RANGES.keys + TouchFeatureManager.TOUCH_EXPERT_MODE)
                .mapNotNull { mode ->
                    TouchFeatureManager.queryMode(mode).cur?.let { mode to it }
                }
                .toMap()
    }

    private fun stopGameMode() {
        TouchFeatureManager.setGameMode(false)
        setOrientationTracking(false)
        savedTuning.forEach { (mode, value) -> TouchFeatureManager.setTuning(mode, value) }
        savedTuning = emptyMap()
        gameModeActive = false
        activePkg = null
    }

    private fun applyTuning(pkg: String) {
        if (prefs.isExpert(pkg)) {

            TouchFeatureManager.setTuning(
                TouchFeatureManager.TOUCH_EXPERT_MODE,
                prefs.expertPreset(pkg),
            )
            return
        }
        TouchFeatureManager.setTuning(TouchFeatureManager.TOUCH_EXPERT_MODE, 0)
        TouchFeatureManager.TUNING_RANGES.forEach { (mode, range) ->
            TouchFeatureManager.setTuning(mode, prefs.tuningValue(pkg, mode, range.def))
        }
    }

    private fun setOrientationTracking(enabled: Boolean) {
        val intent = Intent(this, TouchOrientationService::class.java)
        if (enabled) startService(intent) else stopService(intent)
    }

    private fun currentForegroundPackage(): String? {
        val now = System.currentTimeMillis()
        val events = usageStats.queryEvents(now - QUERY_WINDOW_MS, now)
        val event = android.app.usage.UsageEvents.Event()
        var pkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (
                event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED
            ) {
                pkg = event.packageName
            }
        }
        return pkg
    }

    companion object {
        const val TAG = "GameModeService"
        const val ACTION_RELOAD = "com.grewal.notgamemode.ACTION_RELOAD"
        private const val POLL_INTERVAL_MS = 2000L
        private const val QUERY_WINDOW_MS = 10_000L

        @Volatile var manualOverride = false
    }
}
