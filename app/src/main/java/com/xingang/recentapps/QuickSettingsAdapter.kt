package com.xingang.recentapps

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/** The quick-settings grid. Tiles with a state light up when it is on. */
class QuickSettingsAdapter(
    private val quick: QuickSettings,
    private val onClick: (QuickTile) -> Unit,
) : RecyclerView.Adapter<QuickSettingsAdapter.Holder>() {

    private val tiles = quick.tiles()

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val badge: View = view.findViewById(R.id.badge)
        val icon: ImageView = view.findViewById(R.id.icon)
        val name: TextView = view.findViewById(R.id.name)
    }

    override fun getItemCount() = tiles.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_quick, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val tile = tiles[position]
        val ctx = holder.itemView.context
        holder.icon.setImageResource(tile.iconRes)
        holder.name.setText(tile.labelRes)
        val on = quick.stateOf(tile.id) == true
        val bg = if (on) R.color.tile_on else R.color.tile_off
        holder.badge.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(ctx, bg))
        holder.icon.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(ctx, if (on) R.color.tile_icon_on else R.color.tile_icon_off)
        )
        holder.itemView.setOnClickListener { onClick(tile) }
    }
}
