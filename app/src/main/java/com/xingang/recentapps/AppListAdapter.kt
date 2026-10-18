package com.xingang.recentapps

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Grid of launchable apps, most recently opened first. Icon and name only. */
class AppListAdapter(
    private val icons: IconLoader,
    private val onClick: (AppUsage) -> Unit,
    private val onLongClick: (AppUsage) -> Unit,
) : RecyclerView.Adapter<AppListAdapter.Holder>() {

    private var items: List<AppUsage> = emptyList()

    fun submit(list: List<AppUsage>) {
        items = list
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.icon)
        val name: TextView = view.findViewById(R.id.name)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        icons.load(item.packageName, holder.icon)
        holder.name.text = item.label
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }
}
