package com.example.signaldetector

/** 신호 종류 */
enum class SignalType(val label: String) {
    BLE("BLE"),
    BT_CLASSIC("BT"),
    WIFI("Wi-Fi")
}

/** 스캐너가 한 번 측정한 값 */
class Reading(
    val type: SignalType,
    val id: String,
    val name: String,
    val rssi: Int,
    val extra: String,
    val adv: BleAdv? = null,
    val wifi: WifiInfo? = null,
    val btClass: Int = -1
)

/** 탐지된 신호 하나 (블루투스 장치 또는 Wi-Fi 공유기/핫스팟) */
class Signal(
    val key: String,
    val type: SignalType,
    val id: String,
    var name: String,
    var rssi: Int,
    var extra: String,
    val firstSeen: Long
) {
    var lastSeen: Long = firstSeen
    var isNew: Boolean = false
    var hits: Int = 1
    var adv: BleAdv = BleAdv.EMPTY
    var wifi: WifiInfo? = null
    var btClass: Int = -1
    var analysis: Analysis = Analysis.NONE
}

/**
 * 탐지된 신호 저장소.
 * baselineUntil 이전에 처음 보인 신호는 '기존 신호'(기준), 이후에 처음 보인 신호는 '새 신호'로 표시한다.
 * 모든 시간은 SystemClock.elapsedRealtime() 기준 밀리초.
 */
class SignalStore {
    private val map = LinkedHashMap<String, Signal>()

    var baselineUntil: Long = 0L
    var config: AnalyzerConfig = AnalyzerConfig()

    val all: Collection<Signal> get() = map.values

    operator fun get(key: String): Signal? = map[key]

    fun update(r: Reading, now: Long): Signal {
        val key = keyOf(r.type, r.id)
        val existing = map[key]
        val s: Signal
        if (existing == null) {
            s = Signal(key, r.type, r.id, r.name, r.rssi, r.extra, now)
            s.isNew = now >= baselineUntil
            map[key] = s
        } else {
            s = existing
            s.rssi = r.rssi
            s.lastSeen = now
            s.hits++
            if (r.name.isNotEmpty()) s.name = r.name
            if (r.extra.isNotEmpty()) s.extra = r.extra
        }
        val adv = r.adv
        if (adv != null) s.adv = s.adv.merge(adv)
        if (r.wifi != null) s.wifi = r.wifi
        if (r.btClass >= 0) s.btClass = r.btClass
        reanalyze(s)
        return s
    }

    fun reanalyze(s: Signal) {
        if (s.type == SignalType.BLE) s.extra = Analyzer.bleExtra(s.adv)
        s.analysis = Analyzer.analyze(s.type, s.name, s.rssi, s.adv, s.wifi, s.btClass, config)
    }

    /** 감시 채널 설정이 바뀌었을 때 전체 다시 분석 */
    fun reanalyzeAll() {
        for (s in map.values) reanalyze(s)
    }

    /** 현재 목록에 있는 모든 신호를 '기존 신호'로 처리 */
    fun markAllAsBaseline() {
        for (s in map.values) s.isNew = false
    }

    fun clear() {
        map.clear()
    }

    companion object {
        fun keyOf(type: SignalType, id: String): String = "${type.name}|$id"
    }
}
