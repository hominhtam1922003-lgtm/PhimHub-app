package com.example.phimhub

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlin.concurrent.thread

class DetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "movie_url"
        const val EXTRA_TITLE = "movie_title"
        const val EXTRA_POSTER = "movie_poster"
    }

    private lateinit var db: DbRepo
    private var detail: MovieDetail? = null
    private lateinit var movieUrl: String
    private var preTitle: String = ""
    private var currentServer = 0

    // Tai chi tiet 2 PHA: nguon chinh ve truoc la hien ngay (meta +
    // danh sach tap + nut Xem ngay), ban sinh doi cua nguon kia ghep
    // them nhom server khi tai xong — truoc day cho ca 2 nguon xong
    // moi hien nen mo phim nao cung quay lau
    private var primaryDetail: MovieDetail? = null
    private var twinDetail: MovieDetail? = null
    private var primarySettled = false
    private var twinSettled = false
    private var detailShown = false
    private var twinMergedIn = false

    private lateinit var rvEpisodes: RecyclerView
    private lateinit var epAdapter: EpisodeAdapter

    inner class EpisodeAdapter(private var items: List<Episode>) :
        RecyclerView.Adapter<EpisodeAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tv: TextView = v.findViewById(R.id.tvEpisode)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_episode, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ep = items[position]
            holder.tv.text = ep.name
            holder.itemView.setOnClickListener { playEpisode(ep) }
        }

        override fun getItemCount(): Int = items.size

        fun submit(list: List<Episode>) {
            items = list
            notifyDataSetChanged()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)
        db = DbRepo(this)

        movieUrl = intent.getStringExtra(EXTRA_URL)?.let {
            if (Sources.isKk(it)) it else CobeSource.rebase(it)
        } ?: run { finish(); return }
        preTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val prePoster = intent.getStringExtra(EXTRA_POSTER) ?: ""

        title = preTitle
        findViewById<TextView>(R.id.tvDetailTitle).text = preTitle
        if (prePoster.isNotEmpty()) {
            ImageLoader.cover(findViewById(R.id.ivDetailCover), prePoster)
            ImageLoader.backdrop(findViewById(R.id.ivDetailBackdrop), prePoster)
        }

        rvEpisodes = findViewById(R.id.rvChapters)
        rvEpisodes.layoutManager = GridLayoutManager(this, 4)
        epAdapter = EpisodeAdapter(emptyList())
        rvEpisodes.adapter = epAdapter

        updateFavoriteButton()
        findViewById<Button>(R.id.btnFavorite).setOnClickListener {
            val d = detail ?: return@setOnClickListener
            if (db.isFavorite(movieUrl)) {
                db.removeFavorite(movieUrl)
                Toast.makeText(this, "Đã bỏ yêu thích", Toast.LENGTH_SHORT).show()
            } else {
                db.addFavorite(d.movie, d.totalEpisodes)
                Toast.makeText(this, "Đã thêm vào yêu thích", Toast.LENGTH_SHORT).show()
            }
            updateFavoriteButton()
        }
        // Khoa nut Xem ngay cho den khi tai xong danh sach tap: ban cu
        // de bam duoc trong khi chua co tap nao -> player mo ra do
        // nguon tren trang khong phai trang xem, quay rat lau roi bao loi
        val btnWatch = findViewById<Button>(R.id.btnWatchFirst)
        btnWatch.isEnabled = false
        btnWatch.setOnClickListener {
            val first = detail?.servers?.firstOrNull()?.episodes?.firstOrNull()
            if (first != null) {
                playEpisode(first)
            } else {
                Toast.makeText(
                    this,
                    "Danh sách tập chưa tải xong, chờ chút rồi thử lại nhé",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        loadDetail()
    }

    private fun updateFavoriteButton() {
        findViewById<Button>(R.id.btnFavorite).text =
            if (db.isFavorite(movieUrl)) "★ Đã yêu thích" else "☆ Yêu thích"
    }

    private fun loadDetail() {
        findViewById<ProgressBar>(R.id.detailProgress).visibility = View.VISIBLE
        val primaryIsKk = Sources.isKk(movieUrl)
        // Ban sinh doi: chay ngam song song tu dau, tim bang tua the
        thread {
            var t: MovieDetail? = null
            try {
                t = Sources.fetchTwin(preTitle, primaryIsKk)
            } catch (e: Exception) {
                // Khong co ban sinh doi: dung 1 nguon
            }
            runOnUiThread {
                twinDetail = t
                twinSettled = true
                onDetailPartReady(primaryIsKk)
            }
        }
        // Nguon chinh: ve den dau hien den do
        thread {
            RemoteConfig.awaitReady(1500)
            var p: MovieDetail? = null
            try {
                p = Sources.fetchPrimary(movieUrl)
            } catch (e: Exception) {
                // Con duong lui bang ban sinh doi o onDetailPartReady
            }
            runOnUiThread {
                primaryDetail = p
                primarySettled = true
                onDetailPartReady(primaryIsKk)
            }
        }
    }

    private fun onDetailPartReady(primaryIsKk: Boolean) {
        if (isFinishing || isDestroyed) return
        if (!detailShown) {
            when {
                primaryDetail != null &&
                    (primaryDetail!!.servers.isNotEmpty() || twinSettled) -> {
                    detailShown = true
                    twinMergedIn = twinSettled && twinDetail != null
                    showDetail(
                        Sources.mergeDetails(
                            primaryDetail, primaryIsKk,
                            if (twinMergedIn) twinDetail else null
                        )
                    )
                }
                primarySettled && twinSettled -> {
                    if (twinDetail != null) {
                        // Nguon chinh loi: dung ban sinh doi lam chinh
                        detailShown = true
                        twinMergedIn = true
                        showDetail(Sources.mergeDetails(null, primaryIsKk, twinDetail))
                    } else {
                        showLoadError()
                    }
                }
                else -> {
                    // Nguon chinh loi, ban sinh doi chua ve: giu vong xoay
                }
            }
        } else if (!twinMergedIn && twinSettled && twinDetail != null &&
            primaryDetail != null
        ) {
            // Ghep them nhom server cua nguon kia vao man dang hien,
            // giu nguyen ban dang chon
            twinMergedIn = true
            val merged = Sources.mergeDetails(primaryDetail, primaryIsKk, twinDetail)
            detail = merged
            findViewById<TextView>(R.id.tvChapterCount).text =
                "Danh sách tập (${merged.totalEpisodes} tập)"
            if (db.isFavorite(movieUrl)) {
                db.updateFavoriteEpisodeCount(movieUrl, merged.totalEpisodes)
            }
            buildServerButtons(merged)
            val keep = minOf(currentServer, merged.servers.size - 1).coerceAtLeast(0)
            selectServer(keep)
            warmVersions(merged)
        }
    }

    private fun showLoadError() {
        findViewById<ProgressBar>(R.id.detailProgress).visibility = View.GONE
        Toast.makeText(
            this,
            "Không tải được danh sách tập. Kiểm tra mạng rồi mở lại phim nhé.",
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * Lam am phan loai ban chieu cho tap dau cua moi nhom server:
     * nguoi dung bam Xem la player biet ngay ban nao song/chet,
     * khoi mat vai giay phan loai lai (xem VersionCheck).
     */
    private fun warmVersions(d: MovieDetail) {
        val urls = ArrayList<String>()
        for (g in d.servers) {
            g.episodes.firstOrNull()?.let { urls.add(it.url) }
        }
        if (urls.isNotEmpty()) VersionCheck.warm(urls)
    }

    private fun showDetail(d: MovieDetail) {
        findViewById<ProgressBar>(R.id.detailProgress).visibility = View.GONE
        findViewById<Button>(R.id.btnWatchFirst).isEnabled = true
        detail = d
        title = d.movie.title
        findViewById<TextView>(R.id.tvDetailTitle).text = d.movie.title
        if (d.movie.poster.isNotEmpty()) {
            ImageLoader.cover(findViewById(R.id.ivDetailCover), d.movie.poster)
            ImageLoader.backdrop(findViewById(R.id.ivDetailBackdrop), d.movie.poster)
        }
        findViewById<TextView>(R.id.tvDetailMeta).text = d.meta
        findViewById<TextView>(R.id.tvDetailDesc).text = d.description
        findViewById<TextView>(R.id.tvChapterCount).text =
            "Danh sách tập (${d.totalEpisodes} tập)"
        if (db.isFavorite(movieUrl)) {
            db.updateFavoriteEpisodeCount(movieUrl, d.totalEpisodes)
        }
        buildServerButtons(d)
        selectServer(0)
        warmVersions(d)
    }

    private fun buildServerButtons(d: MovieDetail) {
        if (d.servers.size <= 1) return
        findViewById<TextView>(R.id.tvServerLabel).visibility = View.VISIBLE
        findViewById<View>(R.id.svServers).visibility = View.VISIBLE
        val ll = findViewById<LinearLayout>(R.id.llServers)
        ll.removeAllViews()
        d.servers.forEachIndexed { idx, server ->
            val b = MaterialButton(this)
            b.text = server.name
            b.cornerRadius = (16 * resources.displayMetrics.density).toInt()
            b.setOnClickListener { selectServer(idx) }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = 8
            b.layoutParams = lp
            ll.addView(b)
        }
    }

    private fun selectServer(idx: Int) {
        val d = detail ?: return
        if (idx !in d.servers.indices) return
        currentServer = idx
        epAdapter.submit(d.servers[idx].episodes)
    }

    private fun playEpisode(ep: Episode) {
        val d = detail ?: return
        db.addHistory(d.movie, ep.name, ep.url)
        val i = Intent(this, PlayerActivity::class.java)
        i.putExtra(PlayerActivity.EXTRA_WATCH_URL, ep.url)
        i.putExtra(PlayerActivity.EXTRA_TITLE, d.movie.title)
        i.putExtra(PlayerActivity.EXTRA_EPISODE, ep.name)
        // Cung 1 tap o cac ban chieu khac (Phu de / Thuyet Minh / Long
        // tieng...): gui kem de nut Doi ban trong player chuyen duoc
        // thang sang ban chieu khac cua dung tap nay
        val versionNames = ArrayList<String>()
        val versionUrls = ArrayList<String>()
        for (group in d.servers) {
            val same = group.episodes.firstOrNull { it.name == ep.name } ?: continue
            versionNames.add(group.name)
            versionUrls.add(same.url)
        }
        i.putStringArrayListExtra(PlayerActivity.EXTRA_SERVER_NAMES, versionNames)
        i.putStringArrayListExtra(PlayerActivity.EXTRA_SERVER_URLS, versionUrls)
        startActivity(i)
    }
}
