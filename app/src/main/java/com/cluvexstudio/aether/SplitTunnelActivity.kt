package com.cluvexstudio.aether

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.widget.SearchView
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

        // Build layout programmatically
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(0xFF1A1A2E.toInt())
            setPadding(0, 0, 0, 0)
        }

        // Title bar
        val titleBar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(32, 48, 32, 24)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val backBtn = TextView(this).apply {
            text = "←"
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 32, 0)
            setOnClickListener { finish() }
        }
        val titleText = TextView(this).apply {
            text = "Split Tunneling"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        titleBar.addView(backBtn)
        titleBar.addView(titleText)
        root.addView(titleBar)

        // Subtitle
        val subtitle = TextView(this).apply {
            text = "Select apps to BYPASS the VPN (their traffic won't go through the tunnel)"
            textSize = 13f
            setTextColor(0xAAFFFFFF.toInt())
            setPadding(32, 0, 32, 24)
        }
        root.addView(subtitle)

        // Search bar
        searchView = SearchView(this).apply {
            queryHint = "Search apps..."
            isIconifiedByDefault = false
            setBackgroundColor(0xFF16213E.toInt())
        }
        root.addView(searchView)

        // RecyclerView
        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@SplitTunnelActivity)
            setPadding(0, 16, 0, 0)
        }
        root.addView(recyclerView, android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        setContentView(root)

        loadApps()

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(newText: String?): Boolean {
                val filtered = if (newText.isNullOrBlank()) allApps
                else allApps.filter { it.name.contains(newText, true) || it.packageName.contains(newText, true) }
                adapter.updateList(filtered)
                return true
            }
        })
    }

    private fun loadApps() {
        val pm = packageManager
        val sharedPrefs = getSharedPreferences("aether_prefs", Context.MODE_PRIVATE)
        val excluded = sharedPrefs.getStringSet("split_tunnel_excluded", emptySet()) ?: emptySet()

        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.packageName != packageName } // exclude ourselves
            .filter {
                // Only show apps with a launcher icon (user-visible apps)
                pm.getLaunchIntentForPackage(it.packageName) != null
            }
            .map { app ->
                AppItem(
                    name = pm.getApplicationLabel(app).toString(),
                    packageName = app.packageName,
                    icon = pm.getApplicationIcon(app),
                    excluded = excluded.contains(app.packageName)
                )
            }
            .sortedWith(compareByDescending<AppItem> { it.excluded }.thenBy { it.name.lowercase() })

        allApps = installedApps
        adapter = AppListAdapter(installedApps.toMutableList()) { item, isChecked ->
            item.excluded = isChecked
            saveExclusions()
        }
        recyclerView.adapter = adapter
    }

    private fun saveExclusions() {
        val excluded = allApps.filter { it.excluded }.map { it.packageName }.toSet()
        getSharedPreferences("aether_prefs", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("split_tunnel_excluded", excluded)
            .apply()
    }

    class AppListAdapter(
        private var items: MutableList<AppItem>,
        private val onToggle: (AppItem, Boolean) -> Unit
    ) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(android.R.id.icon)
            val name: TextView = view.findViewById(android.R.id.text1)
            val pkg: TextView = view.findViewById(android.R.id.text2)
            val checkbox: CheckBox = view.findViewById(android.R.id.checkbox)
        }

        fun updateList(newList: List<AppItem>) {
            items = newList.toMutableList()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val ctx = parent.context

            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(32, 20, 32, 20)
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val icon = ImageView(ctx).apply {
                id = android.R.id.icon
                val size = (48 * ctx.resources.displayMetrics.density).toInt()
                layoutParams = android.widget.LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = 24
                }
            }
            row.addView(icon)

            val textLayout = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val nameText = TextView(ctx).apply {
                id = android.R.id.text1
                textSize = 15f
                setTextColor(0xFFFFFFFF.toInt())
                maxLines = 1
            }
            val pkgText = TextView(ctx).apply {
                id = android.R.id.text2
                textSize = 11f
                setTextColor(0x99FFFFFF.toInt())
                maxLines = 1
            }
            textLayout.addView(nameText)
            textLayout.addView(pkgText)
            row.addView(textLayout)

            val checkbox = CheckBox(ctx).apply {
                id = android.R.id.checkbox
            }
            row.addView(checkbox)

            return ViewHolder(row)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.icon.setImageDrawable(item.icon)
            holder.name.text = item.name
            holder.pkg.text = item.packageName
            holder.checkbox.setOnCheckedChangeListener(null)
            holder.checkbox.isChecked = item.excluded
            holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
                onToggle(item, isChecked)
            }
            holder.itemView.setOnClickListener {
                holder.checkbox.isChecked = !holder.checkbox.isChecked
            }
        }

        override fun getItemCount() = items.size
    }
}
