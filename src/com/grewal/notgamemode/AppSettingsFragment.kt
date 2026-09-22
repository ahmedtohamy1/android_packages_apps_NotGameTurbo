/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.grewal.notgamemode

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.MainSwitchPreference
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import com.android.settingslib.widget.SliderPreference

class AppSettingsFragment : SettingsBasePreferenceFragment() {

    private lateinit var pkg: String
    private val manualSliders = mutableListOf<Preference>()
    private var superSwitch: SwitchPreferenceCompat? = null
    private var expertSwitch: SwitchPreferenceCompat? = null
    private var presetSlider: SliderPreference? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val extra = requireActivity().intent.getStringExtra(AppSettingsActivity.EXTRA_PACKAGE)
        if (extra == null) {
            requireActivity().finish()
            return
        }
        pkg = extra
        setHasOptionsMenu(true)

        preferenceManager.setStorageDeviceProtected()
        preferenceManager.sharedPreferencesName = GamePrefs.NAME
        buildScreen()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        menu.add(0, MENU_RESET, 0, R.string.reset_title).apply {
            setIcon(R.drawable.ic_reset)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_RESET) {
            confirmReset()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun buildScreen() {
        manualSliders.clear()

        val context = requireContext()
        val isGlobal = pkg == GamePrefs.GLOBAL_PKG
        val store = preferenceManager.sharedPreferences
        val screen = preferenceManager.createPreferenceScreen(context)

        var gameEnabled = store?.getBoolean(GamePrefs.enabledKey(pkg), false) ?: false
        var expertOn = store?.getBoolean(GamePrefs.expertKey(pkg), false) ?: false

        val masterSwitch =
            MainSwitchPreference(context).apply {
                key = GamePrefs.enabledKey(pkg)
                title = getString(
                    if (isGlobal) R.string.global_game_mode_title
                    else R.string.app_enable_title
                )
                setDefaultValue(false)
            }
        screen.addPreference(masterSwitch)

        superSwitch =
            SwitchPreferenceCompat(context).apply {
                key = GamePrefs.superKey(pkg)
                title = getString(R.string.super_report_title)
                summary = getString(R.string.super_report_summary)
                setDefaultValue(true)
                isIconSpaceReserved = false
                isEnabled = false
                setOnPreferenceChangeListener { _, _ ->
                    notifyService()
                    true
                }
            }
        screen.addPreference(superSwitch!!)

        val tuning =
            PreferenceCategory(context).apply { title = getString(R.string.tuning_category_title) }
        screen.addPreference(tuning)

        expertSwitch =
            SwitchPreferenceCompat(context).apply {
                key = GamePrefs.expertKey(pkg)
                title = getString(R.string.expert_mode_title)
                summary = getString(R.string.expert_mode_summary)
                setDefaultValue(false)
                isIconSpaceReserved = false
            }
        tuning.addPreference(expertSwitch!!)

        val expert = TouchFeatureManager.EXPERT_RANGE
        val presetKey = GamePrefs.expertPresetKey(pkg)
        presetSlider =
            SliderPreference(context).apply {
                key = presetKey
                title = getString(R.string.expert_preset_title)
                min = expert.min
                max = expert.max
                sliderIncrement = 1
                setTickVisible(true)
                value = store?.getInt(presetKey, expert.def) ?: expert.def
                setDefaultValue(expert.def)
                isIconSpaceReserved = false
                setOnPreferenceChangeListener { _, _ ->
                    notifyService()
                    true
                }
            }
        tuning.addPreference(presetSlider!!)

        val labels =
            mapOf(
                TouchFeatureManager.TOUCH_UP_THRESHOLD to R.string.tune_up_threshold,
                TouchFeatureManager.TOUCH_TOLERANCE to R.string.tune_tolerance,
                TouchFeatureManager.TOUCH_AIM_SENSITIVITY to R.string.tune_aim_sensitivity,
                TouchFeatureManager.TOUCH_TAP_STABILITY to R.string.tune_tap_stability,
                TouchFeatureManager.TOUCH_EDGE_FILTER to R.string.tune_edge_filter,
            )
        TouchFeatureManager.TUNING_RANGES.forEach { (mode, range) ->
            val key = GamePrefs.tuneKey(pkg, mode)
            val slider =
                SliderPreference(context).apply {
                    this.key = key
                    title = getString(labels.getValue(mode))
                    min = range.min
                    max = range.max
                    sliderIncrement = 1
                    setTickVisible(true)
                    value = store?.getInt(key, range.def) ?: range.def
                    setDefaultValue(range.def)
                    isIconSpaceReserved = false
                    setOnPreferenceChangeListener { _, _ ->
                        notifyService()
                        true
                    }
                }
            tuning.addPreference(slider)
            manualSliders.add(slider)
        }

        fun updateEnabled() {

            expertSwitch?.isEnabled = gameEnabled
            presetSlider?.isEnabled = gameEnabled && expertOn
            manualSliders.forEach { it.isEnabled = gameEnabled && !expertOn }
        }
        updateEnabled()

        masterSwitch.setOnPreferenceChangeListener { _, newValue ->
            gameEnabled = newValue as Boolean
            updateEnabled()
            notifyService()
            true
        }
        expertSwitch!!.setOnPreferenceChangeListener { _, newValue ->
            expertOn = newValue as Boolean
            updateEnabled()
            notifyService()
            true
        }

        preferenceScreen = screen
    }

    private fun confirmReset() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.reset_title)
            .setMessage(
                if (pkg == GamePrefs.GLOBAL_PKG) R.string.reset_global_message
                else R.string.reset_message
            )
            .setPositiveButton(android.R.string.ok) { _, _ ->
                resetDefaults()
                notifyService()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun notifyService() {
        val context = context ?: return
        context.startService(
            Intent(context, GameModeService::class.java).setAction(GameModeService.ACTION_RELOAD)
        )
    }

    private fun resetDefaults() {
        val editor = preferenceManager.sharedPreferences?.edit() ?: return
        editor.remove(GamePrefs.enabledKey(pkg))
        editor.remove(GamePrefs.superKey(pkg))
        editor.remove(GamePrefs.expertKey(pkg))
        editor.remove(GamePrefs.expertPresetKey(pkg))
        TouchFeatureManager.TUNING_RANGES.keys.forEach { editor.remove(GamePrefs.tuneKey(pkg, it)) }
        editor.apply()

        buildScreen()
    }

    companion object {
        private const val MENU_RESET = 1
    }
}
