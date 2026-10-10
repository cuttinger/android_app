package com.xingang.recentapps

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.DateFormat
import java.util.Date

class AppListAdapter(
    private val icons: IconLoader,
    private val onClick: (AppUsage) -> Unit,
) : ListAdapter<AppUsage, AppListAdapter.Holder>(DIFF) {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.icon)
        val name: TextView = view.findViewById(R.id.name)
        val relative: TextView = view.findViewById(R.id.relativeTime)
        val absolute: TextView = view.findViewById(R.id.absoluteTime)
        val rank: TextView = view.findViewById(R.id.rank)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        val ctx = holder.itemView.context
        icons.load(item.packageName, holder.icon)
        holder.name.text = item.label
        holder.rank.text = (position + 1).toString()
        if (item.lastUsed > 0) {
            holder.relative.text = DateUtils.getRelativeTimeSpanString(
                item.lastUsed, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            )
            holder.absolute.text = DateFormat.getDateTimeInstance(
                DateFormat.MEDIUM, DateFormat.SHORT
            ).format(Date(item.lastUsed))
        } else {
            holder.relative.text = ctx.getString(R.string.never_used)
            holder.absolute.text =
                if (item.labelEn != item.label) item.labelEn else item.packageName
        }
        holder.itemView.setOnClickListener { onClick(item) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AppUsage>() {
            override fun areItemsTheSame(a: AppUsage, b: AppUsage) = a.packageName == b.packageName
            override fun areContentsTheSame(a: AppUsage, b: AppUsage) =
                a.label == b.label && a.lastUsed == b.lastUsed
        }
    }
}
