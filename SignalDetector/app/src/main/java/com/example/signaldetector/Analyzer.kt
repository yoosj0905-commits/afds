package com.example.signaldetector

/*
 * 신호 분석: 기기 종류 분류 + 의심 점수 계산 (안드로이드 API에 의존하지 않는 순수 코틀린)
 *
 * 모든 분류는 광고 패킷에 들어 있는 힌트로 하는 '추정'이다.
 * (Apple 연속성 메시지, 제조사 ID, 서비스 UUID, Appearance, 기기 이름 등)
 * 정확한 기기 종류를 알려주지 않으며, 의심 점수는 부정행위의 증거가 아니라 확인 대상을 추리는 참고 값이다.
 */

enum class DeviceClass(val label: String) {
    PHONE("스마트폰"),
    TABLET("태블릿"),
    PHONE_TAB("스마트폰·태블릿"),
    WATCH("스마트워치"),
    WEARABLE("웨어러블"),
    EARBUDS("무선 이어폰"),
    TAG("소형 추적 태그"),
    BEACON("비콘"),
    COMPUTER("컴퓨터"),
    AUDIO_TV("스피커·TV"),
    PERIPHERAL("주변기기"),
    SMALL_IOT("소형 IoT 기기"),
    NAMED_OTHER("기타 기기"),
    UNKNOWN_ANON("이름 없는 미확인 소형 기기"),
    WIFI_AP("일반 공유기·AP"),
    WIFI_HIDDEN("숨겨진 SSID"),
    WIFI_HOTSPOT("스마트폰 핫스팟 추정"),
    WIFI_DIRECT("Wi-Fi Direct")
}

/** 사용자가 설정하는 Wi-Fi 감시 채널 (대역별) */
class AnalyzerConfig(
    var ch24: Set<Int> = setOf(12, 13, 14),
    var ch5: Set<Int> = emptySet()
)

/** "12,13, 36-40" 같은 입력을 채널 집합으로 변환. 잘못된 값은 무시한다. */
fun parseChannels(text: String): Set<Int> {
    val out = HashSet<Int>()
    for (tok in text.split(',', ' ', ';', '\n')) {
        val t = tok.trim()
        if (t.isEmpty()) continue
        try {
            val dash = t.indexOf('-')
            if (dash > 0) {
                val a = t.substring(0, dash).trim().toInt()
                val b = t.substring(dash + 1).trim().toInt()
                val lo = minOf(a, b)
                val hi = maxOf(a, b)
                if (hi - lo > 300) continue
                for (c in lo..hi) out.add(c)
            } else {
                out.add(t.toInt())
            }
        } catch (e: NumberFormatException) {
            // 숫자가 아닌 값은 무시
        }
    }
    return out
}

fun channelsToText(set: Set<Int>): String = set.sorted().joinToString(",")

/** Wi-Fi 한 개(BSSID)의 정보 */
class WifiInfo(
    val ssid: String,
    val bssid: String,
    val freqMhz: Int,
    val channel: Int,
    val band: String,
    val security: String
)

/** BLE 광고 패킷에서 읽어낸 정보. 같은 기기의 여러 패킷은 merge로 합친다. */
class BleAdv(
    val name: String = "",
    val mfrId: Int = -1,
    val uuids16: Set<Int> = emptySet(),
    val hasOtherUuid: Boolean = false,
    val appearance: Int = -1,
    val txPower: Int = NO_TX,
    val hints: Set<String> = emptySet(),
    val appleModel: Int = -1
) {
    fun merge(o: BleAdv): BleAdv = BleAdv(
        name = if (o.name.isNotEmpty()) o.name else name,
        mfrId = if (o.mfrId >= 0) o.mfrId else mfrId,
        uuids16 = uuids16 + o.uuids16,
        hasOtherUuid = hasOtherUuid || o.hasOtherUuid,
        appearance = if (o.appearance >= 0) o.appearance else appearance,
        txPower = if (o.txPower != NO_TX) o.txPower else txPower,
        hints = hints + o.hints,
        appleModel = if (o.appleModel >= 0) o.appleModel else appleModel
    )

    companion object {
        const val NO_TX = Int.MIN_VALUE
        val EMPTY = BleAdv()
    }
}

