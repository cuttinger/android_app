package com.xingang.recentapps

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var repo: UsageRepository
    private lateinit var adapter: AppListAdapter
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var list: RecyclerView
    private lateinit var permissionPanel: View
    private lateinit var subtitle: TextView
    private lateinit var searchBox: EditText
    private lateinit var clearButton: ImageButton
    private lateinit var emptyView: TextView

    /** Unfiltered list, most recent first. */
    private var allApps: List<AppUsage> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repo = UsageRepository(this)
        adapter = AppListAdapter(IconLoader(packageManager, lifecycleScope)) { app ->
            val intent = repo.launchIntent(app.packageName)
            if (intent != null) {
                startActivity(intent)
            } else {
                Toast.makeText(this, R.string.cannot_launch, Toast.LENGTH_SHORT).show()
            }
        }

        swipe = findViewById(R.id.swipe)
        list = findViewById(R.id.list)
        permissionPanel = findViewById(R.id.permissionPanel)
        subtitle = findViewById(R.id.subtitle)
        searchBox = findViewById(R.id.searchBox)
        clearButton = findViewById(R.id.clearButton)
        emptyView = findViewById(R.id.emptyView)

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        swipe.setOnRefreshListener { refresh() }

        searchBox.doAfterTextChanged { text ->
            clearButton.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
            applyFilter()
        }
        clearButton.setOnClickListener {
            searchBox.text.clear()
            searchBox.requestFocus()
        }
        // Hide the keyboard while the user scrolls the results.
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) hideKeyboard()
            }
        })

        findViewById<View>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.grantButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check on every return: the user may have just come back from
        // Settings after granting access, or from another app that is now
        // the most recent one.
        refresh()
    }

    private fun refresh() {
        val granted = repo.hasUsageAccess()
        permissionPanel.visibility = if (granted) View.GONE else View.VISIBLE
        swipe.visibility = if (granted) View.VISIBLE else View.GONE
        searchBox.isEnabled = granted
        if (!granted) {
            swipe.isRefreshing = false
            subtitle.text = getString(R.string.subtitle_no_permission)
            return
        }
        swipe.isRefreshing = true
        lifecycleScope.launch {
            allApps = repo.loadApps()
            applyFilter()
            swipe.isRefreshing = false
        }
    }

    /** Filter [allApps] by the current query and update list, subtitle and empty state. */
    private fun applyFilter() {
        val query = SearchIndex.normalize(searchBox.text)
        val shown = if (query.isEmpty()) allApps else allApps.filter { it.matches(query) }
        adapter.submitList(shown) {
            if (shown.isNotEmpty()) list.scrollToPosition(0)
        }
        emptyView.visibility = if (shown.isEmpty() && allApps.isNotEmpty()) View.VISIBLE else View.GONE
        subtitle.text = if (query.isEmpty()) {
            val used = allApps.count { it.lastUsed > 0 }
            getString(R.string.subtitle_count, allApps.size, used)
        } else {
            getString(R.string.subtitle_filtered, shown.size, allApps.size)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchBox.windowToken, 0)
        searchBox.clearFocus()
    }
}
