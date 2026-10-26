package com.example.phimhub

import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.request.target.Target

/**
 * Tai anh tu CDN cua nguon (i.hinhhinh.com / i*.truyenvua.com).
 *
 * CDN nay chan tai anh "tran" (khong co Referer / User-Agent cua trinh duyet)
 * nen Glide mac dinh bi loi 403 -> anh bia va anh trang truyen deu hong.
 * Moi lan tai anh deu gan header nhu trinh duyet that dang xem tren web nguon.
 */
object ImageLoader {


    private fun glideUrl(raw: String): GlideUrl {
        return GlideUrl(
            raw,
            LazyHeaders.Builder()
                .addHeader("Referer", CobeSource.BASE + "/")
                .addHeader("User-Agent", CobeSource.UA)
                .addHeader("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
                .build()
        )
    }

    /**
     * Anh bia trong luoi/danh sach: chi tai dung co o hien thi
     * (toi da 480x720) thay vi anh goc full-size — nhe mang + giai
     * ma nhanh hon nhieu khi cuon danh sach dai.
     */
    fun cover(iv: ImageView, url: String) {
        if (url.isEmpty()) {
            iv.setImageResource(R.drawable.bg_poster)
            return
        }
        Glide.with(iv).load(glideUrl(url))
            .override(480, 720)
            .placeholder(R.drawable.bg_poster)
            .error(R.drawable.bg_poster)
            .centerCrop()
            .into(iv)
    }

    /** Anh nen lon o dau trang chi tiet (rong toan man hinh). */
    fun backdrop(iv: ImageView, url: String) {
        if (url.isEmpty()) {
            iv.setImageResource(R.drawable.bg_poster)
            return
        }
        Glide.with(iv).load(glideUrl(url))
            .override(1080, 720)
            .placeholder(R.drawable.bg_poster)
            .error(R.drawable.bg_poster)
            .centerCrop()
            .into(iv)
    }

    fun page(iv: ImageView, url: String) {
        Glide.with(iv).load(glideUrl(url))
            .override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
            .placeholder(android.R.drawable.ic_menu_gallery)
            .error(android.R.drawable.ic_menu_report_image)
            .into(iv)
    }
}
