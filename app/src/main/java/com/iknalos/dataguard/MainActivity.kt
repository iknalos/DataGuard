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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.Spinner
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

    private val capValues = intArrayOf(0, 1, 2, 5, 10)

    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                startThrottleService()
            } else {
                Toast.makeText(this, "VPN permission is required to cap speed", Toast.LENGTH_SHORT).show()
                syncSpeedSelector()
                refresh()
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
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Usage access isn't available on this device", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveSettings() }

        syncSpeedSelector()
        findViewById<RadioGroup>(R.id.speedGroup).setOnCheckedChangeListener { _, checkedId ->
            val mbps = when (checkedId) {
                R.id.rbSpeed1 -> 1
                R.id.rbSpeed2 -> 2
                R.id.rbSpeed5 -> 5
                R.id.rbSpeed10 -> 10
                else -> 0
            }
            if (mbps != prefs.globalMbps) {
                prefs.globalMbps = mbps
                applyThrottle()
            }
        }

        findViewById<RadioGroup>(R.id.usageRangeGroup).setOnCheckedChangeListener { _, _ -> refresh() }

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

    // ---- Global + per-app speed cap ----

    /** Starts the throttle service if any cap is set, otherwise stops it. */
    private fun applyThrottle() {
        val active = prefs.globalMbps > 0 || prefs.appCaps().isNotEmpty()
        if (!active) {
            startService(
                Intent(this, ThrottleVpnService::class.java)
                    .setAction(ThrottleVpnService.ACTION_STOP)
            )
            return
        }
        val consent = VpnService.prepare(this)
        if (consent != null) {
            vpnConsent.launch(consent)
        } else {
            startThrottleService()
        }
    }

    private fun startThrottleService() {
        startForegroundService(
            Intent(this, ThrottleVpnService::class.java)
                .setAction(ThrottleVpnService.ACTION_APPLY)
        )
    }

    private fun syncSpeedSelector() {
        val id = when (prefs.globalMbps) {
            1 -> R.id.rbSpeed1
            2 -> R.id.rbSpeed2
            5 -> R.id.rbSpeed5
            10 -> R.id.rbSpeed10
            else -> R.id.rbSpeedOff
        }
        val group = findViewById<RadioGroup>(R.id.speedGroup)
        if (group.checkedRadioButtonId != id) group.check(id)
    }

    // ---- Hotspot cap ----

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

    // ---- Data tracking + per-app list ----

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
        // On devices without a usage-access settings screen (e.g. Fire TV) there's
        // nothing to grant, so hide the prompt entirely and just keep the speed cap.
        val canRequestUsage = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .resolveActivity(packageManager) != null
        findViewById<View>(R.id.grantGroup).visibility =
            if (!granted && canRequestUsage) View.VISIBLE else View.GONE
        findViewById<View>(R.id.statsGroup).visibility = if (granted) View.VISIBLE else View.GONE
        if (!granted) return

        val now = System.currentTimeMillis()
        val cycleStart = Prefs.cycleStart(prefs.cycleDay)
        val cycleEnd = Prefs.nextCycleStart(prefs.cycleDay)
        val todayStart = Prefs.todayStart()
        val capBytes = (prefs.capGb * 1e9).toLong()

        val cycleUsed = repo.getMobileBytes(cycleStart, now)
        val todayUsed = repo.getMobileBytes(todayStart, now)

        findViewById<TextView>(R.id.txtCycle).text =
            "${Format.bytes(cycleUsed)} / ${Format.bytes(capBytes)}"
        val progress = findViewById<ProgressBar>(R.id.progressCycle)
        progress.max = 1000
        progress.progress =
            if (capBytes > 0) (cycleUsed * 1000 / capBytes).coerceAtMost(1000).toInt() else 0

        findViewById<TextView>(R.id.txtToday).text = "Today: ${Format.bytes(todayUsed)}"

        val hotspotBytes = repo.getPerAppBytesRaw(cycleStart, now)[NetworkStats.Bucket.UID_TETHERING] ?: 0L
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

        renderAppList(now, cycleStart, todayStart)
    }

    private fun renderAppList(now: Long, cycleStart: Long, todayStart: Long) {
        val showToday = findViewById<RadioGroup>(R.id.usageRangeGroup).checkedRadioButtonId == R.id.rbRangeToday
        val rangeStart = if (showToday) todayStart else cycleStart
        val perApp = repo.getPerAppMobileBytes(rangeStart, now)
        val hourMap = repo.getPerAppBytesRaw(now - 3_600_000L, now)
        val todayMap = repo.getPerAppBytesRaw(todayStart, now)
        val caps = prefs.appCaps()

        val container = findViewById<LinearLayout>(R.id.appList)
        container.removeAllViews()
        for (app in perApp) {
            val row = layoutInflater.inflate(R.layout.item_app_usage, container, false)
            row.findViewById<TextView>(R.id.appName).text = app.label
            row.findViewById<TextView>(R.id.appBytes).text = Format.bytes(app.bytes)
            row.findViewById<TextView>(R.id.appRecency).text = recencyLabel(app.uid, hourMap, todayMap)

            val spinner = row.findViewById<Spinner>(R.id.appCap)
            val pkg = app.pkg
            if (pkg == null) {
                // Tethering/system/removed buckets aren't real apps to cap.
                spinner.visibility = View.INVISIBLE
            } else {
                val adapter = ArrayAdapter.createFromResource(
                    this, R.array.cap_options, android.R.layout.simple_spinner_item
                )
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinner.adapter = adapter
                spinner.setSelection(capToIndex(caps[pkg] ?: 0), false)
                spinner.tag = false // not yet ready; ignore the initial callback
                spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        if (spinner.tag != true) return
                        prefs.setAppCap(pkg, capValues[position])
                        applyThrottle()
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
                spinner.post { spinner.tag = true }
            }
            container.addView(row)
        }
    }

    private fun recencyLabel(uid: Int, hourMap: Map<Int, Long>, todayMap: Map<Int, Long>): String = when {
        (hourMap[uid] ?: 0L) > 0L -> getString(R.string.recency_hour)
        (todayMap[uid] ?: 0L) > 0L -> getString(R.string.recency_today)
        else -> getString(R.string.recency_cycle)
    }

    private fun capToIndex(mbps: Int): Int = capValues.indexOf(mbps).let { if (it < 0) 0 else it }

    private fun trimNumber(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
}
