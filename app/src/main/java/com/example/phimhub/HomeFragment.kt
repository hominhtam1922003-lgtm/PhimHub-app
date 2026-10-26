package com.example.phimhub

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlin.concurrent.thread

/**
 * Trang chu (ban 2.0 - nguon API): cac MUC dung nhu tren web
 * ("Phim Song Ngu", "Phim Trung Quoc moi", "Phim US-UK moi"...) lay
 * tu /lists/homepageLists, hien dang tieu de muc + luoi phim 3 cot.
 * O tim kiem go den dau tim den do bang API (tham so keyword), ket
 * qua co phan trang "Tai them phim".
 */
class HomeFragment : Fragment() {

    private sealed class HomeItem {
        data class Header(val name: String) : HomeItem()
        data class MovieItem(val movie: Movie) : HomeItem()
    }

    private inner class HomeAdapter(
        private var items: List<HomeItem>
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        // ViewHolder co cache view: truoc day moi lan bind deu
        // findViewById 4 lan + dung holder vo danh -> cuon danh sach
        // dai rat de giat
        inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
            val tv: TextView = v as TextView
        }

        inner class MovieVH(v: View) : RecyclerView.ViewHolder(v) {
            val iv: ImageView = v.findViewById(R.id.ivCover)
            val tvTitle: TextView = v.findViewById(R.id.tvTitle)
            val tvSub: TextView = v.findViewById(R.id.tvLatest)
            val tvBadge: TextView = v.findViewById(R.id.tvSourceBadge)
        }

        override fun getItemViewType(position: Int): Int =
            if (items[position] is HomeItem.Header) 0 else 1