/** 분석 결과 */
class Analysis(
    val cls: DeviceClass,
    val detail: String,
    val score: Int,
    val reasons: List<String>,
    val flags: Set<String>
) {
    /** 0=일반, 1=주의, 2=의심, 3=강한 의심 */
    val level: Int
        get() = when {
            score >= 4 -> 3
            score >= 2 -> 2
            score >= 1 -> 1
            else -> 0
        }

    val suspect: Boolean get() = score >= 2

    companion object {
        val NONE = Analysis(DeviceClass.NAMED_OTHER, "", 0, emptyList(), emptySet())
    }
}

/** BLE 광고 원시 바이트(AD 구조) 해석 */
object BleParser {
    private const val APPLE = 0x004C

    fun parse(bytes: ByteArray?): BleAdv {
        if (bytes == null) return BleAdv()
        var name = ""
        var mfrId = -1
        var appearance = -1
        var tx = BleAdv.NO_TX
        var otherUuid = false
        var appleModel = -1
        val uuids = HashSet<Int>()
        val hints = HashSet<String>()

        var i = 0
        while (i < bytes.size) {
            val len = bytes[i].toInt() and 0xFF
            if (len == 0 || i + len >= bytes.size) break
            val type = bytes[i + 1].toInt() and 0xFF
            val data = bytes.copyOfRange(i + 2, i + len + 1)
            when (type) {
                0x08, 0x09 -> {
                    val s = String(data, Charsets.UTF_8).trim { it.code <= 32 }
                    if (s.isNotEmpty() && (type == 0x09 || name.isEmpty())) name = s
                }
                0x02, 0x03 -> {
                    var k = 0
                    while (k + 1 < data.size) {
                        uuids.add(le16(data, k))
                        k += 2
                    }
                }
                0x04, 0x05, 0x06, 0x07 -> otherUuid = true
                0x16 -> if (data.size >= 2) uuids.add(le16(data, 0))
                0x0A -> if (data.isNotEmpty()) tx = data[0].toInt()
                0x19 -> if (data.size >= 2) appearance = le16(data, 0)
                0xFF -> if (data.size >= 2) {
                    mfrId = le16(data, 0)
                    if (mfrId == APPLE) {
                        val m = parseApple(data, 2, hints)
                        if (m >= 0) appleModel = m
                    }
                }
                else -> {}
            }
            i += len + 1
        }
        return BleAdv(name, mfrId, uuids, otherUuid, appearance, tx, hints, appleModel)
    }

    private fun le16(d: ByteArray, o: Int): Int =
        (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)

    /** Apple 연속성(Continuity) 메시지: [종류][길이][데이터] 가 이어진 구조 */
    private fun parseApple(d: ByteArray, off: Int, hints: MutableSet<String>): Int {
        var model = -1
        var j = off
        while (j + 1 < d.size) {
            val t = d[j].toInt() and 0xFF
            var l = d[j + 1].toInt() and 0xFF
            val s = j + 2
            if (s + l > d.size) l = d.size - s
            when (t) {
                0x02 -> hints.add("APPLE_IBEACON")
                0x05 -> hints.add("APPLE_AIRDROP")
                0x06 -> hints.add("APPLE_HOMEKIT")
                0x07 -> {
                    hints.add("APPLE_PP")
                    if (l >= 3) {
                        model = ((d[s + 1].toInt() and 0xFF) shl 8) or (d[s + 2].toInt() and 0xFF)
                    }
                }
                0x09, 0x0A -> hints.add("APPLE_AIRPLAY")
                0x0C -> hints.add("APPLE_HANDOFF")
                0x0D, 0x0E -> hints.add("APPLE_HOTSPOT")
                0x0F -> hints.add("APPLE_NEARBY")
                0x10 -> {
                    hints.add("APPLE_NEARBY")
                    // 활동 코드 0x0A = 손목에 착용 중인 Apple Watch
                    if (l >= 1 && (d[s].toInt() and 0x0F) == 0x0A) hints.add("APPLE_WATCH")
                }
                0x12 -> hints.add("APPLE_FINDMY")
                else -> {}
            }
            j = s + l
        }
        return model
    }
}

object Analyzer {

