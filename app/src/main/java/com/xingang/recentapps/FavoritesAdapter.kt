package com.xingang.recentapps

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections

/**
 * The pinned apps, plus one trailing tile that opens the picker. A long press
 * turns on edit mode, where every tile grows a remove badge and a further long
 * press picks the tile up for reordering.
 */
class FavoritesAdapter(
    private val icons: IconLoader,
    private val onClick: (AppUsage) -> Unit,
    private val onRemove: (AppUsage) -> Unit,
    private val onAdd: () -> Unit,
    private val onEnterEdit: () -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = ArrayList<AppUsage>()

    var editing = false
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, items.size)
        }

    fun submit(list: List<AppUsage>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /** Current order, for persisting after a drag. */
    fun packages(): List<String> = items.map { it.packageName }

    fun isApp(position: Int) = position < items.size

    fun move(from: Int, to: Int): Boolean {
        if (!isApp(from) || !isApp(to)) return false
        Collections.swap(items, from, to)
        notifyItemMoved(from, to)
        return true
    }

    override fun getItemCount() = items.size + 1

    override fun getItemViewType(position: Int) = if (isApp(position)) TYPE_APP else TYPE_ADD

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_APP) {
            AppHolder(inflater.inflate(R.layout.item_favorite, parent, false))
        } else {
            AddHolder(inflater.inflate(R.layout.item_add, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder !is AppHolder) {
            holder.itemView.setOnClickListener { onAdd() }
            return
        }
        val item = items[position]
        icons.load(item.packageName, holder.icon)
        holder.name.text = item.label
        holder.remove.visibility = if (editing) View.VISIBLE else View.GONE
        holder.remove.setOnClickListener { onRemove(item) }
        holder.itemView.setOnClickListener { if (!editing) onClick(item) }
        holder.itemView.setOnLongClickListener {
            if (editing) onStartDrag(holder) else onEnterEdit()
            true
        }
    }

    class AppHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.icon)
        val name: TextView = view.findViewById(R.id.name)
        val remove: View = view.findViewById(R.id.remove)
    }

    class AddHolder(view: View) : RecyclerView.ViewHolder(view)

    private companion object {
        const val TYPE_APP = 0
        const val TYPE_ADD = 1
    }
}
