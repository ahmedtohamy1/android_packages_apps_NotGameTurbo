/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.grewal.notgamemode

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class AppPickerFragment : Fragment() {

    private data class AppEntry(
        val packageName: String,
        val label: String,
        val icon: Drawable,
    )

    private lateinit var prefs: GamePrefs
    private val allApps = mutableListOf<AppEntry>()
    private val filteredApps = mutableListOf<AppEntry>()
    private val selectedPackages = mutableSetOf<String>()

    private lateinit var searchInput: EditText
    private lateinit var searchClear: View
    private lateinit var progressBar: ProgressBar
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var btnAddSelected: Button

    private val adapter = AppAdapter()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        prefs = GamePrefs(requireContext())
        savedInstanceState?.getStringArrayList(KEY_SELECTED)?.let {
            selectedPackages.addAll(it)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(KEY_SELECTED, ArrayList(selectedPackages))
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return inflater.inflate(R.layout.fragment_app_picker, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val searchBarContainer = view.findViewById<View>(R.id.search_bar_container)
        searchInput = view.findViewById(R.id.search_input)
        searchClear = view.findViewById(R.id.search_clear)
        progressBar = view.findViewById(R.id.progress_bar)
        recyclerView = view.findViewById(R.id.apps_recycler_view)
        emptyView = view.findViewById(R.id.empty_view)
        btnAddSelected = view.findViewById(R.id.btn_add_selected)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        searchBarContainer.setOnClickListener {
            searchInput.requestFocus()
            val imm = requireContext().getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        }

        searchInput.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val query = s?.toString().orEmpty()
                    searchClear.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                    filterApps(query)
                }
                override fun afterTextChanged(s: Editable?) {}
            }
        )

        searchInput.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val imm = requireContext().getSystemService(InputMethodManager::class.java)
                imm?.hideSoftInputFromWindow(v.windowToken, 0)
                true
            } else {
                false
            }
        }

        searchClear.setOnClickListener {
            searchInput.text?.clear()
        }

        btnAddSelected.setOnClickListener {
            confirmAdd()
        }

        updateSelectionUI()
        loadApps()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        menu
            .add(0, MENU_ADD, 0, R.string.add_selected_apps)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu
            .add(0, MENU_TOGGLE_ALL, 1, R.string.select_all)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        val addItem = menu.findItem(MENU_ADD)
        if (selectedPackages.isNotEmpty()) {
            addItem?.title = getString(R.string.add_selected_apps_count, selectedPackages.size)
            addItem?.isVisible = true
        } else {
            addItem?.isVisible = false
        }

        val toggleItem = menu.findItem(MENU_TOGGLE_ALL)
        toggleItem?.title =
            if (areAllVisibleSelected()) getString(R.string.deselect_all)
            else getString(R.string.select_all)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_ADD -> {
                confirmAdd()
                true
            }
            MENU_TOGGLE_ALL -> {
                toggleSelectAll()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun areAllVisibleSelected(): Boolean {
        if (filteredApps.isEmpty()) return false
        return filteredApps.all { selectedPackages.contains(it.packageName) }
    }

    private fun toggleSelectAll() {
        if (filteredApps.isEmpty()) return
        val visiblePkgs = filteredApps.map { it.packageName }
        if (areAllVisibleSelected()) {
            selectedPackages.removeAll(visiblePkgs.toSet())
        } else {
            selectedPackages.addAll(visiblePkgs)
        }
        adapter.notifyDataSetChanged()
        updateSelectionUI()
    }

    private fun confirmAdd() {
        if (selectedPackages.isEmpty()) return
        prefs.addCustom(selectedPackages)
        activity?.finish()
    }

    private fun updateSelectionUI() {
        if (!isAdded) return
        val count = selectedPackages.size
        if (count > 0) {
            btnAddSelected.text = getString(R.string.add_selected_apps_count, count)
            btnAddSelected.visibility = View.VISIBLE
        } else {
            btnAddSelected.visibility = View.GONE
        }
        activity?.invalidateOptionsMenu()
    }

    private fun filterApps(query: String) {
        val trimmed = query.trim()
        filteredApps.clear()
        if (trimmed.isEmpty()) {
            filteredApps.addAll(allApps)
        } else {
            filteredApps.addAll(
                allApps.filter {
                    it.label.contains(trimmed, ignoreCase = true) ||
                        it.packageName.contains(trimmed, ignoreCase = true)
                }
            )
        }
        adapter.notifyDataSetChanged()
        emptyView.visibility =
            if (filteredApps.isEmpty() && allApps.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun loadApps() {
        val context = context ?: return
        val appContext = context.applicationContext
        progressBar.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.GONE

        Thread {
            val pm = appContext.packageManager
            val custom = prefs.customPackages
            val installed =
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))

            val shown =
                custom +
                    installed
                        .filter { it.category == ApplicationInfo.CATEGORY_GAME }
                        .map { it.packageName }

            val apps =
                installed
                    .filter {
                        it.packageName !in shown &&
                            it.packageName != appContext.packageName &&
                            pm.getLaunchIntentForPackage(it.packageName) != null
                    }
                    .map { info ->
                        AppEntry(
                            packageName = info.packageName,
                            label = pm.getApplicationLabel(info).toString(),
                            icon = pm.getApplicationIcon(info),
                        )
                    }
                    .sortedBy { it.label.lowercase() }

            mainHandler.post {
                if (!isAdded) return@post
                allApps.clear()
                allApps.addAll(apps)
                progressBar.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                filterApps(searchInput.text?.toString().orEmpty())
            }
        }.start()
    }

    private inner class AppAdapter : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.app_icon)
            val label: TextView = view.findViewById(R.id.app_label)
            val checkBox: CheckBox = view.findViewById(R.id.app_checkbox)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view =
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_app_picker, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = filteredApps[position]
            holder.label.text = entry.label
            holder.icon.setImageDrawable(entry.icon)
            holder.checkBox.isChecked = selectedPackages.contains(entry.packageName)

            holder.itemView.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos < filteredApps.size) {
                    val app = filteredApps[pos]
                    if (selectedPackages.contains(app.packageName)) {
                        selectedPackages.remove(app.packageName)
                        holder.checkBox.isChecked = false
                    } else {
                        selectedPackages.add(app.packageName)
                        holder.checkBox.isChecked = true
                    }
                    updateSelectionUI()
                }
            }
        }

        override fun getItemCount() = filteredApps.size
    }

    companion object {
        private const val MENU_ADD = 101
        private const val MENU_TOGGLE_ALL = 102
        private const val KEY_SELECTED = "selected_packages"
    }
}