    private val APPLE_MODELS = mapOf(
        0x0220 to "AirPods 1세대", 0x0F20 to "AirPods 2세대", 0x1320 to "AirPods 3세대",
        0x0E20 to "AirPods Pro", 0x1420 to "AirPods Pro 2", 0x0A20 to "AirPods Max",
        0x0B20 to "Powerbeats Pro", 0x0520 to "BeatsX", 0x0620 to "Beats Solo3",
        0x0320 to "Powerbeats3", 0x0C20 to "Beats Solo Pro", 0x1020 to "Beats Flex",
        0x1120 to "Beats Studio Buds"
    )

    private val HOTSPOT_WORDS = listOf(
        "iphone", "ipad", "androidap", "android ap", "galaxy", "sm-", "pixel", "oneplus",
        "hotspot", "tether", "mobile ap", "핫스팟", "갤럭시", "아이폰", "테더링"
    )

    private class NameRule(val regex: Regex, val cls: DeviceClass, val detail: String)

    private fun rule(pattern: String, cls: DeviceClass, detail: String) =
        NameRule(Regex(pattern, RegexOption.IGNORE_CASE), cls, detail)

    // 위에서부터 먼저 맞는 규칙을 사용한다 (예: 'Galaxy Buds'는 폰 규칙보다 먼저 이어폰으로 잡힘)
    private val NAME_RULES = listOf(
        rule(
            """airpods|buds|earbud|earphone|earpods|headset|headphone|\bwf-|\bwh-|\btws\b|\bpods?\b|freebuds|soundcore|liberty|jabra|powerbeats|beats (solo|studio|flex|fit)|nothing ear|\bear\s*\(\d|이어폰|이어버드|버즈|헤드셋|헤드폰""",
            DeviceClass.EARBUDS, "무선 이어폰·헤드셋 (이름 기준 추정)"
        ),
        rule(
            """watch|smart ?band|mi ?band|galaxy fit|fitbit|gear (s|fit|sport)|versa|forerunner|fenix|amazfit|ticwatch|garmin|\bband\b|워치|스마트밴드|시계""",
            DeviceClass.WATCH, "스마트워치·밴드 (이름 기준 추정)"
        ),
        rule(
            """sm-r|galaxy wearable""",
            DeviceClass.WEARABLE, "삼성 웨어러블 (워치·버즈 계열 추정)"
        ),
        rule(
            """ipad|galaxy tab|갤럭시 ?탭|sm-t|sm-x|tablet|matepad|mi ?pad|lenovo tab|태블릿""",
            DeviceClass.TABLET, "태블릿 (이름 기준 추정)"
        ),
        rule(
            """iphone|galaxy (s|a|z|note|m|f)\d|갤럭시 ?(s|a|z|노트)|sm-[sagnfmje]\d|pixel \d|redmi note|아이폰""",
            DeviceClass.PHONE, "스마트폰 (이름 기준 추정)"
        ),
        rule(
            """airtag|smarttag|\btile\b|tracker|chipolo|pebblebee|\btag\b""",
            DeviceClass.TAG, "소형 추적 태그 (이름 기준 추정)"
        ),
        rule(
            """macbook|imac|mac ?mini|laptop|notebook|desktop|thinkpad|galaxy ?book|lg gram|\bgram\b|노트북|데스크탑|\bpc\b|-pc\b""",
            DeviceClass.COMPUTER, "컴퓨터 (이름 기준 추정)"
        ),
        rule(
            """\btv\b|speaker|soundbar|homepod|\bjbl\b|sonos|\bbose\b|\bboom\b|\becho\b|스피커""",
            DeviceClass.AUDIO_TV, "스피커·TV (이름 기준 추정)"
        ),
        rule(
            """keyboard|mouse|trackpad|\bk\d{3}\b|mx (master|keys|anywhere)|remote|pencil|\bpen\b|gamepad|controller|키보드|마우스|리모컨""",
            DeviceClass.PERIPHERAL, "입력 주변기기 (이름 기준 추정)"
        )
    )

    fun companyName(id: Int): String = when (id) {
        0x004C -> "Apple"
        0x0075 -> "Samsung"
        0x00E0 -> "Google"
        0x0006 -> "Microsoft"
        0x0087 -> "Garmin"
        0x038F -> "Xiaomi"
        0x027D -> "Huawei"
        0x012D -> "Sony"
        0x009E -> "Bose"
        0x005D -> "Realtek"
        0x0059 -> "Nordic Semiconductor"
        0x02E5 -> "Espressif(ESP32 칩)"
        0x0211 -> "Telink"
        0x00D2 -> "Dialog Semiconductor"
        0x000A -> "Qualcomm"
        0x0002 -> "Intel"
        else -> String.format("0x%04X", id)
    }

