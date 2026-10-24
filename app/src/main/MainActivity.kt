package com.flow.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast

class AppInfo(val label: String, val component: ComponentName, val icon: Bitmap)

class MainActivity : Activity() {

    private var apps: List<AppInfo> = emptyList()
    private var shown: List<AppInfo> = emptyList()

    private lateinit var root: FrameLayout
    private lateinit var home: LinearLayout
    private lateinit var dockRow: LinearLayout
    private lateinit var drawer: LinearLayout
    private lateinit var grid: GridView
    private lateinit var search: EditText
    private lateinit var adapter: AppAdapter

    private var drawerOpen = false
    private var drawerAnim: ValueAnimator? = null
    private var screenH = 0

    private val prefs by lazy { getSharedPreferences("flow", MODE_PRIVATE) }

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            loadApps()
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        screenH = resources.displayMetrics.heightPixels
        buildUi()
        setContentView(root)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(pkgReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(pkgReceiver, filter)
        }
        loadApps()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(pkgReceiver)
        } catch (_: Exception) {
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home button pressed while we are already in front.
        if (drawerOpen) closeDrawer(true)
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        if (drawerOpen) closeDrawer(true)
        // As a launcher, back on the home screen does nothing.
    }

    // ---------------------------------------------------------------- helpers

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun pill(color: Int, radiusPx: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx.toFloat()
            setStroke(dp(1f), stroke)
        }

    private fun iconBitmap(d: Drawable, s: Int): Bitmap {
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        if (d is AdaptiveIconDrawable) {
            val big = (s * 1.5f).toInt()
            c.save()
            c.translate(-(big - s) / 2f, -(big - s) / 2f)
            d.background?.let {
                it.setBounds(0, 0, big, big)
                it.draw(c)
            }
            d.foreground?.let {
                it.setBounds(0, 0, big, big)
                it.draw(c)
            }
            c.restore()
        } else {
            c.drawColor(0xFFEFF1F5.toInt())
            val inset = (s * 0.14f).toInt()
            d.setBounds(inset, inset, s - inset, s - inset)
            d.draw(c)
        }
        // Squircle-style mask (soft rounded square, ColorOS look)
        val mask = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        Canvas(mask).drawRoundRect(
            RectF(0f, 0f, s.toFloat(), s.toFloat()), s * 0.32f, s * 0.32f,
            Paint(Paint.ANTI_ALIAS_FLAG)
        )
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        c.drawBitmap(mask, 0f, 0f, p)
        return out
    }

    private fun iconView(bmp: Bitmap, sizePx: Int): ImageView = ImageView(this).apply {
        setImageBitmap(bmp)
        layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
    }

    // ---------------------------------------------------------------- UI

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        root = FrameLayout(this)

        val flingDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (vy < -1200f) {
                    openDrawer(false)
                    return true
                }
                if (vy > 1200f) {
                    expandNotifications()
                    return true
                }
                return false
            }

            override fun onLongPress(e: MotionEvent) {
                pickWallpaper()
            }
        })

        // ---- home layer
        home = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setOnTouchListener { _, ev -> flingDetector.onTouchEvent(ev) }
        }

        val clock = TextClock(this).apply {
            format12Hour = "h:mm"
            format24Hour = "HH:mm"
            textSize = 84f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setShadowLayer(14f, 0f, 2f, 0x66000000)
        }
        val date = TextClock(this).apply {
            format12Hour = "EEEE, d MMMM"
            format24Hour = "EEEE, d MMMM"
            textSize = 16f
            setTextColor(0xEEFFFFFF.toInt())
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 1f, 0x66000000)
        }
        val searchPill = TextView(this).apply {
            text = "Search apps"
            textSize = 15f
            setTextColor(0xCCFFFFFF.toInt())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22f), 0, dp(22f), 0)
            background = pill(0x33FFFFFF, dp(26f), 0x44FFFFFF)
            setOnTouchListener { _, ev ->
                flingDetector.onTouchEvent(ev)
                false
            }
            setOnClickListener { openDrawer(true) }
        }
        val spacer = View(this)

        dockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            background = pill(0x38FFFFFF, dp(38f), 0x55FFFFFF)
            setOnTouchListener { _, ev ->
                flingDetector.onTouchEvent(ev)
                false
            }
        }

        home.addView(clock, LinearLayout.LayoutParams(-1, -2))
        home.addView(date, LinearLayout.LayoutParams(-1, -2))
        home.addView(
            searchPill,
            LinearLayout.LayoutParams(-1, dp(52f)).apply { topMargin = dp(30f) }
        )
        home.addView(spacer, LinearLayout.LayoutParams(-1, 0, 1f))
        home.addView(
            dockRow,
            LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL }
        )

        // ---- drawer layer
        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x66000000)
            isClickable = true
        }

        val handle = View(this).apply {
            background = pill(0x88FFFFFF.toInt(), dp(3f), 0x00000000)
            setOnClickListener { closeDrawer(true) }
        }
        drawer.addView(
            handle,
            LinearLayout.LayoutParams(dp(44f), dp(5f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(14f)
            }
        )

        search = EditText(this).apply {
            hint = "Search apps"
            setHintTextColor(0x99FFFFFF.toInt())
            setTextColor(Color.WHITE)
            textSize = 15f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(22f), 0, dp(22f), 0)
            background = pill(0x33FFFFFF, dp(26f), 0x44FFFFFF)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    filterApps(s?.toString().orEmpty())
                }
            })
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    shown.firstOrNull()?.let { launch(it) }
                    true
                } else false
            }
        }
        drawer.addView(
            search,
            LinearLayout.LayoutParams(-1, dp(52f)).apply {
                leftMargin = dp(20f)
                rightMargin = dp(20f)
                bottomMargin = dp(8f)
            }
        )

        adapter = AppAdapter()
        grid = GridView(this).apply {
            numColumns = 4
            verticalSpacing = dp(10f)
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            this.adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> launch(shown[pos]) }
            setOnItemLongClickListener { _, _, pos, _ ->
                showAppMenu(shown[pos])
                true
            }
        }
        val closeDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val atTop = grid.firstVisiblePosition == 0 &&
                    (grid.getChildAt(0)?.top ?: 0) >= grid.paddingTop
                if (vy > 1500f && atTop) {
                    closeDrawer(true)
                    return true
                }
                return false
            }
        })
        grid.setOnTouchListener { _, ev ->
            closeDetector.onTouchEvent(ev)
            false
        }
        drawer.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(home, FrameLayout.LayoutParams(-1, -1))
        root.addView(drawer, FrameLayout.LayoutParams(-1, -1))

        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            home.setPadding(dp(24f), bars.top + dp(56f), dp(24f), bars.bottom + dp(22f))
            drawer.setPadding(0, bars.top + dp(14f), 0, 0)
            grid.setPadding(dp(12f), dp(8f), dp(12f), bars.bottom + dp(16f))
            insets
        }

        setProgress(0f)
        drawer.visibility = View.INVISIBLE
    }

    // ---------------------------------------------------------------- drawer animation

    private fun setProgress(p: Float) {
        drawer.translationY = (1f - p) * screenH
        drawer.alpha = 0.2f + 0.8f * p
        home.alpha = 1f - 0.7f * p
        home.scaleX = 1f - 0.06f * p
        home.scaleY = 1f - 0.06f * p
        try {
            window.setBackgroundBlurRadius((p * 90f).toInt())
        } catch (_: Exception) {
        }
    }

    private fun animateDrawer(open: Boolean, focusSearch: Boolean) {
        drawerAnim?.cancel()
        drawer.visibility = View.VISIBLE
        val from = 1f - drawer.translationY / screenH
        val to = if (open) 1f else 0f
        drawerOpen = open
        drawerAnim = ValueAnimator.ofFloat(from, to).apply {
            duration = 340
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { setProgress(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                var cancelled = false
                override fun onAnimationCancel(a: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(a: Animator) {
                    if (cancelled) return
                    if (!open) drawer.visibility = View.INVISIBLE
                    if (open && focusSearch) showKeyboard()
                }
            })
            start()
        }
    }

    private fun openDrawer(focusSearch: Boolean) {
        if (!drawerOpen) animateDrawer(true, focusSearch)
        else if (focusSearch) showKeyboard()
    }

    private fun closeDrawer(animated: Boolean) {
        hideKeyboard()
        search.setText("")
        if (animated) {
            animateDrawer(false, false)
        } else {
            drawerAnim?.cancel()
            drawerOpen = false
            setProgress(0f)
            drawer.visibility = View.INVISIBLE
        }
    }

    private fun showKeyboard() {
        search.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
    }

    // ---------------------------------------------------------------- apps

    private fun loadApps() {
        Thread {
            val pm = packageManager
            val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(q, 0)
                .filter { it.activityInfo.packageName != packageName }
                .map { ri ->
                    AppInfo(
                        ri.loadLabel(pm).toString(),
                        ComponentName(ri.activityInfo.packageName, ri.activityInfo.name),
                        iconBitmap(ri.loadIcon(pm), 168)
                    )
                }
                .sortedBy { it.label.lowercase() }
            runOnUiThread {
                apps = list
                filterApps(search.text.toString())
                buildDock()
            }
        }.start()
    }

    private fun filterApps(q: String) {
        shown = if (q.isBlank()) apps else apps.filter { it.label.contains(q, ignoreCase = true) }
        adapter.notifyDataSetChanged()
    }

    private fun launch(a: AppInfo) {
        val i = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(a.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(this, "Can't open ${a.label}", Toast.LENGTH_SHORT).show()
        }
        if (drawerOpen) closeDrawer(false)
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell = (convertView as? LinearLayout) ?: makeCell()
            val a = shown[position]
            (cell.getChildAt(0) as ImageView).setImageBitmap(a.icon)
            (cell.getChildAt(1) as TextView).text = a.label
            return cell
        }
    }

    private fun makeCell(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(6f), 0, dp(6f))
        addView(ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(58f), dp(58f))
        })
        addView(TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(6f, 0f, 1f, 0x66000000)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) }
        })
    }

    // ---------------------------------------------------------------- dock

    private fun defaultDock(): List<String> {
        val probes = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_VIEW, Uri.parse("sms:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        )
        return probes.mapNotNull { p ->
            val pkg = packageManager
                .resolveActivity(p, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            if (pkg == null || pkg == "android") null
            else apps.firstOrNull { it.component.packageName == pkg }?.component?.flattenToString()
        }.distinct()
    }

    private fun dockComponents(): List<String> {
        val saved = prefs.getString("dock", null)
        return if (saved == null) defaultDock() else saved.split(",").filter { it.isNotBlank() }
    }

    private fun buildDock() {
        dockRow.removeAllViews()
        for (s in dockComponents()) {
            val cn = ComponentName.unflattenFromString(s) ?: continue
            val a = apps.firstOrNull { it.component == cn } ?: continue
            val v = iconView(a.icon, dp(58f))
            (v.layoutParams as LinearLayout.LayoutParams).apply {
                leftMargin = dp(8f)
                rightMargin = dp(8f)
            }
            v.setOnClickListener { launch(a) }
            v.setOnLongClickListener {
                showAppMenu(a)
                true
            }
            dockRow.addView(v)
        }
        dockRow.visibility = if (dockRow.childCount == 0) View.GONE else View.VISIBLE
    }

    private fun toggleDock(a: AppInfo) {
        val key = a.component.flattenToString()
        val list = dockComponents().toMutableList()
        if (list.contains(key)) {
            list.remove(key)
        } else {
            if (list.size >= 5) {
                Toast.makeText(this, "Dock is full (5 apps)", Toast.LENGTH_SHORT).show()
                return
            }
            list.add(key)
        }
        prefs.edit().putString("dock", list.joinToString(",")).apply()
        buildDock()
    }

    private fun showAppMenu(a: AppInfo) {
        val inDock = dockComponents().contains(a.component.flattenToString())
        val options = arrayOf(
            if (inDock) "Remove from dock" else "Add to dock",
            "App info",
            "Uninstall"
        )
        val pkgUri = Uri.parse("package:${a.component.packageName}")
        AlertDialog.Builder(this)
            .setTitle(a.label)
            .setItems(options) { _, which ->
                try {
                    when (which) {
                        0 -> toggleDock(a)
                        1 -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri))
                        2 -> startActivity(Intent(Intent.ACTION_DELETE, pkgUri))
                    }
                } catch (_: Exception) {
                }
            }
            .show()
    }

    // ---------------------------------------------------------------- system actions

    @SuppressLint("WrongConstant")
    private fun expandNotifications() {
        try {
            val sb = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager")
                .getMethod("expandNotificationsPanel")
                .invoke(sb)
        } catch (_: Exception) {
        }
    }

    private fun pickWallpaper() {
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper"))
        } catch (_: Exception) {
        }
    }
}
