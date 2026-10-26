package com.example.phimhub

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var tabs: List<TextView>
    private var selectedTab: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dung UA THAT cua may cho moi request: dau van tay nhat quan
        // nhu Chrome that thi Cloudflare moi cho qua man hinh xac minh
        try {
            val realUa = android.webkit.WebSettings.getDefaultUserAgent(applicationContext)
            if (!realUa.isNullOrBlank()) CobeSource.UA = realUa
        } catch (e: Exception) {
            // Giu UA du phong trong CobeSource
        }
        setContentView(R.layout.activity_main)
        // Khong dung thanh tieu de: an action bar mac dinh, noi dung bat dau
        // ngay tu o tim kiem / danh sach.
        // de khoi bi 2 thanh tieu de chong nhau
        supportActionBar?.hide()

        val tabHome = findViewById<TextView>(R.id.tabHome)
        val tabSingle = findViewById<TextView>(R.id.tabSingle)
        val tabSeries = findViewById<TextView>(R.id.tabSeries)
        val tabCinema = findViewById<TextView>(R.id.tabCinema)
        val tabFilter = findViewById<TextView>(R.id.tabFilter)
        val tabFavorites = findViewById<TextView>(R.id.tabFavorites)
        val tabHistory = findViewById<TextView>(R.id.tabHistory)
        tabs = listOf(
            tabHome, tabSingle, tabSeries, tabCinema, tabFilter,
            tabFavorites, tabHistory
        )

        tabHome.setOnClickListener { select(tabHome); show("home") { HomeFragment() } }
        tabSingle.setOnClickListener {
            select(tabSingle)
            show("single") { BrowseFragment.newInstance(BrowseFragment.KIND_SINGLE) }
        }
        tabSeries.setOnClickListener {
            select(tabSeries)
            show("series") { BrowseFragment.newInstance(BrowseFragment.KIND_SERIES) }
        }
        tabCinema.setOnClickListener {
            select(tabCinema)
            show("cinema") { BrowseFragment.newInstance(BrowseFragment.KIND_CINEMA) }
        }
        tabFilter.setOnClickListener { select(tabFilter); show("filter") { FilterFragment() } }
        tabFavorites.setOnClickListener {
            // Thong bao tap moi gan voi phim yeu thich:
            // chua co quyen thi xin lai tai day (Android 13+)
            ensureNotificationPermission()
            select(tabFavorites)
            show("favorites") { FavoritesFragment() }
        }
        tabHistory.setOnClickListener {
            select(tabHistory)
            show("history") { HistoryFragment() }
        }

        if (savedInstanceState == null) {
            select(tabHome)
            show("home") { HomeFragment() }
        } else {
            // Activity duoc khoi phuc (xoay may / bi thu hoi roi mo lai):
            // to mau dung tab cua fragment dang hien
            val visibleTag = supportFragmentManager.fragments
                .firstOrNull { !it.isHidden }?.tag
            select(
                when (visibleTag) {
                    "single" -> tabSingle
                    "series" -> tabSeries
                    "cinema" -> tabCinema
                    "filter" -> tabFilter
                    "favorites" -> tabFavorites
                    "history" -> tabHistory
                    else -> tabHome
                }
            )
        }

        ensureNotificationPermission()
        ensureBatteryExemption()
        scheduleNewEpisodeChecks()
        // Xac dinh ten mien nguon dang song (chong web doi ten mien)
        RemoteConfig.resolveAsync(applicationContext)
    }

    /** To mau the dang chon (chu + icon dam/mau chinh), cac the con lai xam. */
    private fun select(tab: TextView) {
        selectedTab = tab
        val primary = androidx.core.content.ContextCompat.getColor(this, R.color.tab_selected)
        val idle = android.graphics.Color.parseColor("#8E8CA0")
        for (t in tabs) {
            val active = t == tab
            val color = if (active) primary else idle
            t.setTextColor(color)
            t.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            val top = t.compoundDrawables.getOrNull(1)
            if (top != null) {
                val wrapped = androidx.core.graphics.drawable.DrawableCompat.wrap(top.mutate())
                androidx.core.graphics.drawable.DrawableCompat.setTint(wrapped, color)
                t.setCompoundDrawablesWithIntrinsicBounds(null, wrapped, null, null)
            }
        }
    }

    private val fragCache = HashMap<String, Fragment>()

    private fun obtain(tag: String, create: () -> Fragment): Fragment {
        supportFragmentManager.findFragmentByTag(tag)?.let { return it }
        return fragCache.getOrPut(tag) { create() }
    }

    /**
     * Chuyen tab bang AN/HIEN: moi fragment chi tao 1 lan va giu nguyen
     * trang thai (du lieu da tai, vi tri cuon, trang da tai them) —
     * truoc day moi lan bam tab la replace() fragment moi, tai lai tu
     * dau qua mang nen chuyen tab rat cham.
     */
    private fun show(tag: String, create: () -> Fragment) {
        val target = obtain(tag, create)
        val tx = supportFragmentManager.beginTransaction()
        for (f in supportFragmentManager.fragments) {
            if (f !== target && !f.isHidden) tx.hide(f)
        }
        if (target.isAdded) tx.show(target) else tx.add(R.id.container, target, tag)
        tx.commit()
    }

    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    /**
     * Xin mien toi uu pin (1 lan duy nhat): nhieu may (nhat la Xiaomi)
     * giet viec kiem tra tap moi o nen, thong bao khong bao gio den
     * du da cap quyen. Da mien roi thi thoi; da hoi 1 lan thi khong
     * hoi lai (tranh lam phien).
     */
    fun ensureBatteryExemption() {
        try {
            val pm = getSystemService(android.content.Context.POWER_SERVICE)
                as android.os.PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) return
            val prefs = getSharedPreferences("app_state", MODE_PRIVATE)
            if (prefs.getBoolean("battery_prompt_shown", false)) return
            prefs.edit().putBoolean("battery_prompt_shown", true).apply()
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (e: Exception) {
            // May khong co man hinh nay thi bo qua: viec nen van chay
            // theo lich WorkManager nhu cu
        }
    }

    /** Kiem tra tap moi cho phim yeu thich moi 3 gio (chi khi co mang). */
    private fun scheduleNewEpisodeChecks() {
        val request = PeriodicWorkRequestBuilder<NewEpisodeWorker>(3, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "new_episode_check",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
