package com.cluvexstudio.aether

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.SearchView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class SplitTunnelActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var searchView: SearchView
    private lateinit var adapter: AppListAdapter
    private var allApps: List<AppItem> = emptyList()

    data class AppItem(
        val name: String,
        val packageName: String,
        val icon: Drawable,
        var excluded: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Root uses the app's dark theme color
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg_main))
        }

        // Top bar matching app style
        val topBar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(getColor(R.color.surface_dark))
            setPadding(16.dp, 0, 16.dp, 0)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 64.dp)
        }
        val backBtn = TextView(this).apply {
            text = "←"
            textSize = 22f
            setTextColor(getColor(R.color.text_main))
            setPadding(0, 0, 16.dp, 0)
            setOnClickListener { finish() }
        }
        val titleText = TextView(this).apply {
            text = "Split Tunneling"
            textSize = 18f
            setTextColor(getColor(R.color.text_main))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        topBar.addView(backBtn)
        topBar.addView(titleText)
        root.addView(topBar)

        // Subtitle
        val subtitle = TextView(this).apply {
            text = "تیک بزنید تا اون اپ از VPN رد نشه"
            textSize = 13f
            setTextColor(getColor(R.color.text_muted))
            setPadding(16.dp, 12.dp, 16.dp, 4.dp)
        }
        root.addView(subtitle)

        // Search bar with app theme
        searchView = SearchView(this).apply {
            queryHint = "Search apps..."
            isIconifiedByDefault = false
            setBackgroundColor(getColor(R.color.surface_dark))
        }
        root.addView(searchView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // RecyclerView
        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@SplitTunnelActivity)
            setBackgroundColor(getColor(R.color.bg_main))
        }
        root.addView(recyclerView, android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)
        loadApps()

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?) = false
            override fun onQueryTextChange(text: String?): Boolean {
                val filtered = if (text.isNullOrBlank()) allApps
                else allApps.filter { it.name.contains(text, true) || it.packageName.contains(text, true) }
                adapter.updateList(filtered)
                return true
            }
        })
    }

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    private fun loadApps() {
        val pm = packageManager
        val prefs = getSharedPreferences("aether_prefs", Context.MODE_PRIVATE)
        val excluded = prefs.getStringSet("split_tunnel_excluded", emptySet()) ?: emptySet()

        // Load installed apps (only those with a launcher icon)
        val mainIntent = Intent(Intent.ACTION_MAIN, null)
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
        
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
        val apps = resolveInfos.mapNotNull { resolveInfo ->
            val app = resolveInfo.activityInfo.applicationInfo
            if (app.packageName == packageName) return@mapNotNull null
            try {
                AppItem(
                    name = pm.getApplicationLabel(app).toString(),
                    packageName = app.packageName,
                    icon = pm.getApplicationIcon(app),
                    excluded = excluded.contains(app.packageName)
                )
            } catch (e: Exception) { null }
        }
        .distinctBy { it.packageName }
        .sortedWith(compareByDescending<AppItem> { it.excluded }.thenBy { it.name.lowercase() })

        allApps = apps
        adapter = AppListAdapter(apps.toMutableList(),
            bgColor = getColor(R.color.bg_main),
            surfaceColor = getColor(R.color.surface_dark),
            textColor = getColor(R.color.text_main),
            mutedColor = getColor(R.color.text_muted)
        ) { item, isChecked ->
            item.excluded = isChecked
            saveExclusions()
        }
        recyclerView.adapter = adapter
    }

    private fun saveExclusions() {
        val excluded = allApps.filter { it.excluded }.map { it.packageName }.toSet()
        getSharedPreferences("aether_prefs", Context.MODE_PRIVATE)
            .edit().putStringSet("split_tunnel_excluded", excluded).apply()
    }

    class AppListAdapter(
        private var items: MutableList<AppItem>,
        private val bgColor: Int,
        private val surfaceColor: Int,
        private val textColor: Int,
        private val mutedColor: Int,
        private val onToggle: (AppItem, Boolean) -> Unit
    ) : RecyclerView.Adapter<AppListAdapter.VH>() {

        inner class VH(val row: ViewGroup, val icon: ImageView, val name: TextView, val pkg: TextView, val check: CheckBox) : RecyclerView.ViewHolder(row)

        fun updateList(new: List<AppItem>) { items = new.toMutableList(); notifyDataSetChanged() }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, type: Int): VH {
            val ctx = parent.context
            val dp = ctx.resources.displayMetrics.density

            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundColor(bgColor)
                setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val icon = ImageView(ctx).apply {
                val sz = (44 * dp).toInt()
                layoutParams = android.widget.LinearLayout.LayoutParams(sz, sz).apply { marginEnd = (14 * dp).toInt() }
            }
            val texts = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val name = TextView(ctx).apply { textSize = 14f; setTextColor(textColor); maxLines = 1 }
            val pkg  = TextView(ctx).apply { textSize = 11f; setTextColor(mutedColor); maxLines = 1 }
            texts.addView(name); texts.addView(pkg)

            val check = CheckBox(ctx).apply {
                buttonTintList = android.content.res.ColorStateList.valueOf(0xFFFF6D00.toInt())
            }
            row.addView(icon); row.addView(texts); row.addView(check)
            return VH(row, icon, name, pkg, check)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val item = items[pos]
            h.icon.setImageDrawable(item.icon)
            h.name.text = item.name
            h.pkg.text  = item.packageName
            h.check.setOnCheckedChangeListener(null)
            h.check.isChecked = item.excluded
            h.check.setOnCheckedChangeListener { _, c -> onToggle(item, c) }
            h.row.setOnClickListener { h.check.isChecked = !h.check.isChecked }
        }
    }
}
