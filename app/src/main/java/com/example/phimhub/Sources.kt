package com.example.phimhub

import java.text.Normalizer

/**
 * Lop gop 2 nguon phim (KKPhim + cobephim) — trai tim cua app da nguon:
 * - Trang chu: muc cua ca 2 nguon, trung tua thi chi giu 1 the
 *   (tua chuan hoa bo dau) — nguon KKPhim xep truoc (cap nhat nhanh).
 * - Tim kiem: chay ca 2 nguon, gop ket qua, trung tua giu ban KKPhim.
 * - Chi tiet: tai phim o nguon chinh roi TIM BAN SINH DOI o nguon
 *   kia bang chinh tua phim; cac nhom server cua ca 2 nguon duoc
 *   ghep chung 1 man hinh, moi nhom gan nhan nguon ("KK · Vietsub",
 *   "Cobe · Song ngữ"...) — nho vay man phat co san cac ban chieu
 *   xuyen nguon cua cung 1 tap de tu nhay khi 1 nguon loi.
 */
object Sources {

    fun isKk(url: String): Boolean = KkSource.isKkUrl(url)

    /**
     * Chay 1 viec mang tren thread rieng va tra ket qua qua [join]
     * (null neu viec do loi — dung de tai 2 nguon SONG SONG thay vi
     * tuan tu: thoi gian cho bang luot cham nhat thay vi tong ca hai).
     */
    private class Fork<T>(block: () -> T) {
        @Volatile
        private var value: T? = null
        private val t: Thread = kotlin.concurrent.thread {
            value = try {
                block()
            } catch (e: Exception) {
                null
            }
        }

        fun join(): T? {
            t.join()
            return value
        }
    }

    private fun <T> fork(block: () -> T): Fork<T> = Fork(block)

    /** Chuan hoa tua de so trung: bo dau, thuong, gon khoang trang. */
    fun normalizeTitle(t: String): String {
        var s = t.lowercase().replace('đ', 'd')
        s = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        s = s.replace(Regex("[^a-z0-9]+"), " ").trim()
        return s.replace(Regex("\\s+"), " ")
    }

    // ---------------- Trang chu ----------------

    fun homeSectionsMerged(): List<HomeSection> {
        val out = ArrayList<HomeSection>()
        val seen = HashSet<String>()
        fun addAll(sections: List<HomeSection>) {
            for (s in sections) {
                val fresh = s.movies.filter { seen.add(normalizeTitle(it.title)) }
                if (fresh.isNotEmpty()) out.add(HomeSection(s.name, fresh))
            }
        }
        // 2 nguon tai song song; thu tu ghep van KKPhim truoc nhu cu
        val kkFork = fork {
            KkSource.fetchHomeSections().map { HomeSection("KKPhim • " + it.name, it.movies) }
        }
        val cobeFork = fork { CobeSource.fetchHomeSections() }
        kkFork.join()?.let { addAll(it) }
        cobeFork.join()?.let { addAll(it) }
        return out
    }

    // ---------------- Tim kiem ----------------

    fun searchMerged(keyword: String, page: Int): SearchPage {
        // 2 nguon tim song song
        val kkFork = fork { KkSource.searchKeyword(keyword, page) }
        val cobeFork = fork { CobeSource.searchKeyword(keyword, page) }
        val kk = kkFork.join()
        val cobe = cobeFork.join()
        if (kk == null && cobe == null) throw Exception("Ca 2 nguon deu loi")
        val merged = ArrayList<Movie>()
        val seen = HashSet<String>()
        kk?.movies?.forEach { if (seen.add(normalizeTitle(it.title))) merged.add(it) }
        cobe?.movies?.forEach { if (seen.add(normalizeTitle(it.title))) merged.add(it) }
        val totalPages = maxOf(kk?.totalPages ?: page, cobe?.totalPages ?: page)
        val total = (kk?.total ?: 0) + (cobe?.total ?: 0)
        return SearchPage(merged, totalPages, total)
    }

    // ---------------- Duyet theo muc (gop 2 nguon) ----------------

    // Cache trang 1 cua cac muc duyet (3 phut): vao tab Phim le/bo/
    // Chieu rap lan dau da co san du lieu (trang chu tai truoc ngam),
    // khoi cho mang. Chi cache trang 1; cac trang sau luon tai tuoi.
    private data class CachedPage(val at: Long, val page: SearchPage)

    private val browseCache = java.util.concurrent.ConcurrentHashMap<String, CachedPage>()
    private const val BROWSE_CACHE_TTL_MS = 180_000L

    private fun browseKey(kkType: String, cobeParams: Map<String, String>, page: Int): String =
        kkType + "|" + cobeParams.entries.sortedBy { it.key }
            .joinToString("&") { "${it.key}=${it.value}" } + "|" + page

    /** Tai truoc trang 1 cua 1 muc duyet (chay nen tu trang chu). */
    fun prefetchBrowse(kkType: String, cobeParams: Map<String, String>) {
        try {
            browseMerged(kkType, cobeParams, 1)
        } catch (e: Exception) {
            // Tai truoc loi thi vao tab tai lai nhu cu
        }
    }

