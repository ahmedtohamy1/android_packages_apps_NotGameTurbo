/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.grewal.notgamemode

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceViewHolder
import com.android.settingslib.widget.SettingsBasePreferenceFragment

class GameModeFragment : SettingsBasePreferenceFragment() {

    private lateinit var prefs: GamePrefs

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setHasOptionsMenu(true)
        prefs = GamePrefs(requireContext())
        rebuild()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        menu
            .add(0, MENU_TEST, 0, R.string.touch_test_title)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu
            .add(0, MENU_DEBUG, 1, R.string.debug_title)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_TEST -> {
                startActivity(Intent(requireContext(), TouchTestActivity::class.java))
                true
            }
            MENU_DEBUG -> {
                startActivity(Intent(requireContext(), DebugActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()

        if (::prefs.isInitialized) {
            rebuild()
        }
    }

    private fun rebuild() {
        val context = requireContext()
        val pm = context.packageManager
        val screen = preferenceManager.createPreferenceScreen(context)

        val isGlobal = prefs.isGlobalEnabled

        val globalCategory =
            PreferenceCategory(context).apply { title = getString(R.string.global_category_title) }
        screen.addPreference(globalCategory)

        val globalPref =
            object : Preference(context) {
                override fun onBindViewHolder(holder: PreferenceViewHolder) {
                    super.onBindViewHolder(holder)
                    val switchView = holder.findViewById(R.id.global_switch) as? Switch
                    switchView?.setOnCheckedChangeListener(null)
                    switchView?.isChecked = prefs.isGlobalEnabled
                    switchView?.setOnCheckedChangeListener { _, isChecked ->
                        if (prefs.isGlobalEnabled != isChecked) {
                            prefs.isGlobalEnabled = isChecked
                            notifyService()
                            rebuild()
                        }
                    }
                }
            }.apply {
                title = getString(R.string.global_game_mode_title)
                summary = getString(
                    if (isGlobal) R.string.global_game_mode_enabled_summary
                    else R.string.global_game_mode_summary
                )
                widgetLayoutResource = R.layout.preference_widget_switch
                setOnPreferenceClickListener {
                    startActivity(
                        Intent(requireContext(), AppSettingsActivity::class.java)
                            .putExtra(AppSettingsActivity.EXTRA_PACKAGE, GamePrefs.GLOBAL_PKG)
                    )
                    true
                }
            }
        globalCategory.addPreference(globalPref)

        val custom = prefs.customPackages
        val detected =
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0)).filter {
                it.category == ApplicationInfo.CATEGORY_GAME && it.packageName !in custom
            }

        val games =
            PreferenceCategory(context).apply {
                title = getString(R.string.games_category_title)
                isEnabled = !isGlobal
            }
        screen.addPreference(games)
        if (detected.isEmpty()) {
            games.addPreference(
                Preference(context).apply {
                    summary = getString(R.string.no_games_summary)
                    isSelectable = false
                }
            )
        } else {
            detected.sortedBy { label(pm, it) }.forEach { games.addPreference(appRow(it, isGlobal = isGlobal)) }
        }

        val customCategory =
            PreferenceCategory(context).apply {
                title = getString(R.string.custom_category_title)
                isEnabled = !isGlobal
            }
        screen.addPreference(customCategory)
        custom
            .mapNotNull { runCatching { pm.getApplicationInfo(it, 0) }.getOrNull() }
            .sortedBy { label(pm, it) }
            .forEach { customCategory.addPreference(appRow(it, removable = true, isGlobal = isGlobal)) }
        customCategory.addPreference(
            Preference(context).apply {
                title = getString(R.string.add_app_title)
                summary = getString(R.string.add_app_summary).takeIf { custom.isNotEmpty() && !isGlobal }
                isEnabled = !isGlobal
                if (!isGlobal) {
                    setOnPreferenceClickListener {
                        showAppPicker()
                        true
                    }
                }
            }
        )

        preferenceScreen = screen
    }

    private fun appRow(
        app: ApplicationInfo,
        removable: Boolean = false,
        isGlobal: Boolean = false,
    ): Preference {
        val pm = requireContext().packageManager
        val preference =
            object : Preference(requireContext()) {
                override fun onBindViewHolder(holder: PreferenceViewHolder) {
                    super.onBindViewHolder(holder)
                    holder.itemView.setOnLongClickListener {
                        if (removable && !isGlobal) {
                            confirmRemoveCustom(app)
                            true
                        } else {
                            false
                        }
                    }
                }
            }
        return preference.apply {
            title = label(pm, app)
            summary = when {
                isGlobal -> getString(R.string.global_mode_active_summary)
                prefs.isEnabled(app.packageName) -> getString(R.string.app_enabled)
                else -> null
            }
            icon = pm.getApplicationIcon(app)
            isEnabled = !isGlobal
            if (!isGlobal) {
                setOnPreferenceClickListener {
                    startActivity(
                        Intent(requireContext(), AppSettingsActivity::class.java)
                            .putExtra(AppSettingsActivity.EXTRA_PACKAGE, app.packageName)
                    )
                    true
                }
            }
        }
    }

    private fun notifyService() {
        val context = context ?: return
        context.startService(
            Intent(context, GameModeService::class.java).setAction(GameModeService.ACTION_RELOAD)
        )
    }

    private fun confirmRemoveCustom(app: ApplicationInfo) {
        val pm = requireContext().packageManager
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.remove_app_title)
            .setMessage(getString(R.string.remove_app_message, label(pm, app)))
            .setPositiveButton(R.string.remove_app_confirm) { _, _ ->
                prefs.removeCustom(app.packageName)
                rebuild()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAppPicker() {
        startActivity(Intent(requireContext(), AppPickerActivity::class.java))
    }

    private fun label(pm: PackageManager, app: ApplicationInfo) =
        pm.getApplicationLabel(app).toString()

    companion object {
        private const val MENU_TEST = 1
        private const val MENU_DEBUG = 2
    }
}
