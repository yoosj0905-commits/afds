package com.example.signaldetector

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * 주변의 BLE / 클래식 블루투스 / Wi-Fi 신호를 주기적으로 탐색하고 측정값(RSSI)을 listener로 전달한다.
 * 모든 콜백은 메인 스레드에서 호출된다.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class SignalScanner(context: Context, private val listener: Listener) {

    interface Listener {
        fun onReading(r: Reading)
        fun onMessage(message: String)
    }

    companion object {
        private const val WIFI_SCAN_INTERVAL_MS = 6_000L
    }

    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val btAdapter: BluetoothAdapter? =
        (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val wifiManager: WifiManager? =
        app.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var running = false
    private var receiversRegistered = false
    private var bleScanner: BluetoothLeScanner? = null

    // ---- BLE -----------------------------------------------------------

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleBle(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (r in results) handleBle(r)
        }

        override fun onScanFailed(errorCode: Int) {
            listener.onMessage("BLE 스캔 실패 (코드 $errorCode)")
        }
    }

    private fun handleBle(r: ScanResult) {
        val dev = r.device ?: return
        // 광고 패킷 원시 바이트를 직접 해석 (제조사 데이터, UUID, Appearance, 이름 등)
        var adv = BleParser.parse(r.scanRecord?.bytes)
        if (adv.name.isEmpty()) {
            val n = try {
                r.scanRecord?.deviceName ?: dev.name ?: ""
            } catch (e: SecurityException) {
                ""
            }
            if (n.isNotEmpty()) adv = adv.merge(BleAdv(name = n))
        }
        listener.onReading(Reading(SignalType.BLE, dev.address, adv.name, r.rssi, "", adv = adv))
    }

    // ---- 클래식 블루투스 -----------------------------------------------

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val dev = getDevice(intent) ?: return
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                    if (rssi == Short.MIN_VALUE) return
                    var name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: ""
                    var btClass = -1
                    try {
                        if (name.isEmpty()) name = dev.name ?: ""
                        btClass = dev.bluetoothClass?.deviceClass ?: -1
                    } catch (e: SecurityException) {
                        // 권한 없음: 이름·분류 없이 진행
                    }
                    listener.onReading(
                        Reading(SignalType.BT_CLASSIC, dev.address, name, rssi.toInt(), "", btClass = btClass)
                    )
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (running) startClassicDiscovery()
                }
            }
        }
    }

    private fun getDevice(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    private fun startClassicDiscovery() {
        val a = btAdapter ?: return
        try {
            if (!a.isEnabled) return
            if (a.isDiscovering) a.cancelDiscovery()
            a.startDiscovery()
        } catch (e: SecurityException) {
            listener.onMessage("블루투스 권한이 없습니다.")
        }
    }

    // ---- Wi-Fi ---------------------------------------------------------

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            readWifiResults()
        }
    }

    private fun readWifiResults() {
        val wm = wifiManager ?: return
        val results = try {
            wm.scanResults
        } catch (e: SecurityException) {
            return
        }
        for (r in results) {
            val bssid = r.BSSID ?: continue
            val ssid = (r.SSID ?: "").trim { it.code <= 32 }
            val ch = channelOf(r.frequency)
            val band = when {
                r.frequency < 3000 -> "2.4GHz"
                r.frequency < 5925 -> "5GHz"
                else -> "6GHz"
            }
            val caps = r.capabilities ?: ""
            val sec = when {
                caps.contains("WPA3") || caps.contains("SAE") -> "WPA3"
                caps.contains("WPA2") -> "WPA2"
                caps.contains("WPA") -> "WPA"
                caps.contains("WEP") -> "WEP"
                else -> "개방"
            }
            val info = WifiInfo(ssid, bssid, r.frequency, ch, band, sec)
            listener.onReading(
                Reading(SignalType.WIFI, bssid, ssid, r.level, "$band ch$ch · $sec", wifi = info)
            )
        }
    }

    private fun channelOf(freq: Int): Int = when {
        freq == 2484 -> 14
        freq in 2412..2472 -> (freq - 2407) / 5
        freq in 5000..5900 -> (freq - 5000) / 5
        freq in 5955..7115 -> (freq - 5950) / 5
        else -> 0
    }

    private val wifiLoop = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                wifiManager?.startScan()
            } catch (e: SecurityException) {
                // 권한 없음: 무시
            }
            handler.postDelayed(this, WIFI_SCAN_INTERVAL_MS)
        }
    }

    // ---- 시작 / 중지 ---------------------------------------------------

    fun start() {
        if (running) return
        running = true

        val bt = btAdapter
        if (bt == null) {
            listener.onMessage("이 기기는 블루투스를 지원하지 않습니다.")
        } else if (!bt.isEnabled) {
            listener.onMessage("블루투스가 꺼져 있습니다. 켜야 블루투스 탐색이 됩니다.")
        }
        val wm = wifiManager
        if (wm != null && !wm.isWifiEnabled) {
            listener.onMessage("Wi-Fi가 꺼져 있습니다. 켜야 Wi-Fi 탐색이 됩니다.")
        }

        registerReceivers()

        // BLE
        try {
            bleScanner = btAdapter?.bluetoothLeScanner
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            bleScanner?.startScan(null, settings, bleCallback)
        } catch (e: SecurityException) {
            listener.onMessage("블루투스 권한이 없습니다.")
        }

        // 클래식 블루투스
        startClassicDiscovery()

        // Wi-Fi: 캐시된 결과를 먼저 읽고, 주기적으로 새 스캔 요청
        readWifiResults()
        handler.post(wifiLoop)
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(wifiLoop)
        try {
            bleScanner?.stopScan(bleCallback)
        } catch (e: Exception) {
            // 이미 중지되었거나 권한 없음
        }
        bleScanner = null
        try {
            btAdapter?.cancelDiscovery()
        } catch (e: Exception) {
            // 무시
        }
        unregisterReceivers()
    }

    private fun registerReceivers() {
        if (receiversRegistered) return
        val btFilter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        val wifiFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(btReceiver, btFilter, Context.RECEIVER_NOT_EXPORTED)
            app.registerReceiver(wifiReceiver, wifiFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(btReceiver, btFilter)
            app.registerReceiver(wifiReceiver, wifiFilter)
        }
        receiversRegistered = true
    }

    private fun unregisterReceivers() {
        if (!receiversRegistered) return
        try {
            app.unregisterReceiver(btReceiver)
        } catch (e: IllegalArgumentException) {
            // 이미 해제됨
        }
        try {
            app.unregisterReceiver(wifiReceiver)
        } catch (e: IllegalArgumentException) {
            // 이미 해제됨
        }
        receiversRegistered = false
    }
}