    fun bleExtra(a: BleAdv): String {
        val parts = ArrayList<String>()
        if (a.mfrId >= 0) parts.add("제조사 " + companyName(a.mfrId))
        if (a.txPower != BleAdv.NO_TX) parts.add("TX " + a.txPower + "dBm")
        return parts.joinToString(" · ")
    }

    fun byName(name: String): Pair<DeviceClass, String>? {
        for (r in NAME_RULES) {
            if (r.regex.containsMatchIn(name)) return Pair(r.cls, r.detail)
        }
        return null
    }

    private fun byAppearance(ap: Int): Pair<DeviceClass, String>? = when (ap shr 6) {
        1 -> Pair(DeviceClass.PHONE, "스마트폰 (기기가 직접 알림)")
        2 -> Pair(DeviceClass.COMPUTER, "컴퓨터 (기기가 직접 알림)")
        3 -> Pair(DeviceClass.WATCH, "스마트워치 (기기가 직접 알림)")
        8 -> Pair(DeviceClass.TAG, "소형 태그 (기기가 직접 알림)")
        13 -> Pair(DeviceClass.WEARABLE, "심박·건강 센서 (웨어러블 가능)")
        15 -> Pair(DeviceClass.PERIPHERAL, "입력 주변기기 (기기가 직접 알림)")
        37 -> Pair(DeviceClass.EARBUDS, "무선 이어폰·헤드셋 (기기가 직접 알림)")
        else -> null
    }

    private fun classifyBle(name: String, a: BleAdv): Pair<DeviceClass, String> {
        val h = a.hints
        // 1) Apple 연속성 메시지 중 종류가 분명한 것
        if (h.contains("APPLE_PP")) {
            val m = APPLE_MODELS[a.appleModel]
            return Pair(DeviceClass.EARBUDS, "무선 이어폰 (" + (m ?: "AirPods·Beats 계열") + " 추정)")
        }
        if (h.contains("APPLE_WATCH")) {
            return Pair(DeviceClass.WATCH, "스마트워치 (Apple Watch 추정)")
        }
        // 2) 기기가 직접 알리는 Appearance
        if (a.appearance >= 0) {
            val r = byAppearance(a.appearance)
            if (r != null) return r
        }
        // 3) 이름
        if (name.isNotEmpty()) {
            val r = byName(name)
            if (r != null) return r
        }
        // 4) 서비스 UUID
        val u = a.uuids16
        if (u.contains(0xFE2C)) return Pair(DeviceClass.EARBUDS, "무선 이어폰·액세서리 (Google Fast Pair 추정)")
        if (u.contains(0x180D)) return Pair(DeviceClass.WEARABLE, "심박 센서·웨어러블 (심박 서비스 제공)")
        if (u.contains(0xFEED) || u.contains(0xFD84)) return Pair(DeviceClass.TAG, "Tile 추적 태그")
        if (u.contains(0xFD5A) || u.contains(0xFD59)) return Pair(DeviceClass.TAG, "삼성 SmartTag 추정")
        if (u.contains(0xFEAA)) return Pair(DeviceClass.BEACON, "Eddystone 비콘")
        if (u.contains(0x1812)) return Pair(DeviceClass.PERIPHERAL, "HID 입력 주변기기")
        if (u.contains(0xFE95)) return Pair(DeviceClass.SMALL_IOT, "샤오미 계열 기기 (밴드·이어폰·IoT)")
        // 5) Apple 기타 메시지
        if (h.contains("APPLE_FINDMY")) return Pair(DeviceClass.TAG, "Find My 태그 (에어태그·분실방지 기기 추정)")
        if (h.contains("APPLE_IBEACON")) return Pair(DeviceClass.BEACON, "iBeacon 비콘")
        if (h.contains("APPLE_AIRPLAY")) return Pair(DeviceClass.AUDIO_TV, "AirPlay 기기 (Apple TV·스피커 추정)")
        if (h.contains("APPLE_HOMEKIT")) return Pair(DeviceClass.NAMED_OTHER, "HomeKit 액세서리")
        if (h.contains("APPLE_NEARBY") || h.contains("APPLE_HANDOFF") ||
            h.contains("APPLE_AIRDROP") || h.contains("APPLE_HOTSPOT")
        ) {
            return Pair(DeviceClass.PHONE_TAB, "Apple 스마트폰·태블릿·맥 (패킷만으로 구분 불가)")
        }
        // 6) 제조사
        when (a.mfrId) {
            0x004C -> return Pair(DeviceClass.NAMED_OTHER, "Apple 기기 (종류 불명)")
            0x0059, 0x02E5, 0x0211, 0x00D2 ->
                return Pair(DeviceClass.SMALL_IOT, "소형 통신기기 칩 제조사 신호 (" + companyName(a.mfrId) + ")")
            0x038F -> return Pair(DeviceClass.SMALL_IOT, "샤오미 계열 기기 (밴드·이어폰·IoT)")
            0x0087 -> return Pair(DeviceClass.WEARABLE, "Garmin 웨어러블 추정")
            0x0006 -> return Pair(DeviceClass.COMPUTER, "Microsoft 기기 (PC·주변기기 추정)")
            0x0075 -> return Pair(DeviceClass.NAMED_OTHER, "삼성 기기 (종류 불명)")
            0x00E0 -> return Pair(DeviceClass.NAMED_OTHER, "Google·Android 기기 (종류 불명)")
            else -> {}
        }
        if (a.mfrId >= 0) {
            return Pair(DeviceClass.NAMED_OTHER, "제조사 " + companyName(a.mfrId) + " 기기 (종류 불명)")
        }
        if (name.isNotEmpty()) return Pair(DeviceClass.NAMED_OTHER, "이름 있는 기기 (종류 불명)")
        if (u.isNotEmpty() || a.hasOtherUuid) {
            return Pair(DeviceClass.NAMED_OTHER, "서비스 정보만 있는 기기 (종류 불명)")
        }
        return Pair(DeviceClass.UNKNOWN_ANON, "이름·제조사·서비스 정보가 없는 미확인 소형 기기")
    }

