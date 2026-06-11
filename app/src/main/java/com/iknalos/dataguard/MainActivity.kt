package com.iknalos.dataguard

import android.Manifest
import android.app.Activity
import android.app.usage.NetworkStats
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var repo: DataUsageRepository
    private var pendingMbps = 0

    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                startThrottle(pendingMbps)
            } else {
                Toast.makeText(this, "VPN permission is required to cap speed", Toast.LENGTH_SHORT).show()
                syncSpeedSelector()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        repo = DataUsageRepository(this)

        findViewById<EditText>(R.id.inputCap).setText(trimNumber(prefs.capGb))
        findViewById<EditText>(R.id.inputCycleDay).setText(prefs.cycleDay.toString())

        findViewById<Button>(R.id.btnGrant).setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveSettings() }

        syncSpeedSelector()
        findViewById<RadioGroup>(R.id.speedGroup).setOnCheckedChangeListener { _, checkedId ->
            val mbps = when (checkedId) {
                R.id.rbSpeed2 -> 2
                R.id.rbSpeed5 -> 5
                R.id.rbSpeed10 -> 10
                else -> 0
            }
            if (mbps != ThrottleVpnService.currentMbps) onSpeedSelected(mbps)
        }

        syncHotspotControls()
        findViewById<Switch>(R.id.switchHotspot).setOnCheckedChangeListener { btn, isChecked ->
            if (!btn.isPressed) return@setOnCheckedChangeListener // ignore programmatic sync
            if (isChecked) startHotspotProxy(selectedHotspotMbps()) else stopHotspotProxy()
        }
        findViewById<RadioGroup>(R.id.hotspotSpeedGroup).setOnCheckedChangeListener { _, _ ->
            if (HotspotProxyService.currentMbps != 0) startHotspotProxy(selectedHotspotMbps())
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
            )
        }

        Notifier.ensureChannel(this)
        scheduleWorker()
    }

    override fun onResume() {
        super.onResume()
        syncSpeedSelector()
        syncHotspotControls()
        refresh()
    }

    private fun selectedHotspotMbps(): Int =
        when (findViewById<RadioGroup>(R.id.hotspotSpeedGroup).checkedRadioButtonId) {
            R.id.rbHotspot2 -> 2
            R.id.rbHotspot10 -> 10
            else -> 5
        }

    private fun startHotspotProxy(mbps: Int) {
        startForegroundService(
            Intent(this, HotspotProxyService::class.java)
                .putExtra(HotspotProxyService.EXTRA_MBPS, mbps)
        )
        syncHotspotControls()
    }

    private fun stopHotspotProxy() {
        startService(
            Intent(this, HotspotProxyService::class.java)
                .setAction(HotspotProxyService.ACTION_STOP)
        )
        syncHotspotControls()
    }

    private fun syncHotspotControls() {
        val on = HotspotProxyService.currentMbps != 0
        val sw = findViewById<Switch>(R.id.switchHotspot)
        if (sw.isChecked != on) sw.isChecked = on
        val info = findViewById<TextView>(R.id.txtHotspotProxy)
        if (on) {
            val ip = NetworkUtils.hotspotAddress()
            info.text = if (ip != null) {
                "On each connected device, open Wi-Fi settings → your hotspot → Proxy = Manual, " +
                    "Host = $ip, Port = ${HotspotProxyService.PORT}. " +
                    "Capped at ${HotspotProxyService.currentMbps} Mbps."
            } else {
                "Turn your hotspot ON first, then toggle this again so DataGuard can detect " +
                    "its IP address. Proxy port is ${HotspotProxyService.PORT}."
            }
            info.visibility = View.VISIBLE
        } else {
            info.visibility = View.GONE
        }
    }

    private fun onSpeedSelected(mbps: Int) {
        if (mbps == 0) {
            startService(
                Intent(this, ThrottleVpnService::class.java)
                    .setAction(ThrottleVpnService.ACTION_STOP)
            )
            return
        }
        val consent = VpnService.prepare(this)
        if (consent != null) {
            pendingMbps = mbps
            vpnConsent.launch(consent)
        } else {
            startThrottle(mbps)
        }
    }

    private fun startThrottle(mbps: Int) {
        startForegroundService(
            Intent(this, ThrottleVpnService::class.java)
                .putExtra(ThrottleVpnService.EXTRA_MBPS, mbps)
        )
    }

    private fun syncSpeedSelector() {
        val id = when (ThrottleVpnService.currentMbps) {
            2 -> R.id.rbSpeed2
            5 -> R.id.rbSpeed5
            10 -> R.id.rbSpeed10
            else -> R.id.rbSpeedOff
        }
        val group = findViewById<RadioGroup>(R.id.speedGroup)
        if (group.checkedRadioButtonId != id) group.check(id)
    }

    private fun scheduleWorker() {
        val request = PeriodicWorkRequestBuilder<UsageCheckWorker>(30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "usage_check", ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

    private fun saveSettings() {
        val cap = findViewById<EditText>(R.id.inputCap).text.toString().toDoubleOrNull()
        val day = findViewById<EditText>(R.id.inputCycleDay).text.toString().toIntOrNull()
        if (cap == null || cap <= 0 || day == null || day !in 1..31) {
            Toast.makeText(this, "Enter a valid cap (GB) and cycle day (1-31)", Toast.LENGTH_SHORT).show()
            return
        }
        prefs.capGb = cap
        prefs.cycleDay = day
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun refresh() {
        val granted = repo.hasUsageAccess()
        findViewById<View>(R.id.grantGroup).visibility = if (granted) View.GONE else View.VISIBLE
        findViewById<View>(R.id.statsGroup).visibility = if (granted) View.VISIBLE else View.GONE
        if (!granted) return

        val now = System.currentTimeMillis()
        val cycleStart = Prefs.cycleStart(prefs.cycleDay)
        val cycleEnd = Prefs.nextCycleStart(prefs.cycleDay)
        val capBytes = (prefs.capGb * 1e9).toLong()

        val cycleUsed = repo.getMobileBytes(cycleStart, now)
        val todayUsed = repo.getMobileBytes(Prefs.todayStart(), now)

        findViewById<TextView>(R.id.txtCycle).text =
            "${Format.bytes(cycleUsed)} / ${Format.bytes(capBytes)}"
        val progress = findViewById<ProgressBar>(R.id.progressCycle)
        progress.max = 1000
        progress.progress =
            if (capBytes > 0) (cycleUsed * 1000 / capBytes).coerceAtMost(1000).toInt() else 0

        findViewById<TextView>(R.id.txtToday).text = "Today: ${Format.bytes(todayUsed)}"

        val perApp = repo.getPerAppMobileBytes(cycleStart, now)
        val hotspotBytes =
            perApp.firstOrNull { it.uid == NetworkStats.Bucket.UID_TETHERING }?.bytes ?: 0L
        findViewById<TextView>(R.id.txtHotspot).text =
            "Hotspot this cycle: ${Format.bytes(hotspotBytes)}"

        val dateFmt = SimpleDateFormat("MMM d", Locale.US)
        val elapsedMs = (now - cycleStart).coerceAtLeast(3_600_000L)
        val perDay = cycleUsed.toDouble() / elapsedMs * 86_400_000.0
        val projected = perDay * (cycleEnd - cycleStart) / 86_400_000.0
        val projection = StringBuilder("Pace: ${Format.bytes(perDay.toLong())}/day. ")
        projection.append(
            "On track for ${Format.bytes(projected.toLong())} by ${dateFmt.format(Date(cycleEnd))}."
        )
        if (cycleUsed >= capBytes) {
            projection.append(" Cap already exceeded.")
        } else if (perDay > 0) {
            val runOut = now + ((capBytes - cycleUsed) / perDay * 86_400_000.0).toLong()
            if (runOut < cycleEnd) {
                projection.append(" At this pace you run out around ${dateFmt.format(Date(runOut))}!")
            }
        }
        findViewById<TextView>(R.id.txtProjection).text = projection.toString()

        val container = findViewById<LinearLayout>(R.id.appList)
        container.removeAllViews()
        for (app in perApp.take(12)) {
            val row = layoutInflater.inflate(R.layout.item_app_usage, container, false)
            row.findViewById<TextView>(R.id.appName).text = app.label
            row.findViewById<TextView>(R.id.appBytes).text = Format.bytes(app.bytes)
            container.addView(row)
        }
    }

    private fun trimNumber(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
}
