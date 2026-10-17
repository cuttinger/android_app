package com.xingang.recentapps

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/** Grid of launchable apps, most recently opened first. Icon and name only. */
class AppListAdapter(
    private val icons: IconLoader,
    private val onClick: (AppUsage) -> Unit,
    private val onLongClick: (AppUsage) -> Unit,
) : ListAdapter<AppUsage, AppListAdapter.Holder>(DIFF) {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.icon)
        val name: TextView = view.findViewById(R.id.name)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        icons.load(item.packageName, holder.icon)
        holder.name.text = item.label
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AppUsage>() {
            override fun areItemsTheSame(a: AppUsage, b: AppUsage) = a.packageName == b.packageName
            override fun areContentsTheSame(a: AppUsage, b: AppUsage) =
                a.label == b.label && a.lastUsed == b.lastUsed
        }
    }
}
