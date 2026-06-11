package com.iknalos.dataguard

import android.Manifest
import android.app.usage.NetworkStats
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
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
        refresh()
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
        val hotspotBytes = perApp.firstOrNull { it.uid == NetworkStats.Bucket.UID_TETHERING }?.bytes ?: 0L
        findViewById<TextView>(R.id.txtHotspot).text =
            "Hotspot this cycle: ${Format.bytes(hotspotBytes)}"

        // Pace + projection
        val dateFmt = SimpleDateFormat("MMM d", Locale.US)
        val elapsedMs = (now - cycleStart).coerceAtLeast(3_600_000L)
        val perDay = cycleUsed.toDouble() / elapsedMs * 86_400_000.0
        val projected = perDay * (cycleEnd - cycleStart) / 86_400_000.0
        val projection = StringBuilder("Pace: ${Format.bytes(perDay.toLong())}/day. ")
        projection.append("On track for ${Format.bytes(projected.toLong())} by ${dateFmt.format(Date(cycleEnd))}.")
        if (perDay > 0 && cycleUsed < capBytes) {
            val runOut = now + ((capBytes - cycleUsed) / perDay * 86_400_000.0).toLong()
            if (runOut < cycleEnd) {
                projection.append(" At this pace you run out around ${dateFmt.format(Date(runOut))}!")
            }
        } else if (cycleUsed >= capBytes) {
            projection.append(" Cap already exceeded.")
        }
        findViewById<TextView>(R.id.txtProjection).text = projection.toString()

        // Top consumers
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