        override fun onCreateViewHolder(
            parent: ViewGroup, viewType: Int
        ): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                HeaderVH(inf.inflate(R.layout.item_section_header, parent, false))
            } else {
                MovieVH(inf.inflate(R.layout.item_comic_grid, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is HomeItem.Header -> {
                    (holder as HeaderVH).tv.text = item.name
                }
                is HomeItem.MovieItem -> {
                    val h = holder as MovieVH
                    val m = item.movie
                    h.tvTitle.text = m.title
                    h.tvSub.text = m.sub
                    bindSourceBadge(h.tvBadge, m.url)
                    ImageLoader.cover(h.iv, m.poster)
                    holder.itemView.setOnClickListener { openDetail(m) }
                }
            }
        }

        override fun getItemCount(): Int = items.size

        fun submit(list: List<HomeItem>) {
            items = list
            notifyDataSetChanged()
        }

        /** Noi them ket qua tim kiem (tai them): chi chen phan moi. */
        fun appendItems(more: List<HomeItem>) {
            if (more.isEmpty()) return
            val start = items.size
            items = items + more
            notifyItemRangeInserted(start, more.size)
        }
    }

    private lateinit var adapter: HomeAdapter
    private var homeItems: List<HomeItem> = emptyList()
    private val searchMovies = ArrayList<Movie>()
    private var loading = false

    // Tim kiem truc tiep khi go: dem thoi gian + so the he chong ket qua cu
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null
    private var searchGeneration = 0
    private var searching = false
    private var searchPage = 0
    private var searchTotalPages = 1
    private var loadingMore = false

    private lateinit var progress: ProgressBar
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var etSearch: EditText
    private lateinit var btnLoadMore: Button
    private lateinit var tvLoadError: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_home, container, false)
        progress = v.findViewById(R.id.progress)
        swipe = v.findViewById(R.id.swipeRefresh)
        etSearch = v.findViewById(R.id.etSearch)
        btnLoadMore = v.findViewById(R.id.btnLoadMore)
        btnLoadMore.text = "Tải thêm phim"
        btnLoadMore.visibility = View.GONE
        btnLoadMore.setOnClickListener { loadMoreSearch() }
        v.findViewById<View>(R.id.btnVerify).visibility = View.GONE
        tvLoadError = v.findViewById(R.id.tvLoadError)

        val rv = v.findViewById<RecyclerView>(R.id.rvComics)
        val glm = GridLayoutManager(requireContext(), 3)
        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (adapter.getItemViewType(position) == 0) 3 else 1
        }
        rv.layoutManager = glm
        rv.setHasFixedSize(true)
        rv.setItemViewCacheSize(12)
        adapter = HomeAdapter(emptyList())
        rv.adapter = adapter

        swipe.setOnRefreshListener {
            if (searching) runSearch(etSearch.text.toString().trim())
            else loadHome()
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                if (q.isEmpty()) {
                    // Xoa o tim kiem -> quay lai cac muc trang chu
                    searchGeneration++
                    searching = false
                    btnLoadMore.visibility = View.GONE
                    adapter.submit(homeItems)
                    return
                }
                val r = Runnable { runSearch(q) }
                searchRunnable = r
                searchHandler.postDelayed(r, 450)
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val q = etSearch.text.toString().trim()
                if (q.isNotEmpty()) {
                    searchRunnable?.let { searchHandler.removeCallbacks(it) }
                    runSearch(q)
                }
                true
            } else {
                false
            }
        }

        loadHome()
        return v
    }

    private fun openDetail(movie: Movie) {
        val i = Intent(requireContext(), DetailActivity::class.java)
        i.putExtra(DetailActivity.EXTRA_URL, CobeSource.rebase(movie.url))
        i.putExtra(DetailActivity.EXTRA_TITLE, movie.title)
        i.putExtra(DetailActivity.EXTRA_POSTER, movie.poster)
        startActivity(i)
    }

    private fun loadHome() {
        if (loading) return
        loading = true
        if (!swipe.isRefreshing) progress.visibility = View.VISIBLE
        thread {
            RemoteConfig.awaitReady(2500)
            var sections: List<HomeSection> = emptyList()
            var hot: List<Movie> = emptyList()
            try {
                sections = Sources.homeSectionsMerged()
            } catch (e: Exception) {
                // Thu duong du phong phim hot o duoi
            }
            if (sections.isEmpty()) {
                try {
                    hot = CobeSource.fetchHot()
                } catch (e: Exception) {
                    // Bao loi o duoi
                }
            }
            activity?.runOnUiThread {
                loading = false
                swipe.isRefreshing = false
                progress.visibility = View.GONE
                val items = ArrayList<HomeItem>()
                if (sections.isNotEmpty()) {
                    for (s in sections) {
                        items.add(HomeItem.Header(s.name))
                        s.movies.forEach { items.add(HomeItem.MovieItem(it)) }
                    }
                } else if (hot.isNotEmpty()) {
                    items.add(HomeItem.Header("Phim hot"))
                    hot.forEach { items.add(HomeItem.MovieItem(it)) }
                }
                if (items.isNotEmpty()) {
                    tvLoadError.visibility = View.GONE
                    homeItems = items
                    if (!searching) adapter.submit(items)
                } else if (homeItems.isEmpty()) {
                    tvLoadError.visibility = View.VISIBLE
                    tvLoadError.text =
                        "Chưa tải được phim. Kiểm tra kết nối mạng rồi vuốt xuống để thử lại nhé."
                }
            }
        }
    }

    /** Tim kiem theo tu khoa qua API (tham so keyword), trang 1. */
    private fun runSearch(keyword: String) {
        val gen = ++searchGeneration
        searching = true
        searchPage = 1
        searchTotalPages = 1
        progress.visibility = View.VISIBLE
        btnLoadMore.visibility = View.GONE
        thread {
            RemoteConfig.awaitReady(1500)
            var page: SearchPage? = null
            try {
                page = Sources.searchMerged(keyword, 1)
            } catch (e: Exception) {
                // Bao rong o duoi
            }
            activity?.runOnUiThread {
                if (gen != searchGeneration) return@runOnUiThread
                progress.visibility = View.GONE
                swipe.isRefreshing = false
                searchMovies.clear()
                if (page != null && page.movies.isNotEmpty()) {
                    searchMovies.addAll(page.movies)
                    searchTotalPages = page.totalPages
                    adapter.submit(searchMovies.map { HomeItem.MovieItem(it) })
                    updateSearchMoreVisibility()
                } else {
                    adapter.submit(emptyList())
                    Toast.makeText(
                        requireContext(), "Không tìm thấy phim phù hợp",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun updateSearchMoreVisibility() {
        btnLoadMore.visibility =
            if (searching && searchPage < searchTotalPages && searchMovies.isNotEmpty())
                View.VISIBLE else View.GONE
        btnLoadMore.isEnabled = !loadingMore
    }

    private fun loadMoreSearch() {
        if (!searching || loadingMore || searchPage >= searchTotalPages) return
        val keyword = etSearch.text.toString().trim()
        if (keyword.isEmpty()) return
        val gen = searchGeneration
        loadingMore = true
        updateSearchMoreVisibility()
        val next = searchPage + 1
        thread {
            var page: SearchPage? = null
            try {
                page = Sources.searchMerged(keyword, next)
            } catch (e: Exception) {
                // Giu nguyen danh sach hien tai
            }
            activity?.runOnUiThread {
                loadingMore = false
                if (gen != searchGeneration) return@runOnUiThread
                if (page != null && page.movies.isNotEmpty()) {
                    val known = searchMovies.map { it.url }.toHashSet()
                    val added = page.movies.filter { known.add(it.url) }
                    searchMovies.addAll(added)
                    if (added.isNotEmpty() ||
                        page.movies.size >= KkSource.PAGE_SIZE
                    ) {
                        searchPage = next
                        searchTotalPages = page.totalPages
                    } else {
                        searchTotalPages = searchPage
                    }
                    if (added.isNotEmpty()) {
                        adapter.appendItems(added.map { HomeItem.MovieItem(it) })
                    }
                } else {
                    searchTotalPages = searchPage
                    Toast.makeText(
                        requireContext(), "Đã tải hết kết quả", Toast.LENGTH_SHORT
                    ).show()
                }
                updateSearchMoreVisibility()
            }
        }
    }
}
