package com.example.phimhub

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Phan loai BAN CHIEU: luong thang (.m3u8/.mp4) hay trang embed chet
 * (streamc — player web bao "Setup error"/102630, khong co luong that).
 *
 * Trang embed chinh thuc cua cobephim chua san truong encrypted_url;
 * giai ma hex-XOR ngay trong app la biet dich cua ban do. Ket qua
 * duoc CACHE dung chung: man chi tiet "lam am" cache ngay khi mo
 * phim (nguoi dung dang doc gioi thieu thi viec phan loai da xong),
 * vao player la quyet dinh duoc ngay, khoi cho doi lai.
 */
object VersionCheck {

    private val cache = ConcurrentHashMap<String, Boolean>()

    fun cached(url: String): Boolean? = cache[url]

    /** Phan loai 1 URL (doc cache truoc; chua co thi tai trang embed). */
    fun classify(url: String): Boolean? {
        cache[url]?.let { return it }
        val r = classifyNow(url)
        if (r != null) cache[url] = r
        return r
    }

    /** Lam am cache cho nhieu URL (chay nen, khong cho ket qua). */
    fun warm(urls: Collection<String>) {
        for (u in urls) {
            if (cache.containsKey(u)) continue
            thread { classify(u) }
        }
    }

    /** true = phat duoc, false = chet o nguon, null = khong chac. */
    private fun classifyNow(u: String): Boolean? {
        val p = u.substringBefore("?").lowercase()
        if (p.endsWith(".m3u8") || p.endsWith(".mp4")) {
            // Luong thang: THU TAI THAT bang dung thong so app se
            // phat. Host KKPhim (a.kvp726.com) tra 404 cho ca file
            // da chet lan IP bi chan — tu may nguoi dung, 404 nghia
            // la app phat cung se loi, phai danh dau chet de chuyen
            // ban khac ngay tu dau thay vi thu phat roi bao loi.
            // Loi mang / ma khac (403...) -> khong ket luan.
            return probeDirect(u)
        }
        if (!u.contains("/embed")) return null
        return try {
            val html = httpGetText(u) ?: return null
            val m = Regex("\"encrypted_url\"\\s*:\\s*\"([0-9a-fA-F]+)\"").find(html)
                ?: return null
            val plain = xorDecryptHex(m.groupValues[1], "mySecretKey2024")
            when {
                plain.contains(".m3u8") || plain.contains(".mp4") -> true
                plain.contains("streamc") || plain.contains("/embed.php") -> false
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Thu tai 1 luong thang: chi lay ma trang thai, khong doc than. */
    private fun probeDirect(u: String): Boolean? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(u).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 6000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", CobeSource.UA)
                setRequestProperty("Referer", CobeSource.BASE + "/")
            }
            when (conn.responseCode) {
                in 200..399 -> true
                404, 410 -> false
                else -> null
            }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Giai ma hex-XOR dung khoa web dang dung cho encrypted_url. */
    private fun xorDecryptHex(hex: String, key: String): String {
        val kb = key.toByteArray(Charsets.UTF_8)
        val bytes = ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        val out = ByteArray(bytes.size) { i ->
            (bytes[i].toInt() xor kb[i % kb.size].toInt()).toByte()
        }
        return String(out, Charsets.UTF_8)
    }

    private fun httpGetText(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 6000
                setRequestProperty("User-Agent", CobeSource.UA)
                setRequestProperty("Accept", "text/html,application/json,*/*")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
