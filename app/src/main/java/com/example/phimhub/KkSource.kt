package com.example.phimhub

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Nguon du lieu 2: KKPhim — API JSON cong khai tai phimapi.com.
 *
 * KET QUA KIEM CHUNG TRUC TIEP (26/10/2026, curl tu may nay):
 *   - GET /danh-sach/{phim-moi-cap-nhat|phim-le|phim-bo|hoat-hinh|tv-shows}?page=N
 *       -> {items: [...], pagination: {totalItems, totalItemsPerPage: 24,
 *          currentPage, totalPages}} — SO LIEU THAT (khac cobephim).
 *       Tong kho 30.423 (le 17.198 / bo 9.224 / hoat hinh 3.624 / TV 377).
 *       Item chi co: name, origin_name, slug, poster_url, thumb_url,
 *       year, tmdb, imdb — KHONG co episode_current/quality o danh sach.
 *   - GET /v1/api/tim-kiem?keyword=&page=&limit=24
 *       -> {data: {items: [...], params: {pagination: {...}}}};
 *       tim duoc ca co dau lan khong dau.
 *   - GET /phim/{slug}
 *       -> {movie: {name, origin_name, content (HTML), actor[], director[],
 *          category[{name}], country[{name}], episode_current "Tập 6",
 *          episode_total 12, quality, lang, type series|single, time,
 *          year, poster_url (URL tuyet doi, host phimimg.com)},
 *          episodes: [{server_name, server_data: [{name "Tập 01", slug,
 *          link_m3u8, link_embed}]}]}
 *       Phim le: 1 tap ten "Full". Link phat la m3u8 TRUC TIEP
 *       (host a.kvp726.com / kkphimplayer) — tu IP ngoai VN tra 404
 *       (nghi CDN chi mo IP VN); player co duong lui qua ban embed
 *       player.phimapi.com va qua ban chieu cobephim cua cung tap.
 *
 * URL dinh danh phim cua nguon nay dung tien to gia "kk://phim/{slug}"
 * de DbRepo/Detail phan biet nguon ma khong doi model dung chung.
 */
object KkSource {

    const val DEFAULT_API_BASE = "https://phimapi.com"
    const val PAGE_SIZE = 24

    /** Goc API dang dung (RemoteConfig cap nhat khi API doi cho). */
    @Volatile
    var activeApiBase: String = DEFAULT_API_BASE

    val API_BASE: String
        get() = activeApiBase

    fun detailUrl(slug: String): String = "kk://phim/$slug"

    fun isKkUrl(url: String): Boolean = url.startsWith("kk://")

    fun slugOf(detailUrl: String): String =
        detailUrl.substringAfter("/phim/").substringBefore("?").trimEnd('/')

    // ---------------- HTTP JSON ----------------

