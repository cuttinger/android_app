package com.xingang.recentapps

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/** Quick settings, the recent grid, then the pinned one. */
class PagesAdapter(private val onPageBound: (position: Int, page: View) -> Unit) :
    RecyclerView.Adapter<PagesAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view)

    override fun getItemCount() = 3

    override fun getItemViewType(position: Int) = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val layout = when (viewType) {
            PAGE_QUICK -> R.layout.page_quick
            PAGE_RECENT -> R.layout.page_recent
            else -> R.layout.page_favorites
        }
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        v.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) =
        onPageBound(position, holder.itemView)

    companion object {
        const val PAGE_QUICK = 0
        const val PAGE_RECENT = 1
        const val PAGE_FAVORITES = 2
    }
}
