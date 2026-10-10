package com.xingang.novelreader

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChapterAdapter(
    private val chapters: List<Link>,
    private val currentUrl: String?,
    private val onClick: (Link) -> Unit,
) : RecyclerView.Adapter<ChapterAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.chapterName)
        val marker: View = view.findViewById(R.id.marker)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_chapter, parent, false))

    override fun getItemCount() = chapters.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val ch = chapters[position]
        val isCurrent = ch.url == currentUrl
        holder.title.text = ch.text
        holder.title.setTypeface(null, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
        holder.marker.visibility = if (isCurrent) View.VISIBLE else View.INVISIBLE
        holder.itemView.setOnClickListener { onClick(ch) }
    }
}