    private fun classifyClassic(name: String, btClass: Int): Pair<DeviceClass, String> {
        if (btClass >= 0) {
            val major = btClass and 0x1F00
            if (major == 0x0400) {
                return if (btClass == 0x0404 || btClass == 0x0408 || btClass == 0x0418) {
                    Pair(DeviceClass.EARBUDS, "무선 이어폰·헤드셋 (블루투스 기기 분류)")
                } else {
                    Pair(DeviceClass.AUDIO_TV, "오디오·영상 기기 (블루투스 기기 분류)")
                }
            }
            if (major == 0x0200) return Pair(DeviceClass.PHONE, "스마트폰 (블루투스 기기 분류)")
            if (major == 0x0100) return Pair(DeviceClass.COMPUTER, "컴퓨터 (블루투스 기기 분류)")
            if (major == 0x0700) {
                return if (btClass == 0x0704) {
                    Pair(DeviceClass.WATCH, "손목시계 (블루투스 기기 분류)")
                } else {
                    Pair(DeviceClass.WEARABLE, "웨어러블 (블루투스 기기 분류)")
                }
            }
            if (major == 0x0900) return Pair(DeviceClass.WEARABLE, "건강 기기 (블루투스 기기 분류)")
            if (major == 0x0500) return Pair(DeviceClass.PERIPHERAL, "입력 주변기기 (블루투스 기기 분류)")
        }
        if (name.isNotEmpty()) {
            val r = byName(name)
            if (r != null) return r
            return Pair(DeviceClass.NAMED_OTHER, "이름 있는 기기 (종류 불명)")
        }
        return Pair(DeviceClass.UNKNOWN_ANON, "이름 없는 미확인 기기")
    }

    fun analyze(
        type: SignalType,
        name: String,
        rssi: Int,
        adv: BleAdv,
        wifi: WifiInfo?,
        btClass: Int,
        cfg: AnalyzerConfig
    ): Analysis {
        return when (type) {
            SignalType.WIFI -> if (wifi != null) analyzeWifi(wifi, rssi, cfg) else Analysis.NONE
            SignalType.BLE -> analyzeDevice(name.ifBlank { adv.name }, rssi, adv, -1, true)
            SignalType.BT_CLASSIC -> analyzeDevice(name, rssi, BleAdv.EMPTY, btClass, false)
        }
    }