    /**
     * 1 trang duyet phim gop ca 2 nguon cho cac tab Phim le / Phim bo /
     * Chieu rap: KKPhim theo danh sach loai [kkType], cobephim theo
     * [cobeParams]; trung tua (chuan hoa bo dau) chi giu 1 the, KKPhim
     * xep truoc. 1 nguon loi trang nay thi nguon kia van tra ket qua.
     */
    fun browseMerged(kkType: String, cobeParams: Map<String, String>, page: Int): SearchPage {
        if (page == 1) {
            val hit = browseCache[browseKey(kkType, cobeParams, page)]
            if (hit != null &&
                System.currentTimeMillis() - hit.at < BROWSE_CACHE_TTL_MS
            ) {
                return hit.page
            }
        }
        // 2 nguon tai song song
        val kkFork = fork { KkSource.fetchList(kkType, page) }
        val cobeFork = fork { CobeSource.searchMovies(cobeParams, page) }
        val kk = kkFork.join()
        val cobe = cobeFork.join()
        if (kk == null && cobe == null) throw Exception("Ca 2 nguon deu loi")
        val merged = ArrayList<Movie>()
        val seen = HashSet<String>()
        kk?.movies?.forEach { if (seen.add(normalizeTitle(it.title))) merged.add(it) }
        cobe?.movies?.forEach { if (seen.add(normalizeTitle(it.title))) merged.add(it) }
        val totalPages = maxOf(kk?.totalPages ?: page, cobe?.totalPages ?: page)
        val total = (kk?.total ?: 0) + (cobe?.total ?: 0)
        val result = SearchPage(merged, totalPages, total)
        if (page == 1) {
            browseCache[browseKey(kkType, cobeParams, page)] =
                CachedPage(System.currentTimeMillis(), result)
        }
        return result
    }

    // ---------------- Chi tiet gop 2 nguon ----------------

    /** Chi tiet o NGUON CHINH cua [url] (chua ghep ban sinh doi). */
    fun fetchPrimary(url: String): MovieDetail =
        if (isKk(url)) KkSource.fetchDetail(url) else CobeSource.fetchDetail(url)

    /** Ban sinh doi o nguon con lai (tim bang tua); null neu khong co. */
    fun fetchTwin(title: String, primaryIsKk: Boolean): MovieDetail? =
        findTwin(title, primaryIsKk)

    /** Ghep chi tiet nguon chinh + ban sinh doi thanh 1 man chi tiet. */
    fun mergeDetails(
        primary: MovieDetail?, primaryIsKk: Boolean, twin: MovieDetail?
    ): MovieDetail {
        val base = primary ?: twin ?: throw Exception("Khong tai duoc chi tiet phim")
        val baseIsKk = if (primary != null) primaryIsKk else !primaryIsKk
        val other = if (primary != null) twin else null
        val groups = ArrayList<ServerGroup>()
        base.servers.forEach { groups.add(ServerGroup(tag(baseIsKk) + it.name, it.episodes)) }
        other?.servers?.forEach { groups.add(ServerGroup(tag(!baseIsKk) + it.name, it.episodes)) }
        val desc = base.description.ifBlank { other?.description ?: "" }
        val meta = base.meta.ifBlank { other?.meta ?: "" }
        return MovieDetail(base.movie, meta, desc, groups)
    }

    /**
     * Tai chi tiet o nguon cua [url]; neu thanh cong, tim ban sinh
     * doi o nguon con lai (theo tua) va ghep nhom server cua ca hai.
     * Nguon chinh loi thi thu dung ban sinh doi lam chinh (can
     * [fallbackTitle] tu the phim de tim).
     */
    fun fetchDetailMerged(url: String, fallbackTitle: String = ""): MovieDetail {
        val primaryIsKk = isKk(url)
        // Chi tiet nguon chinh va viec tim ban sinh doi chay SONG SONG:
        // ban sinh doi tim bang tua tren the phim (chinh la tua cua nguon
        // chinh trong hau het truong hop); neu truot va tua that cua nguon
        // chinh khac tua the thi tim lai bang tua that o duoi
        val primaryFork = fork {
            if (primaryIsKk) KkSource.fetchDetail(url) else CobeSource.fetchDetail(url)
        }
        val twinFork = if (fallbackTitle.isNotBlank()) {
            fork { findTwin(fallbackTitle, primaryIsKk) }
        } else {
            null
        }
        val primary = primaryFork.join()
        var twin = twinFork?.join()
        if (twin == null && primary != null) {
            val realTitle = primary.movie.title
            if (realTitle.isNotBlank() &&
                (twinFork == null || normalizeTitle(realTitle) != normalizeTitle(fallbackTitle))
            ) {
                try {
                    twin = findTwin(realTitle, primaryIsKk)
                } catch (e: Exception) {
                    // Khong co ban sinh doi: dung 1 nguon
                }
            }
        }
        return mergeDetails(primary, primaryIsKk, twin)
    }

    private fun tag(isKk: Boolean): String = if (isKk) "KK · " else "Cobe · "

    /** Tim cung 1 phim o nguon con lai bang tua (chuan hoa bo dau). */
    private fun findTwin(title: String, primaryIsKk: Boolean): MovieDetail? {
        val want = normalizeTitle(title)
        if (want.isEmpty()) return null
        val page = if (primaryIsKk) {
            CobeSource.searchKeyword(title, 1)
        } else {
            KkSource.searchKeyword(title, 1)
        }
        val hit = page.movies.firstOrNull { normalizeTitle(it.title) == want }
            ?: return null
        return if (primaryIsKk) CobeSource.fetchDetail(hit.url) else KkSource.fetchDetail(hit.url)
    }
}
