package com.example.signaldetector

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

class MainActivity : Activity(), SignalScanner.Listener, SensorEventListener {

    companion object {
        private const val REQ_PERMS = 1001
        private const val BASELINE_MS = 15_000L
        private const val LIST_REFRESH_MS = 1_500L
        private const val TRACK_REFRESH_MS = 400L
        private const val STALE_MS = 30_000L
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private val COLOR_BG = Color.parseColor("#0F1115")
    private val COLOR_TEXT = Color.parseColor("#E6E8EB")
    private val COLOR_SUB = Color.parseColor("#9AA4B2")
    private val COLOR_ALERT = Color.parseColor("#FF6B6B")
    private val COLOR_GOOD = Color.parseColor("#4CC38A")
    private val COLOR_NEW_BG = Color.parseColor("#2A1517")
    private val COLOR_WARN = Color.parseColor("#FF9F1C")

    private val handler = Handler(Looper.getMainLooper())
    private val store = SignalStore()
    private lateinit var scanner: SignalScanner
    private lateinit var prefs: SharedPreferences

    private var scanning = false
    private var baselineEnd = 0L
    private var touching = false

    // 필터 상태
    private var onlyNew = false
    private var onlySuspect = false
    private var onlyUnnamedBle = false
    private var wifiSuspectOnly = false
    private var bandFilter = 0 // 0=전체, 1=2.4GHz, 2=5GHz 이상

    // 목록 화면
    private lateinit var listScreen: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var scanButton: Button
    private lateinit var listView: ListView
    private lateinit var adapter: SignalAdapter
    private lateinit var filterPanel: LinearLayout
    private lateinit var bandButton: Button

    // 추적 화면
    private lateinit var trackScreen: ScrollView
    private lateinit var trackName: TextView
    private lateinit var trackInfo: TextView
    private lateinit var trackClass: TextView
    private lateinit var trackReason: TextView
    private lateinit var rssiText: TextView
    private lateinit var rssiSub: TextView
    private lateinit var trendText: TextView
    private lateinit var trendSub: TextView
    private lateinit var distText: TextView
    private lateinit var graph: GraphView
    private lateinit var compass: CompassView
    private lateinit var dirText: TextView
    private lateinit var dirHint: TextView
    private lateinit var slowNote: TextView

    private var trackedKey: String? = null
    private var tracker: SignalTracker? = null

    // 방향 센서
    private var sensorManager: SensorManager? = null
    private var rotationSensor: Sensor? = null
    private val rot = FloatArray(9)
    private var headingSin = 0.0
    private var headingCos = 1.0
    private var hasHeading = false
    private var sensorAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH

    // ------------------------------------------------------------------
    // 생명주기
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("anticheat", Context.MODE_PRIVATE)
        store.config = AnalyzerConfig(
            parseChannels(prefs.getString("ch24", "12,13,14") ?: ""),
            parseChannels(prefs.getString("ch5", "") ?: "")
        )

        scanner = SignalScanner(this, this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        val root = FrameLayout(this)
        root.setBackgroundColor(COLOR_BG)
        root.addView(buildListScreen(), FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(buildTrackScreen(), FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        showList()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(listLoop)
        handler.post(listLoop)
        if (trackedKey != null) {
            registerSensor()
            handler.removeCallbacks(trackLoop)
            handler.post(trackLoop)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(listLoop)
        handler.removeCallbacks(trackLoop)
        unregisterSensor()
    }

    override fun onDestroy() {
        super.onDestroy()
        scanner.stop()
        handler.removeCallbacksAndMessages(null)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (trackScreen.visibility == View.VISIBLE) {
            closeTrack()
        } else {
            super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------
    // 화면 구성 (코드로 직접 구성)
    // ------------------------------------------------------------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun pill(color: Int, radiusDp: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(radiusDp).toFloat()
        return d
    }

    private fun label(size: Float, color: Int, bold: Boolean = false): TextView {
        val tv = TextView(this)
        tv.textSize = size
        tv.setTextColor(color)
        if (bold) tv.typeface = Typeface.DEFAULT_BOLD
        return tv
    }

    private fun filterSwitch(text: String, onChange: (Boolean) -> Unit): Switch {
        val sw = Switch(this)
        sw.text = text
        sw.setTextColor(COLOR_TEXT)
        sw.setPadding(dp(4), dp(6), dp(4), dp(6))
        sw.setOnCheckedChangeListener { _, checked ->
            onChange(checked)
            refreshList()
        }
        return sw
    }

    private fun bandLabel(): String = when (bandFilter) {
        1 -> "Wi-Fi 대역: 2.4GHz만"
        2 -> "Wi-Fi 대역: 5GHz 이상만"
        else -> "Wi-Fi 대역: 전체"
    }

    private fun buildListScreen(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(COLOR_BG)
        col.setPadding(dp(16), dp(12), dp(16), dp(8))
        listScreen = col

        col.addView(label(22f, COLOR_TEXT, true).apply { text = "안티 커닝" })

        statusText = label(13f, COLOR_SUB).apply {
            text = "'탐색 시작'을 눌러 주변 블루투스·Wi-Fi 신호를 찾아보세요."
            setPadding(0, dp(4), 0, dp(8))
        }
        col.addView(statusText)

        scanButton = Button(this).apply {
            text = "탐색 시작"
            setOnClickListener { toggleScan() }
        }
        col.addView(scanButton, LinearLayout.LayoutParams(MATCH, WRAP))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(
            Button(this).apply {
                text = "기준 재설정"
                setOnClickListener {
                    store.markAllAsBaseline()
                    refreshList()
                    toast("현재 보이는 신호를 모두 '기존 신호'로 처리했습니다.")
                }
            },
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        row.addView(
            Button(this).apply {
                text = "목록 비우기"
                setOnClickListener {
                    store.clear()
                    val now = SystemClock.elapsedRealtime()
                    baselineEnd = if (scanning) now + BASELINE_MS else 0L
                    store.baselineUntil = baselineEnd
                    refreshList()
                }
            },
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        col.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))

        // 자주 쓰는 필터 두 개는 항상 보이게
        val swRow = LinearLayout(this)
        swRow.orientation = LinearLayout.HORIZONTAL
        swRow.addView(filterSwitch("새 신호만") { onlyNew = it }, LinearLayout.LayoutParams(0, WRAP, 1f))
        swRow.addView(filterSwitch("의심 신호만") { onlySuspect = it }, LinearLayout.LayoutParams(0, WRAP, 1f))
        col.addView(swRow, LinearLayout.LayoutParams(MATCH, WRAP))

        // 나머지 필터·설정은 접이식 패널
        filterPanel = LinearLayout(this)
        filterPanel.orientation = LinearLayout.VERTICAL
        filterPanel.visibility = View.GONE
        filterPanel.addView(filterSwitch("이름 없는 BLE 기기만") { onlyUnnamedBle = it })
        filterPanel.addView(filterSwitch("Wi-Fi는 숨김·핫스팟 의심만") { wifiSuspectOnly = it })
        bandButton = Button(this)
        bandButton.text = bandLabel()
        bandButton.setOnClickListener {
            bandFilter = (bandFilter + 1) % 3
            bandButton.text = bandLabel()
            refreshList()
        }
        filterPanel.addView(bandButton, LinearLayout.LayoutParams(MATCH, WRAP))
        filterPanel.addView(
            Button(this).apply {
                text = "감시할 Wi-Fi 채널 설정"
                setOnClickListener { showChannelDialog() }
            },
            LinearLayout.LayoutParams(MATCH, WRAP)
        )
        filterPanel.addView(label(11f, COLOR_SUB).apply {
            text = "의심 표시는 패킷 정보로 추정한 참고 값입니다. 정상 기기도 표시될 수 있으니 반드시 직접 확인하세요."
            setPadding(dp(4), dp(4), dp(4), dp(4))
        })

        val toggle = Button(this)
        toggle.text = "필터·설정 ▾"
        toggle.setOnClickListener {
            val open = filterPanel.visibility != View.VISIBLE
            filterPanel.visibility = if (open) View.VISIBLE else View.GONE
            toggle.text = if (open) "필터·설정 ▴" else "필터·설정 ▾"
        }
        col.addView(toggle, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(filterPanel, LinearLayout.LayoutParams(MATCH, WRAP))

        adapter = SignalAdapter()
        listView = ListView(this)
        listView.adapter = adapter
        listView.divider = ColorDrawable(Color.parseColor("#262B35"))
        listView.dividerHeight = 1
        listView.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> touching = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touching = false
            }
            false
        }
        listView.setOnItemClickListener { _, view, _, _ ->
            val key = view.tag as? String
            if (key != null) openTrack(key)
        }
        col.addView(listView, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return col
    }

    private fun showChannelDialog() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)

        box.addView(label(12f, COLOR_SUB).apply {
            text = "쉼표로 구분하고 범위는 12-14처럼 적습니다. 입력한 채널의 Wi-Fi 신호에는 '감시 채널' 표시와 의심 점수가 붙습니다. 비워 두면 감시하지 않습니다."
            setPadding(0, 0, 0, dp(8))
        })
        val e24 = EditText(this)
        e24.hint = "2.4GHz 채널 (예: 12,13,14)"
        e24.setText(channelsToText(store.config.ch24))
        e24.inputType = InputType.TYPE_CLASS_TEXT
        box.addView(e24, LinearLayout.LayoutParams(MATCH, WRAP))

        val e5 = EditText(this)
        e5.hint = "5GHz 채널 (예: 36-48)"
        e5.setText(channelsToText(store.config.ch5))
        e5.inputType = InputType.TYPE_CLASS_TEXT
        box.addView(e5, LinearLayout.LayoutParams(MATCH, WRAP))

        AlertDialog.Builder(this)
            .setTitle("감시할 Wi-Fi 채널")
            .setView(box)
            .setPositiveButton("저장") { _, _ ->
                val c24 = parseChannels(e24.text.toString())
                val c5 = parseChannels(e5.text.toString())
                store.config = AnalyzerConfig(c24, c5)
                store.reanalyzeAll()
                prefs.edit()
                    .putString("ch24", channelsToText(c24))
                    .putString("ch5", channelsToText(c5))
                    .apply()
                refreshList()
                toast("감시 채널을 저장했습니다.")
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun buildTrackScreen(): View {
        val sv = ScrollView(this)
        sv.setBackgroundColor(COLOR_BG)
        sv.isFillViewport = true
        sv.visibility = View.GONE
        trackScreen = sv

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(12), dp(16), dp(24))
        sv.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))

        col.addView(
            Button(this).apply {
                text = "← 목록으로"
                setOnClickListener { closeTrack() }
            },
            LinearLayout.LayoutParams(WRAP, WRAP)
        )

        trackName = label(22f, COLOR_TEXT, true)
        col.addView(trackName)
        trackInfo = label(12f, COLOR_SUB)
        col.addView(trackInfo)
        trackClass = label(14f, COLOR_TEXT, true).apply { setPadding(0, dp(6), 0, 0) }
        col.addView(trackClass)
        trackReason = label(12f, COLOR_WARN)
        col.addView(trackReason)

        rssiText = label(44f, COLOR_TEXT, true).apply { setPadding(0, dp(8), 0, 0) }
        col.addView(rssiText)
        rssiSub = label(12f, COLOR_SUB)
        col.addView(rssiSub)

        trendText = label(21f, COLOR_SUB, true).apply { setPadding(0, dp(10), 0, 0) }
        col.addView(trendText)
        trendSub = label(12f, COLOR_SUB)
        col.addView(trendSub)

        distText = label(15f, COLOR_TEXT).apply { setPadding(0, dp(10), 0, dp(8)) }
        col.addView(distText)

        graph = GraphView(this)
        col.addView(graph, LinearLayout.LayoutParams(MATCH, dp(120)))

        col.addView(label(16f, COLOR_TEXT, true).apply {
            text = "신호 방향"
            setPadding(0, dp(16), 0, dp(8))
        })
        compass = CompassView(this)
        col.addView(
            compass,
            LinearLayout.LayoutParams(MATCH, WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL }
        )
        dirText = label(15f, COLOR_TEXT, true).apply { setPadding(0, dp(8), 0, 0) }
        col.addView(dirText)
        dirHint = label(12f, COLOR_SUB).apply { setPadding(0, dp(4), 0, 0) }
        col.addView(dirHint)

        col.addView(
            Button(this).apply {
                text = "방향 데이터 초기화"
                setOnClickListener { tracker?.resetDirection() }
            },
            LinearLayout.LayoutParams(WRAP, WRAP)
        )

        slowNote = label(12f, COLOR_SUB).apply { setPadding(0, dp(8), 0, 0) }
        col.addView(slowNote)
        return sv
    }

    // ------------------------------------------------------------------
    // 목록
    // ------------------------------------------------------------------

    private fun levelColor(level: Int): Int = when (level) {
        3 -> Color.parseColor("#FF4D4F")
        2 -> Color.parseColor("#FF9F1C")
        1 -> Color.parseColor("#E0C341")
        else -> Color.TRANSPARENT
    }

    private fun levelBg(level: Int): Int = when (level) {
        3 -> Color.parseColor("#3A1417")
        2 -> Color.parseColor("#33220E")
        else -> Color.TRANSPARENT
    }

    private fun levelText(level: Int): String = when (level) {
        3 -> "강한 의심"
        2 -> "의심"
        1 -> "주의"
        else -> ""
    }

    private inner class SignalAdapter : BaseAdapter() {
        var items: List<Signal> = emptyList()

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val s = items[position]
            val now = SystemClock.elapsedRealtime()
            val stale = now - s.lastSeen > STALE_MS
            val ctx = this@MainActivity
            val an = s.analysis
            val level = if (stale) 0 else an.level

            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(10), dp(8), dp(10))
            row.tag = s.key
            if (level >= 2) {
                row.setBackgroundColor(levelBg(level))
            } else if (s.isNew && !stale) {
                row.setBackgroundColor(COLOR_NEW_BG)
            }
            row.alpha = if (stale) 0.45f else 1f

            // 맨 왼쪽: 의심 단계 색 막대
            val stripe = View(ctx)
            stripe.setBackgroundColor(levelColor(level))
            row.addView(stripe, LinearLayout.LayoutParams(dp(4), MATCH))

            // 종류 배지 / 의심 배지 / NEW
            val left = LinearLayout(ctx)
            left.orientation = LinearLayout.VERTICAL
            left.gravity = Gravity.CENTER_HORIZONTAL
            left.addView(badge(s.type.label, typeColor(s.type)))
            if (level >= 1) {
                left.addView(
                    badge("⚠ " + levelText(level), levelColor(level), level == 1),
                    LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) }
                )
            }
            if (s.isNew) {
                left.addView(
                    badge("NEW", Color.parseColor("#E5484D")),
                    LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) }
                )
            }
            row.addView(left, LinearLayout.LayoutParams(dp(72), WRAP).apply { leftMargin = dp(4) })

            // 가운데: 이름 / 분류 / 주소 / 이유
            val mid = LinearLayout(ctx)
            mid.orientation = LinearLayout.VERTICAL
            mid.addView(label(16f, COLOR_TEXT, true).apply {
                text = displayName(s)
                maxLines = 1
            })
            if (an.detail.isNotEmpty()) {
                mid.addView(label(12f, if (level >= 2) levelColor(level) else COLOR_TEXT).apply {
                    text = "분류: " + an.detail
                    maxLines = 2
                })
            }
            val sub = StringBuilder(s.id)
            if (s.extra.isNotEmpty()) sub.append(" · ").append(s.extra)
            if (s.isNew) sub.append(" · ").append((now - s.firstSeen) / 1000).append("초 전 처음 감지")
            mid.addView(label(11f, COLOR_SUB).apply {
                text = sub.toString()
                maxLines = 2
            })
            if (an.reasons.isNotEmpty() && level >= 1) {
                mid.addView(label(11f, COLOR_WARN).apply {
                    text = "⚠ " + an.reasons.joinToString(" · ")
                    maxLines = 3
                })
            }
            row.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(6) })

            // 오른쪽: dBm + 세기 막대
            val right = LinearLayout(ctx)
            right.orientation = LinearLayout.VERTICAL
            right.gravity = Gravity.END
            val color = rssiColor(s.rssi)
            right.addView(label(16f, color, true).apply { text = "${s.rssi} dBm" })

            val track = FrameLayout(ctx)
            track.background = pill(Color.parseColor("#2C3340"), 3)
            val fillW = maxOf(dp(3), dp(72) * rssiPercent(s.rssi) / 100)
            val fill = View(ctx)
            fill.background = pill(color, 3)
            track.addView(fill, FrameLayout.LayoutParams(fillW, MATCH))
            right.addView(track, LinearLayout.LayoutParams(dp(72), dp(6)).apply { topMargin = dp(6) })
            row.addView(right, LinearLayout.LayoutParams(dp(80), WRAP))

            return row
        }
    }

    private fun badge(text: String, color: Int, darkText: Boolean = false): TextView {
        val tv = label(11f, if (darkText) Color.parseColor("#1B1F27") else Color.WHITE, true)
        tv.text = text
        tv.gravity = Gravity.CENTER
        tv.setPadding(dp(6), dp(2), dp(6), dp(2))
        tv.background = pill(color, 8)
        return tv
    }

    private fun typeColor(t: SignalType): Int = when (t) {
        SignalType.BLE -> Color.parseColor("#3D8BFF")
        SignalType.BT_CLASSIC -> Color.parseColor("#8E5CF6")
        SignalType.WIFI -> Color.parseColor("#22B07D")
    }

    private fun rssiColor(rssi: Int): Int = when {
        rssi >= -55 -> Color.parseColor("#FF5A5F")
        rssi >= -70 -> Color.parseColor("#FFB020")
        rssi >= -85 -> Color.parseColor("#4CC38A")
        else -> Color.parseColor("#7C8696")
    }

    private fun rssiPercent(rssi: Int): Int = ((rssi + 100) * 100 / 65).coerceIn(0, 100)

    private fun displayName(s: Signal): String {
        if (s.name.isNotBlank()) return s.name
        return when (s.type) {
            SignalType.WIFI -> "(숨겨진 SSID)"
            else -> "(이름 없음)"
        }
    }

    private val listLoop = object : Runnable {
        override fun run() {
            if (listScreen.visibility == View.VISIBLE && !touching) refreshList()
            handler.postDelayed(this, LIST_REFRESH_MS)
        }
    }

    /** 현재 필터 설정에 맞는 신호인지 */
    private fun matches(s: Signal): Boolean {
        if (onlyNew && !s.isNew) return false
        if (onlySuspect && !s.analysis.suspect) return false
        if (onlyUnnamedBle && !(s.type == SignalType.BLE && s.name.isBlank())) return false
        if (s.type == SignalType.WIFI) {
            val w = s.wifi
            if (bandFilter == 1 && !(w != null && w.freqMhz < 3000)) return false
            if (bandFilter == 2 && !(w != null && w.freqMhz >= 3000)) return false
            if (wifiSuspectOnly) {
                val f = s.analysis.flags
                if (!(f.contains("HIDDEN") || f.contains("HOTSPOT") || f.contains("DIRECT"))) return false
            }
        }
        return true
    }

    private fun refreshList() {
        val now = SystemClock.elapsedRealtime()
        val all = store.all.toList()
        val newCount = all.count { it.isNew }
        val suspectCount = all.count { it.analysis.suspect && now - it.lastSeen <= STALE_MS }
        val shown = all.filter { matches(it) }.sortedWith(
            compareBy<Signal> { now - it.lastSeen > STALE_MS }
                .thenByDescending { it.analysis.score }
                .thenByDescending { it.isNew }
                .thenByDescending { it.rssi }
        )
        adapter.items = shown
        adapter.notifyDataSetChanged()
        updateStatus(all.size, newCount, suspectCount, shown.size)
    }

    private fun updateStatus(total: Int, newCount: Int, suspectCount: Int, shownCount: Int) {
        val now = SystemClock.elapsedRealtime()
        val text: String
        var alert = false
        val filtered = if (shownCount != total) " · 표시 ${shownCount}개" else ""
        if (!scanning) {
            text = if (total == 0) {
                "대기 중 · '탐색 시작'을 눌러 주세요."
            } else {
                "중지됨 · 전체 ${total}개 · 새 신호 ${newCount}개 · 의심 ${suspectCount}개$filtered"
            }
        } else if (now < baselineEnd) {
            val sec = (baselineEnd - now) / 1000 + 1
            text = "기준 신호 수집 중… ${sec}초 (지금까지 ${total}개). 이 시간 동안 보인 신호는 '기존 신호'로 처리됩니다."
        } else {
            text = "탐색 중 · 전체 ${total}개 · 새 신호 ${newCount}개 · 의심 ${suspectCount}개$filtered · 항목을 누르면 추적합니다."
            alert = newCount > 0 || suspectCount > 0
        }
        statusText.text = text
        statusText.setTextColor(if (alert) COLOR_ALERT else COLOR_SUB)
    }

    // ------------------------------------------------------------------
    // 탐색 시작/중지, 권한
    // ------------------------------------------------------------------

    private fun requiredPermissions(): Array<String> {
        val list = ArrayList<String>()
        list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return list.toTypedArray()
    }

    private fun hasAllPermissions(): Boolean {
        for (p in requiredPermissions()) {
            // Wi-Fi 근처 기기 권한은 위치 권한이 있으면 없어도 동작하므로 필수로 보지 않는다.
            if (p == Manifest.permission.NEARBY_WIFI_DEVICES) continue
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false
        }
        return true
    }

    private fun isLocationOn(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
        return if (Build.VERSION.SDK_INT >= 28) {
            lm.isLocationEnabled
        } else {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    private fun toggleScan() {
        if (scanning) {
            stopScan()
            return
        }
        if (!hasAllPermissions()) {
            requestPermissions(requiredPermissions(), REQ_PERMS)
            return
        }
        if (!isLocationOn()) {
            toast("주변 신호를 찾으려면 위치(GPS) 서비스를 켜야 합니다.")
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        startScan()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (hasAllPermissions()) {
            toggleScan()
        } else {
            toast("권한이 허용되지 않아 탐색할 수 없습니다. 설정 > 앱 > 권한에서 '근처 기기'와 '위치'를 허용해 주세요.")
        }
    }

    private fun startScan() {
        scanning = true
        val now = SystemClock.elapsedRealtime()
        if (store.all.isEmpty()) {
            baselineEnd = now + BASELINE_MS
            store.baselineUntil = baselineEnd
        }
        scanButton.text = "탐색 중지"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        scanner.start()
        refreshList()
    }

    private fun stopScan() {
        scanning = false
        scanner.stop()
        scanButton.text = "탐색 시작"
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refreshList()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------
    // SignalScanner.Listener
    // ------------------------------------------------------------------

    override fun onReading(r: Reading) {
        val now = SystemClock.elapsedRealtime()
        val s = store.update(r, now)
        if (s.key == trackedKey) tracker?.add(r.rssi, now, currentHeading())
    }

    override fun onMessage(message: String) {
        toast(message)
    }

    // ------------------------------------------------------------------
    // 추적 화면
    // ------------------------------------------------------------------

    private fun showList() {
        trackScreen.visibility = View.GONE
        listScreen.visibility = View.VISIBLE
        refreshList()
    }

    private fun openTrack(key: String) {
        val s = store[key] ?: return
        trackedKey = key
        tracker = SignalTracker(s.type)
        compass.tracker = tracker
        compass.estimateBearing = null
        graph.samples = tracker!!.history
        hasHeading = false

        slowNote.text = if (s.type == SignalType.BLE) {
            ""
        } else {
            "※ Wi-Fi와 클래식 블루투스는 측정 간격이 길어(수 초~수십 초) 추세·방향이 천천히 반영됩니다. " +
                "정확한 추적은 BLE 신호가 가장 잘 됩니다. Wi-Fi는 안드로이드의 스캔 횟수 제한이 있어, " +
                "개발자 옵션의 'Wi-Fi 스캔 제한'을 끄면 더 자주 갱신됩니다."
        }

        listScreen.visibility = View.GONE
        trackScreen.visibility = View.VISIBLE
        trackScreen.scrollTo(0, 0)
        registerSensor()
        handler.removeCallbacks(trackLoop)
        handler.post(trackLoop)
    }

    private fun closeTrack() {
        trackedKey = null
        tracker = null
        compass.tracker = null
        handler.removeCallbacks(trackLoop)
        unregisterSensor()
        hasHeading = false
        showList()
    }

    private val trackLoop = object : Runnable {
        override fun run() {
            if (trackedKey == null) return
            updateTrackUi()
            handler.postDelayed(this, TRACK_REFRESH_MS)
        }
    }

    private fun updateTrackUi() {
        val s = trackedKey?.let { store[it] } ?: return
        val t = tracker ?: return
        val now = SystemClock.elapsedRealtime()

        trackName.text = displayName(s)
        trackInfo.text = "${s.type.label} · ${s.id}" + if (s.extra.isNotEmpty()) " · ${s.extra}" else ""

        val an = s.analysis
        trackClass.text = if (an.detail.isNotEmpty()) "분류: " + an.detail else ""
        trackClass.setTextColor(if (an.level >= 2) levelColor(an.level) else COLOR_TEXT)
        trackReason.text = if (an.reasons.isNotEmpty()) "⚠ " + an.reasons.joinToString(" · ") else ""

        graph.nowMs = now
        graph.invalidate()
        compass.nowMs = now

        if (t.sampleCount == 0) {
            rssiText.text = "-- dBm"
            rssiSub.text = "아직 측정값이 없습니다. 신호가 잡힐 때까지 기다려 주세요."
            trendText.text = "… 측정 중"
            trendText.setTextColor(COLOR_SUB)
            trendSub.text = ""
            distText.text = ""
            dirText.text = ""
            dirHint.text = ""
            compass.invalidate()
            return
        }

        val smooth = t.smoothedRssi
        rssiText.text = "${smooth.roundToInt()} dBm"
        rssiText.setTextColor(rssiColor(smooth.roundToInt()))
        val ageSec = (now - t.lastUpdate) / 1000
        rssiSub.text = "최근 측정값 ${s.rssi} dBm · ${ageSec}초 전 수신 · 총 ${t.sampleCount}회 측정"

        // 추세
        val stale = t.isStale(now)
        val slope = t.slopeDbPerSec(now)
        if (stale) {
            trendText.text = "✕ 신호 끊김"
            trendText.setTextColor(COLOR_ALERT)
            trendSub.text = "${ageSec}초째 수신되지 않습니다. 신호가 꺼졌거나 범위를 벗어났을 수 있습니다."
        } else {
            when (t.trend(now)) {
                Trend.APPROACHING -> {
                    trendText.text = "▲ 가까워지는 중"
                    trendText.setTextColor(COLOR_GOOD)
                }
                Trend.RECEDING -> {
                    trendText.text = "▼ 멀어지는 중"
                    trendText.setTextColor(COLOR_ALERT)
                }
                Trend.STABLE -> {
                    trendText.text = "● 변화 없음"
                    trendText.setTextColor(COLOR_SUB)
                }
                Trend.UNKNOWN -> {
                    trendText.text = "… 추세 계산 중"
                    trendText.setTextColor(COLOR_SUB)
                }
            }
            trendSub.text = if (slope != null) {
                String.format("세기 변화율 %+.2f dB/초 (양수=강해짐)", slope)
            } else {
                "측정값을 더 모으는 중입니다."
            }
        }

        // 거리
        val d = t.distanceMeters()
        distText.text = if (d != null) {
            val ds = if (d < 10) String.format("%.1f", d) else d.roundToInt().toString()
            "대략 ${ds}m · ${t.proximityLabel()}  (거리 추정은 오차가 매우 큽니다)"
        } else {
            ""
        }

        // 방향
        updateDirectionUi(t, now)
        compass.invalidate()
    }

    private fun updateDirectionUi(t: SignalTracker, now: Long) {
        if (rotationSensor == null) {
            compass.estimateBearing = null
            dirText.text = "방향 센서가 없는 기기입니다."
            dirHint.text = "방향 추정은 사용할 수 없고, 세기 변화(가까워짐/멀어짐)만 이용할 수 있습니다."
            return
        }
        val dir = t.direction(now)
        val covered = (0 until SignalTracker.BINS).count {
            !t.binEma[it].isNaN() && now - t.binTime[it] <= SignalTracker.BIN_STALE_MS
        }
        if (dir == null) {
            compass.estimateBearing = null
            dirText.text = "방향 측정 중… ($covered/${SignalTracker.BINS}방향)"
            dirHint.text = "휴대폰을 몸에서 조금 떨어뜨려 든 채, 제자리에서 천천히 한 바퀴(360°) 돌아보세요. " +
                "돌면서 방향별 신호 세기를 모읍니다."
            return
        }
        compass.estimateBearing = dir.bearingDeg

        val heading = currentHeading() ?: 0.0
        val diff = ((dir.bearingDeg - heading + 540.0) % 360.0) - 180.0
        val conf = when (dir.confidence) {
            2 -> "높음"
            1 -> "보통"
            else -> "낮음"
        }
        val guide = when {
            abs(diff) < 20.0 -> "지금 정면 쪽입니다. 그대로 앞으로 이동하세요."
            diff > 0 -> "오른쪽으로 약 ${diff.roundToInt()}° 돌아보세요."
            else -> "왼쪽으로 약 ${(-diff).roundToInt()}° 돌아보세요."
        }
        dirText.text = "${bearingName(dir.bearingDeg)}쪽 ${dir.bearingDeg.roundToInt()}° · 정확도 $conf · $guide"

        var hint = "측정된 방향 ${dir.coveredBins}/${SignalTracker.BINS} · 화면 위쪽이 휴대폰이 향한 방향, " +
            "빨강일수록 신호가 강한 방향입니다."
        if (dir.confidence == 0) {
            hint += " 방향 간 세기 차이가 작아 신뢰도가 낮습니다. 더 천천히 한 바퀴 더 돌아보세요."
        }
        if (sensorAccuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW) {
            hint += " (나침반 보정이 필요합니다: 휴대폰을 8자 모양으로 흔들어 주세요.)"
        }
        dirHint.text = hint
    }

    // ------------------------------------------------------------------
    // 방향 센서 (회전 벡터 → 휴대폰이 향한 방위)
    // ------------------------------------------------------------------

    private fun registerSensor() {
        val sensor = rotationSensor ?: return
        sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun unregisterSensor() {
        sensorManager?.unregisterListener(this)
    }

    private fun currentHeading(): Double? {
        if (!hasHeading) return null
        return (Math.toDegrees(atan2(headingSin, headingCos)) + 360.0) % 360.0
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rot, event.values)

        // 휴대폰을 세워 들면 뒷면(카메라 방향), 눕혀 들면 윗면이 향한 방위를 사용한다.
        val upright = hypot(rot[2].toDouble(), rot[5].toDouble()) > 0.5
        val h = if (upright) {
            atan2(-rot[2].toDouble(), -rot[5].toDouble())
        } else {
            atan2(rot[1].toDouble(), rot[4].toDouble())
        }

        val a = if (hasHeading) 0.25 else 1.0
        headingSin += a * (sin(h) - headingSin)
        headingCos += a * (cos(h) - headingCos)
        hasHeading = true

        compass.heading = (currentHeading() ?: 0.0).toFloat()
        compass.invalidate()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        sensorAccuracy = accuracy
    }
}