    private fun analyzeDevice(name: String, rssi: Int, adv: BleAdv, btClass: Int, isBle: Boolean): Analysis {
        val n = name.trim()
        val c = if (isBle) classifyBle(n, adv) else classifyClassic(n, btClass)
        val cls = c.first
        var score = 0
        val reasons = ArrayList<String>()
        val flags = HashSet<String>()

        when (cls) {
            DeviceClass.EARBUDS -> {
                score += 3
                reasons.add("무선 이어폰 계열")
            }
            DeviceClass.WATCH, DeviceClass.WEARABLE -> {
                score += 2
                reasons.add("스마트워치·웨어러블")
            }
            DeviceClass.PHONE, DeviceClass.TABLET, DeviceClass.PHONE_TAB -> {
                score += 1
                reasons.add("휴대폰·태블릿")
            }
            DeviceClass.SMALL_IOT -> {
                score += 2
                reasons.add("소형 통신기기(IoT) 계열")
            }
            DeviceClass.UNKNOWN_ANON -> {
                score += 2
                reasons.add("이름·식별 정보가 없는 소형 기기")
                flags.add("ANON")
            }
            DeviceClass.TAG -> {
                score += 1
                reasons.add("소형 추적 태그")
            }
            else -> {}
        }
        if (isBle && adv.hints.contains("APPLE_HOTSPOT")) {
            score += 2
            reasons.add("핫스팟 공유 관련 신호")
            flags.add("HOTSPOT")
        }
        if (score > 0 && rssi >= -50) {
            score += 1
            reasons.add("매우 가까움(" + rssi + "dBm)")
        }
        return Analysis(cls, c.second, score, reasons, flags)
    }

    private fun analyzeWifi(w: WifiInfo, rssi: Int, cfg: AnalyzerConfig): Analysis {
        val ssid = w.ssid.trim { it.code <= 32 }
        val hidden = ssid.isEmpty()
        val lower = ssid.lowercase()
        val hotspot = !hidden && HOTSPOT_WORDS.any { lower.contains(it) }
        val direct = lower.startsWith("direct-")
        val monitored = if (w.freqMhz < 3000) {
            cfg.ch24.contains(w.channel)
        } else if (w.freqMhz < 5925) {
            cfg.ch5.contains(w.channel)
        } else {
            false
        }
        val first = w.bssid.take(2).toIntOrNull(16) ?: 0
        val localMac = (first and 0x02) != 0

        var score = 0
        val reasons = ArrayList<String>()
        val flags = HashSet<String>()

        val cls: DeviceClass
        val detail: String
        if (hidden) {
            cls = DeviceClass.WIFI_HIDDEN
            detail = "숨겨진 SSID 네트워크"
        } else if (hotspot) {
            cls = DeviceClass.WIFI_HOTSPOT
            detail = "스마트폰 핫스팟 이름 패턴"
        } else if (direct) {
            cls = DeviceClass.WIFI_DIRECT
            detail = "Wi-Fi Direct (프린터·소형 기기·핫스팟)"
        } else {
            cls = DeviceClass.WIFI_AP
            detail = if (monitored) "감시 채널의 AP" else "일반 공유기·AP"
        }

        if (hidden) {
            score += 2
            reasons.add("SSID 숨김")
            flags.add("HIDDEN")
        }
        if (hotspot) {
            score += 3
            reasons.add("스마트폰 핫스팟 이름 패턴")
            flags.add("HOTSPOT")
        }
        if (direct) {
            score += 2
            reasons.add("Wi-Fi Direct(DIRECT-)")
            flags.add("DIRECT")
        }
        if (monitored) {
            score += 2
            reasons.add("감시 채널(" + w.band + " ch" + w.channel + ")")
            flags.add("MONITORED")
        }
        if (score > 0 && localMac) {
            score += 1
            reasons.add("임의(로컬) MAC 주소 — 핫스팟·가상 AP 가능성")
        }
        if (score > 0 && rssi >= -50) {
            score += 1
            reasons.add("매우 가까움(" + rssi + "dBm)")
        }
        return Analysis(cls, detail, score, reasons, flags)
    }
}
