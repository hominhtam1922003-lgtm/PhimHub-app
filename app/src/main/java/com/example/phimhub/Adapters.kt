package com.example.phimhub

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Gan huy hieu nguon (KK / Cobe) cho 1 the phim theo URL dinh danh. */
fun bindSourceBadge(tv: TextView, url: String) {
    when {
        url.startsWith("kk://") -> {
            tv.text = "KK"
            tv.setBackgroundResource(R.drawable.bg_badge_kk)
            tv.visibility = View.VISIBLE
        }
        url.contains("/phim/") -> {
            tv.text = "Cobe"
            tv.setBackgroundResource(R.drawable.bg_badge_cobe)
            tv.visibility = View.VISIBLE
        }
        else -> tv.visibility = View.GONE
    }
}

/** Luoi poster phim (dung lai layout item_comic_grid: anh + ten + dong phu). */
class MovieGridAdapter(
    private var items: List<Movie>,
    private val onClick: (Movie) -> Unit
) : RecyclerView.Adapter<MovieGridAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val iv: ImageView = v.findViewById(R.id.ivCover)
        val tvTitle: TextView = v.findViewById(R.id.tvTitle)
        val tvSub: TextView = v.findViewById(R.id.tvLatest)
        val tvBadge: TextView = v.findViewById(R.id.tvSourceBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_comic_grid, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        holder.tvTitle.text = m.title
        holder.tvSub.text = m.sub
        bindSourceBadge(holder.tvBadge, m.url)
        ImageLoader.cover(holder.iv, m.poster)
        holder.itemView.setOnClickListener { onClick(m) }
    }

    override fun getItemCount(): Int = items.size

    fun submit(list: List<Movie>) {
        items = list
        notifyDataSetChanged()
    }

    /** Noi them 1 trang vao cuoi (tai them): chi ve lai phan moi chen. */
    fun append(more: List<Movie>) {
        if (more.isEmpty()) return
        val start = items.size
        items = items + more
        notifyItemRangeInserted(start, more.size)
    }
}

/** Danh sach dang dong (Yeu thich / Lich su). */
class MovieRowAdapter(
    private var items: List<Movie>,
    private val subs: Map<String, String> = emptyMap(),
    private val onClick: (Movie) -> Unit
) : RecyclerView.Adapter<MovieRowAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val iv: ImageView = v.findViewById(R.id.ivCoverRow)
        val tvTitle: TextView = v.findViewById(R.id.tvTitleRow)
        val tvSub: TextView = v.findViewById(R.id.tvSubRow)
        val tvBadge: TextView = v.findViewById(R.id.tvSourceBadgeRow)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_comic_row, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        holder.tvTitle.text = m.title
        holder.tvSub.text = subs[m.url] ?: m.sub
        bindSourceBadge(holder.tvBadge, m.url)
        ImageLoader.cover(holder.iv, m.poster)
        holder.itemView.setOnClickListener { onClick(m) }
    }

    override fun getItemCount(): Int = items.size

    fun submit(list: List<Movie>, newSubs: Map<String, String> = emptyMap()) {
        items = list
        notifyDataSetChanged()
    }
}
