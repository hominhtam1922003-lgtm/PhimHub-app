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
        try {
            addAll(KkSource.fetchHomeSections().map { HomeSection("KKPhim • " + it.name, it.movies) })
        } catch (e: Exception) {
            // Nguon KK loi: con cobephim o duoi
        }
        try {
            addAll(CobeSource.fetchHomeSections())
        } catch (e: Exception) {
            // Giu cac muc da co
        }
        return out
    }

    // ---------------- Tim kiem ----------------

    fun searchMerged(keyword: String, page: Int): SearchPage {
        var kk: SearchPage? = null
        var cobe: SearchPage? = null
        try {
            kk = KkSource.searchKeyword(keyword, page)
        } catch (e: Exception) {
            // Nguon KK loi trang nay: dung ket qua cobe
        }
        try {
            cobe = CobeSource.searchKeyword(keyword, page)
        } catch (e: Exception) {
            // Nguon cobe loi trang nay: dung ket qua KK
        }
        if (kk == null && cobe == null) throw Exception("Ca 2 nguon deu loi")
        val merged = ArrayList<Movie>()
        val seen = HashSet<String>()
        kk?.movies?.forEach { if (seen.add(normalizeTitle(it.title))) merged.add(it) }
        cobe?.movies?.forEach { if (seen.add(normalizeTitle(it.title))) merged.add(it) }
        val totalPages = maxOf(kk?.totalPages ?: page, cobe?.totalPages ?: page)
        val total = (kk?.total ?: 0) + (cobe?.total ?: 0)
        return SearchPage(merged, totalPages, total)
    }

    // ---------------- Chi tiet gop 2 nguon ----------------

    /**
     * Tai chi tiet o nguon cua [url]; neu thanh cong, tim ban sinh
     * doi o nguon con lai (theo tua) va ghep nhom server cua ca hai.
     * Nguon chinh loi thi thu dung ban sinh doi lam chinh (can
     * [fallbackTitle] tu the phim de tim).
     */
    fun fetchDetailMerged(url: String, fallbackTitle: String = ""): MovieDetail {
        val primaryIsKk = isKk(url)
        var primary: MovieDetail? = null
        try {
            primary = if (primaryIsKk) KkSource.fetchDetail(url) else CobeSource.fetchDetail(url)
        } catch (e: Exception) {
            // Thu cuu bang nguon con lai o duoi
        }
        val titleForTwin = primary?.movie?.title ?: fallbackTitle
        var twin: MovieDetail? = null
        if (titleForTwin.isNotBlank()) {
            try {
                twin = findTwin(titleForTwin, primaryIsKk)
            } catch (e: Exception) {
                // Khong co ban sinh doi: dung 1 nguon
            }
        }
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
