package com.xingang.recentapps

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.launch

/**
 * Two pages: everything launchable in most-recent order, and the apps the
 * user pinned, in the order they arranged them.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var repo: UsageRepository
    private lateinit var favorites: Favorites
    private lateinit var icons: IconLoader
    private lateinit var adapter: AppListAdapter
    private lateinit var favoritesAdapter: FavoritesAdapter
    private lateinit var pager: ViewPager2
    private lateinit var permissionPanel: View
    private lateinit var root: ViewGroup
    private lateinit var searchBar: View
    private lateinit var searchBox: EditText
    private lateinit var clearButton: ImageButton
    private lateinit var dots: View
    private lateinit var dotQuick: View
    private lateinit var dotRecent: View
    private lateinit var dotFavorites: View
    private lateinit var quick: QuickSettings
    private lateinit var quickAdapter: QuickSettingsAdapter

    // Set once the pager binds each page.
    private var swipe: SwipeRefreshLayout? = null
    private var list: RecyclerView? = null
    private var emptyView: TextView? = null
    private var quickList: RecyclerView? = null
    private var favoritesList: RecyclerView? = null
    private var favoritesHint: TextView? = null
    private var dragHelper: ItemTouchHelper? = null

    /** Unfiltered list, most recent first. */
    private var allApps: List<AppUsage> = emptyList()

    /** The search box starts out of the way and follows the scroll direction. */
    private var searchBarVisible = false

    /**
     * Submitting a list scrolls it to the top, which looks exactly like the
     * user scrolling there. Wait for a real drag before reading anything into
     * the scroll position, so the box stays hidden on a fresh start.
     */
    private var userHasScrolled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repo = UsageRepository(this)
        favorites = Favorites(this)
        quick = QuickSettings(this)
        quickAdapter = QuickSettingsAdapter(quick) { tile ->
            quick.trigger(this, tile)
            // The two stateful tiles change under the tap; redraw so they show it.
            quickList?.post { quickAdapter.notifyDataSetChanged() }
        }
        icons = IconLoader(packageManager, lifecycleScope)
        adapter = AppListAdapter(
            icons = icons,
            onClick = { launch(it) },
            onLongClick = { pin(it) },
        )
        favoritesAdapter = FavoritesAdapter(
            icons = icons,
            onClick = { launch(it) },
            onRemove = { app ->
                favorites.remove(app.packageName)
                renderFavorites()
            },
            onAdd = { showPicker() },
            onEnterEdit = { setEditing(true) },
            onStartDrag = { holder -> dragHelper?.startDrag(holder) },
        )

        root = findViewById(R.id.root)
        pager = findViewById(R.id.pager)
        permissionPanel = findViewById(R.id.permissionPanel)
        searchBar = findViewById(R.id.searchBar)
        searchBox = findViewById(R.id.searchBox)
        clearButton = findViewById(R.id.clearButton)
        dots = findViewById(R.id.dots)
        dotQuick = findViewById(R.id.dotQuick)
        dotRecent = findViewById(R.id.dotRecent)
        dotFavorites = findViewById(R.id.dotFavorites)

        pager.adapter = PagesAdapter { position, page -> bindPage(position, page) }
        pager.offscreenPageLimit = 2
        // The recent grid is the home page; the other two sit either side of it.
        pager.setCurrentItem(PagesAdapter.PAGE_RECENT, false)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // The box filters the recent page, so it has no business on the other one.
                if (position != PagesAdapter.PAGE_RECENT) setSearchBarVisible(false)
                if (position != PagesAdapter.PAGE_FAVORITES) setEditing(false)
                if (position == PagesAdapter.PAGE_QUICK) quickAdapter.notifyDataSetChanged()
                updateDots(position)
            }
        })
        updateDots(PagesAdapter.PAGE_RECENT)

        searchBox.doAfterTextChanged { text ->
            clearButton.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
            applyFilter()
        }
        clearButton.setOnClickListener {
            searchBox.text.clear()
            searchBox.requestFocus()
        }

        findViewById<Button>(R.id.grantButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    favoritesAdapter.editing -> setEditing(false)
                    pager.currentItem != PagesAdapter.PAGE_RECENT ->
                        pager.currentItem = PagesAdapter.PAGE_RECENT
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private fun bindPage(position: Int, page: View) {
        if (position == PagesAdapter.PAGE_QUICK) {
            if (quickList != null) return
            quickList = page.findViewById(R.id.quickList)
            quickList?.layoutManager = GridLayoutManager(this, COLUMNS)
            quickList?.adapter = quickAdapter
            return
        }
        if (position == PagesAdapter.PAGE_RECENT) {
            if (list != null) return
            swipe = page.findViewById(R.id.swipe)
            list = page.findViewById(R.id.list)
            emptyView = page.findViewById(R.id.emptyView)
            val rv = list ?: return
            rv.layoutManager = GridLayoutManager(this, COLUMNS)
            rv.adapter = adapter
            swipe?.setOnRefreshListener { refresh() }
            rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                // Hide the keyboard while the user scrolls the results.
                override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        userHasScrolled = true
                        hideKeyboard()
                    }
                }

                // Scrolling back towards the top brings the search box out;
                // scrolling on down puts it away again.
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (!userHasScrolled) return
                    when {
                        !rv.canScrollVertically(-1) -> setSearchBarVisible(true)
                        dy > SCROLL_SLOP -> setSearchBarVisible(false)
                        dy < -SCROLL_SLOP -> setSearchBarVisible(true)
                    }
                }
            })
            applyFilter()
        } else {
            if (favoritesList != null) return
            favoritesList = page.findViewById(R.id.favoritesList)
            favoritesHint = page.findViewById(R.id.favoritesHint)
            val rv = favoritesList ?: return
            rv.layoutManager = GridLayoutManager(this, COLUMNS)
            rv.adapter = favoritesAdapter
            dragHelper = ItemTouchHelper(dragCallback).also { it.attachToRecyclerView(rv) }
            renderFavorites()
        }
    }

    override fun onResume() {
        super.onResume()
        // Load only when there is nothing to show, or when access is missing
        // and the panel has to be put up. Coming back from another app keeps
        // the grid exactly as it was: reshuffling icons under the user's
        // finger costs more than an order that is a few minutes old. Pull
        // down to refresh.
        if (allApps.isEmpty() || !repo.hasUsageAccess()) refresh()
        // Rotation may have been flipped on the system screen we just came back from.
        if (::quickAdapter.isInitialized) quickAdapter.notifyDataSetChanged()
    }

    private fun launch(app: AppUsage) {
        val intent = repo.launchIntent(app.packageName)
        if (intent != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, R.string.cannot_launch, Toast.LENGTH_SHORT).show()
        }
    }

    private fun refresh() {
        val granted = repo.hasUsageAccess()
        permissionPanel.visibility = if (granted) View.GONE else View.VISIBLE
        pager.visibility = if (granted) View.VISIBLE else View.GONE
        dots.visibility = if (granted) View.VISIBLE else View.GONE
        searchBar.visibility = if (granted && searchBarVisible) View.VISIBLE else View.GONE
        if (!granted) {
            swipe?.isRefreshing = false
            return
        }
        swipe?.isRefreshing = true
        lifecycleScope.launch {
            allApps = repo.loadApps()
            applyFilter()
            renderFavorites()
            swipe?.isRefreshing = false
        }
    }

    /** Filter [allApps] by the current query and update the grid and empty state. */
    private fun applyFilter() {
        val query = SearchIndex.normalize(searchBox.text)
        val shown = if (query.isEmpty()) allApps else allApps.filter { it.matches(query) }
        adapter.submitList(shown) {
            if (shown.isNotEmpty()) list?.scrollToPosition(0)
        }
        emptyView?.visibility =
            if (shown.isEmpty() && allApps.isNotEmpty()) View.VISIBLE else View.GONE
    }

    // ---- pinned page ---------------------------------------------------------

    /** Long press on the recent page sends an app to the end of the pinned one. */
    private fun pin(app: AppUsage) {
        val already = app.packageName in favorites.packages()
        if (!already) {
            favorites.add(app.packageName)
            renderFavorites()
        }
        val message = if (already) R.string.already_pinned else R.string.pinned
        Toast.makeText(this, getString(message, app.label), Toast.LENGTH_SHORT).show()
    }

    /** Resolve the saved package names against the live list, keeping their order. */
    private fun renderFavorites() {
        val byPackage = allApps.associateBy { it.packageName }
        val pinned = favorites.packages().mapNotNull { byPackage[it] }
        favoritesAdapter.submit(pinned)
        favoritesHint?.setText(
            if (favoritesAdapter.editing) R.string.favorites_edit_on else R.string.favorites_hint
        )
    }

    private fun setEditing(on: Boolean) {
        if (favoritesAdapter.editing == on) return
        favoritesAdapter.editing = on
        favoritesHint?.setText(if (on) R.string.favorites_edit_on else R.string.favorites_hint)
    }

    /** Drag to reorder; the trailing add tile stays where it is. */
    private val dragCallback = object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or
            ItemTouchHelper.START or ItemTouchHelper.END,
        0,
    ) {
        override fun getMovementFlags(rv: RecyclerView, holder: RecyclerView.ViewHolder): Int =
            if (favoritesAdapter.isApp(holder.bindingAdapterPosition)) {
                super.getMovementFlags(rv, holder)
            } else {
                0
            }

        override fun isLongPressDragEnabled() = false

        override fun onMove(
            rv: RecyclerView,
            holder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean = favoritesAdapter.move(
            holder.bindingAdapterPosition, target.bindingAdapterPosition
        )

        override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun clearView(rv: RecyclerView, holder: RecyclerView.ViewHolder) {
            super.clearView(rv, holder)
            favorites.save(favoritesAdapter.packages())
        }
    }

    private fun showPicker() {
        if (allApps.isEmpty()) return
        val options = allApps.sortedBy { it.label.lowercase() }
        val labels = options.map { it.label }.toTypedArray()
        val pinned = favorites.packages().toSet()
        val checked = BooleanArray(options.size) { options[it].packageName in pinned }
        AlertDialog.Builder(this)
            .setTitle(R.string.pick_favorites)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                // Keep the order the user already arranged; append new picks.
                val picked = options.filterIndexed { i, _ -> checked[i] }.map { it.packageName }
                val kept = favorites.packages().filter { it in picked }
                favorites.save(kept + picked.filterNot { it in kept })
                renderFavorites()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- chrome --------------------------------------------------------------

    private fun updateDots(position: Int) {
        dotQuick.alpha = if (position == PagesAdapter.PAGE_QUICK) 1f else 0.3f
        dotRecent.alpha = if (position == PagesAdapter.PAGE_RECENT) 1f else 0.3f
        dotFavorites.alpha = if (position == PagesAdapter.PAGE_FAVORITES) 1f else 0.3f
    }

    /**
     * Show or hide the search box. A box with something typed in it stays put,
     * so the query never disappears while the user is still reading the result.
     */
    private fun setSearchBarVisible(visible: Boolean) {
        if (!visible && searchBox.text.isNotEmpty()) return
        if (searchBarVisible == visible) return
        searchBarVisible = visible
        TransitionManager.beginDelayedTransition(root, AutoTransition().setDuration(150))
        searchBar.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) hideKeyboard()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchBox.windowToken, 0)
        searchBox.clearFocus()
    }

    companion object {
        private const val COLUMNS = 4
        /** Ignore the few pixels of drift that a tap produces. */
        private const val SCROLL_SLOP = 6
    }
}