    private fun getText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 15000
            setRequestProperty("User-Agent", CobeSource.UA)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Language", "vi-VN,vi;q=0.9")
            // JSON nen gzip rat tot (API tra Content-Encoding: gzip —
            // da kiem chung truc tiep): giam ~60-80% dung luong tai
            setRequestProperty("Accept-Encoding", "gzip")
        }
        val code = conn.responseCode
        val raw = if (code in 200..299) conn.inputStream else conn.errorStream
        val stream = if (raw != null &&
            conn.getHeaderField("Content-Encoding")?.contains("gzip", true) == true
        ) {
            java.util.zip.GZIPInputStream(raw)
        } else {
            raw
        }
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        if (code !in 200..299) throw Exception("HTTP $code")
        return text
        // Khong disconnect(): doc het body + dong stream la du dieu kien
        // de HttpURLConnection giu ket noi trong pool tai su dung (keep-alive),
        // cac request sau khoi phai bat tay TLS lai
    }

    private fun getJson(path: String): JSONObject = JSONObject(getText(API_BASE + path))

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    // ---------------- Anh xa phim ----------------

    fun movieFromJson(o: JSONObject): Movie {
        val slug = o.optString("slug")
        val title = o.optString("name").ifBlank { o.optString("origin_name") }
        var poster = o.optString("poster_url")
        if (poster.isBlank()) poster = o.optString("thumb_url")
        val parts = ArrayList<String>()
        // Danh sach loai khong co 2 truong nay (chi tim kiem/chi tiet
        // moi co) — co thi hien, khong co thi bo qua
        o.optString("quality").takeIf { it.isNotBlank() }?.let { parts.add(it) }
        o.optString("episode_current").trim().takeIf { it.isNotEmpty() }
            ?.let { parts.add(it) }
        val year = o.optInt("year", 0)
        if (year > 0) parts.add(year.toString())
        return Movie(title, detailUrl(slug), poster, parts.joinToString(" • "))
    }

    private fun moviesFromArray(arr: JSONArray?): List<Movie> {
        if (arr == null) return emptyList()
        val out = ArrayList<Movie>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("slug").isBlank()) continue
            val m = movieFromJson(o)
            if (m.title.isNotEmpty()) out.add(m)
        }
        return out
    }

    private fun pageFromListJson(o: JSONObject, page: Int): SearchPage {
        val movies = moviesFromArray(o.optJSONArray("items"))
        val pg = o.optJSONObject("pagination")
        val totalPages = pg?.optInt("totalPages", 0) ?: 0
        val total = pg?.optInt("totalItems", 0) ?: 0
        return SearchPage(
            movies = movies,
            totalPages = if (totalPages > 0) totalPages else page,
            total = total
        )
    }

    // ---------------- Danh sach / tim kiem ----------------

    /** 1 trang cua 1 danh sach loai (phim-le, phim-bo, hoat-hinh...). */
    fun fetchList(typeSlug: String, page: Int): SearchPage =
        pageFromListJson(getJson("/danh-sach/$typeSlug?page=$page"), page)

    fun searchKeyword(keyword: String, page: Int): SearchPage {
        val o = getJson(
            "/v1/api/tim-kiem?keyword=${enc(keyword)}&page=$page&limit=$PAGE_SIZE"
        )
        val data = o.optJSONObject("data") ?: return SearchPage(emptyList(), page, 0)
        val movies = moviesFromArray(data.optJSONArray("items"))
        val pg = data.optJSONObject("params")?.optJSONObject("pagination")
        val totalPages = pg?.optInt("totalPages", 0) ?: 0
        val total = pg?.optInt("totalItems", 0) ?: 0
        return SearchPage(
            movies = movies,
            totalPages = if (totalPages > 0) totalPages else page,
            total = total
        )
    }

    /** Cac muc trang chu cua nguon KKPhim (moi muc cat 12 phim dau). */
    fun fetchHomeSections(): List<HomeSection> {
        val defs = listOf(
            "phim-moi-cap-nhat" to "Mới cập nhật",
            "phim-le" to "Phim lẻ mới",
            "phim-bo" to "Phim bộ mới",
            "hoat-hinh" to "Hoạt hình"
        )
        // 4 danh sach tai SONG SONG (giu nguyen thu tu muc khi ghep):
        // truoc day tai tuan tu nen trang chu cho ca 4 luot lien tiep
        val slots = arrayOfNulls<HomeSection>(defs.size)
        val threads = defs.mapIndexed { i, (slug, name) ->
            kotlin.concurrent.thread {
                try {
                    val page = fetchList(slug, 1)
                    if (page.movies.isNotEmpty()) {
                        slots[i] = HomeSection(name, page.movies.take(12))
                    }
                } catch (e: Exception) {
                    // Bo qua muc loi, giu cac muc con lai
                }
            }
        }
        threads.forEach { it.join() }
        return slots.filterNotNull()
    }

    // ---------------- Chi tiet + danh sach tap ----------------

    fun fetchDetail(detailUrl: String): MovieDetail {
        val slug = slugOf(detailUrl)
        val o = getJson("/phim/$slug")
        val m = o.optJSONObject("movie")
            ?: throw Exception("API khong tra ve phim $slug")
        val movie = movieFromJson(m)

        val meta = ArrayList<String>()
        val origin = m.optString("origin_name")
        if (origin.isNotBlank() && origin != movie.title) meta.add("Tên gốc: $origin")
        val year = m.optInt("year", 0)
        if (year > 0) meta.add("Năm phát hành: $year")
        m.optString("quality").takeIf { it.isNotBlank() }
            ?.let { meta.add("Chất lượng: $it") }
        m.optString("lang").takeIf { it.isNotBlank() }
            ?.let { meta.add("Ngôn ngữ: $it") }
        m.optString("time").takeIf { it.isNotBlank() }
            ?.let { meta.add("Thời lượng: $it") }
        if (m.optString("type") == "series") {
            // episode_current co the la "Tập 6" hoac "Hoàn Tất (76/76)":
            // so tap hien tai la SO CUOI CUNG tim thay trong chuoi
            val nums = Regex("""\d+""").findAll(m.optString("episode_current"))
                .map { it.value }.toList()
            val cur = nums.lastOrNull() ?: ""
            val tot = m.optInt("episode_total", 0)
            if (tot > 0) meta.add("Số tập: $cur/$tot")
        }
        val tmdb = m.optJSONObject("tmdb")
        val vote = tmdb?.optDouble("vote_average", 0.0) ?: 0.0
        if (vote > 0) meta.add("Điểm TMDB: $vote")
        val cats = namesOf(m.optJSONArray("category"))
        if (cats.isNotEmpty()) meta.add("Thể loại: ${cats.joinToString(", ")}")
        val countries = namesOf(m.optJSONArray("country"))
        if (countries.isNotEmpty()) meta.add("Quốc gia: ${countries.joinToString(", ")}")
        val dirs = stringsOf(m.optJSONArray("director"))
        if (dirs.isNotEmpty()) meta.add("Đạo diễn: ${dirs.joinToString(", ")}")
        val acts = stringsOf(m.optJSONArray("actor"))
        if (acts.isNotEmpty()) meta.add("Diễn viên: ${acts.take(8).joinToString(", ")}")

        val descHtml = m.optString("content")
        val description = if (descHtml.isBlank()) "" else try {
            org.jsoup.Jsoup.parse(descHtml).text()
        } catch (e: Exception) {
            descHtml.replace(Regex("<[^>]+>"), " ").trim()
        }

        val servers = ArrayList<ServerGroup>()
        val epsArr = o.optJSONArray("episodes")
        if (epsArr != null) {
            for (i in 0 until epsArr.length()) {
                val s = epsArr.optJSONObject(i) ?: continue
                val serverName = s.optString("server_name").ifBlank { "Mặc định" }
                val data = s.optJSONArray("server_data") ?: continue
                val seen = LinkedHashMap<String, Episode>()
                for (j in 0 until data.length()) {
                    val e = data.optJSONObject(j) ?: continue
                    val raw = e.optString("name").trim()
                    if (raw.isEmpty()) continue
                    val label = normalizeEpName(raw)
                    // Uu tien m3u8 thang; thieu thi dung trang embed
                    val link = e.optString("link_m3u8").ifBlank { e.optString("link_embed") }
                    if (link.isBlank()) continue
                    if (!seen.containsKey(label)) seen[label] = Episode(label, link)
                }
                if (seen.isNotEmpty()) {
                    val sorted = seen.values.sortedWith(
                        compareBy({ epNum(it.name) ?: Double.MAX_VALUE }, { it.name })
                    )
                    servers.add(ServerGroup(serverName, sorted))
                }
            }
        }
        return MovieDetail(
            movie = movie,
            meta = meta.joinToString("\n"),
            description = description,
            servers = servers
        )
    }

    /**
     * Chuan hoa ten tap ve cung dang voi nguon cobephim de man chi
     * tiet khop duoc cung 1 tap giua 2 nguon ("Tập 01" -> "Tập 1").
     */
    fun normalizeEpName(raw: String): String = when {
        raw.equals("Full", true) -> "Full"
        else -> {
            val m = Regex("""(?i)tập\s*(\d+(?:\.\d+)?)""").find(raw)
            if (m != null) {
                val n = m.groupValues[1]
                val asInt = n.toDoubleOrNull()
                if (asInt != null && asInt == Math.floor(asInt)) "Tập ${asInt.toInt()}"
                else "Tập $n"
            } else if (raw.matches(Regex("""\d+(\.\d+)?"""))) {
                val asInt = raw.toDoubleOrNull()
                if (asInt != null && asInt == Math.floor(asInt)) "Tập ${asInt.toInt()}"
                else "Tập $raw"
            } else raw
        }
    }

    private fun epNum(name: String): Double? =
        Regex("""[\d]+(?:\.[\d]+)?""").find(name)?.value?.toDoubleOrNull()

    private fun namesOf(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val v = arr.opt(i)
            val name = when (v) {
                is JSONObject -> v.optString("name")
                is String -> v
                else -> ""
            }
            if (name.isNotBlank()) out.add(name)
        }
        return out
    }

    private fun stringsOf(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val v = arr.optString(i).trim()
            if (v.isNotEmpty()) out.add(v)
        }
        return out
    }
}
