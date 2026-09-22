/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.grewal.notgamemode

import android.os.Bundle
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity
class AppSettingsActivity : CollapsingToolbarBaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        intent.getStringExtra(EXTRA_PACKAGE)?.let { pkg ->
            if (pkg == GamePrefs.GLOBAL_PKG) {
                title = getString(R.string.global_game_mode_title)
            } else {
                runCatching {
                    title =
                        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0))
                }
            }
        }

        supportFragmentManager
            .beginTransaction()
            .replace(
                com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                AppSettingsFragment(),
            )
            .commit()
    }

    companion object {
        const val EXTRA_PACKAGE = "package"
    }
}
