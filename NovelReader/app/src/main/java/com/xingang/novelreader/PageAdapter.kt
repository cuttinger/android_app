package com.xingang.novelreader

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Pages of one chapter for a horizontal ViewPager2. A chapter with a
 * neighbour gets a sentinel page at that end; scrolling onto it asks the
 * activity to load the neighbouring chapter.
 */
class PageAdapter(
    private val onTap: (xFraction: Float) -> Unit,
) : RecyclerView.Adapter<PageAdapter.Holder>() {

    sealed class Item {
        data class Text(val text: CharSequence, val footer: String) : Item()
        /** Placeholder shown while the previous/next chapter loads. */
        data class Sentinel(val forward: Boolean, var message: String) : Item()
    }

    var items: List<Item> = emptyList()
        private set
    var fontSp: Float = 18f

    fun submit(list: List<Item>) {
        items = list
        notifyDataSetChanged()
    }

    fun setSentinelMessage(forward: Boolean, message: String) {
        val idx = items.indexOfFirst { it is Item.Sentinel && it.forward == forward }
        if (idx >= 0) {
            (items[idx] as Item.Sentinel).message = message
            notifyItemChanged(idx)
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.pageText)
        val footer: TextView = view.findViewById(R.id.pageFooter)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_page, parent, false)
        return Holder(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.text.textSize = fontSp
        when (item) {
            is Item.Text -> {
                holder.text.gravity = Gravity.TOP or Gravity.START
                holder.text.text = item.text
                holder.footer.text = item.footer
            }
            is Item.Sentinel -> {
                holder.text.gravity = Gravity.CENTER
                holder.text.text = item.message
                holder.footer.text = ""
            }
        }
        holder.text.setOnClickListener(null)
        var downX = 0f
        holder.text.setOnTouchListener { _, ev -> downX = ev.x; false }
        holder.text.setOnClickListener { v -> onTap(if (v.width > 0) downX / v.width else 0.5f) }
    }
}
