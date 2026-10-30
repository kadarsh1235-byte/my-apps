package com.adarsh.voxnoxfx

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("vox", MODE_PRIVATE) }
    private lateinit var status: TextView
    private lateinit var meters: TextView
    private lateinit var devices: TextView
    private lateinit var testInfo: TextView
    private lateinit var inBar: ProgressBar
    private lateinit var outBar: ProgressBar
    private lateinit var startBtn: Button
    private lateinit var am: AudioManager
    private var wasTesting = false
    private val devCb = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(a: Array<out AudioDeviceInfo>?) { devices.text = deviceText() }
        override fun onAudioDevicesRemoved(a: Array<out AudioDeviceInfo>?) { devices.text = deviceText() }
    }
    private val ticker = object : Runnable {
        override fun run() { refresh(); h.postDelayed(this, 80) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(t: String) = TextView(this).apply {
        text = t; setTextColor(0xFFE6E6FF.toInt()); textSize = 14f; setPadding(0, dp(6), 0, dp(2))
    }
    private fun bar() = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
    private fun seek(max: Int, init: Int, cb: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max; progress = init
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { cb(p) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }
    private fun check(text: String, init: Boolean, cb: (Boolean) -> Unit) = CheckBox(this).apply {
        this.text = text; setTextColor(0xFFE6E6FF.toInt()); isChecked = init
        setOnCheckedChangeListener { _, c -> cb(c) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        am = getSystemService(AUDIO_SERVICE) as AudioManager
        Engine.presetId = prefs.getInt("preset", 0)
        Engine.semiOff = prefs.getInt("semi", 12) - 12f
        Engine.gain = prefs.getInt("gain", 100) / 100f
        Engine.gate = prefs.getBoolean("gate", true)
        Engine.useSysFx = prefs.getBoolean("sysfx", true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(24))
            setBackgroundColor(0xFF0A0A12.toInt())
        }
        root.addView(TextView(this).apply {
            text = "VoxNox FX  •  Adarsh Kumar"; textSize = 22f; setTextColor(0xFF7C4DFF.toInt())
        })
        status = label(""); root.addView(status)
        startBtn = Button(this).apply { setOnClickListener { toggle() } }; root.addView(startBtn)

        root.addView(label("Voice preset / आवाज़ प्रीसेट"))
        root.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, Presets.all.map { it.name })
            setSelection(Engine.presetId)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    Engine.presetId = pos; prefs.edit().putInt("preset", pos).apply()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        })
        root.addView(label("Pitch offset (−12…+12 semitones)"))
        root.addView(seek(24, prefs.getInt("semi", 12)) { Engine.semiOff = it - 12f; prefs.edit().putInt("semi", it).apply() })
        root.addView(label("Output volume (0–200%)"))
        root.addView(seek(200, prefs.getInt("gain", 100)) { Engine.gain = it / 100f; prefs.edit().putInt("gain", it).apply() })
        root.addView(check("Noise gate (simple DSP, not AI)", Engine.gate) { Engine.gate = it; prefs.edit().putBoolean("gate", it).apply() })
        root.addView(check("Platform NS/AGC/AEC if device has them (applies on next Start)", Engine.useSysFx) {
            Engine.useSysFx = it; prefs.edit().putBoolean("sysfx", it).apply()
        })

        root.addView(label("Input level / माइक")); inBar = bar(); root.addView(inBar)
        root.addView(label("Output level")); outBar = bar(); root.addView(outBar)
        meters = label(""); root.addView(meters)

        root.addView(label("— Microphone test —"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply { text = "Record 4s"; setOnClickListener { recordTest() } })
        row.addView(Button(this).apply { text = "Play original"; setOnClickListener { Engine.playTest(true) {} } })
        row.addView(Button(this).apply { text = "Play processed"; setOnClickListener { Engine.playTest(false) {} } })
        root.addView(row)
        testInfo = label("Start processing first, then Record. Use headphones to avoid feedback. Recordings stay in RAM only and are never saved or uploaded.")
        root.addView(testInfo)

        root.addView(label("— Audio devices —")); devices = label(""); root.addView(devices)
        root.addView(label("— Free Fire integration —"))
        root.addView(label("Status: NOT CONNECTED.\nEffects work INSIDE VoxNox FX only. On Android 11 (unrooted) an app cannot " +
            "feed audio into Free Fire's microphone; the foreground game's mic capture takes priority. " +
            "Teammates will hear your normal voice unless you use external routing (e.g. a second device or hardware voice " +
            "changer connected as a wired/USB headset mic). Not tested by this app."))
        setContentView(ScrollView(this).apply { addView(root) })
        am.registerAudioDeviceCallback(devCb, null)
        devices.text = deviceText()
    }

    override fun onResume() { super.onResume(); h.post(ticker) }
    override fun onPause() { h.removeCallbacks(ticker); super.onPause() }
    override fun onDestroy() { am.unregisterAudioDeviceCallback(devCb); super.onDestroy() }

    private fun toggle() {
        if (Engine.running) {
            startService(Intent(this, VoxService::class.java).setAction("STOP")); return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        startForegroundService(Intent(this, VoxService::class.java))
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        if (r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) toggle()
        else Toast.makeText(this, "Microphone permission denied — processing cannot start.", Toast.LENGTH_LONG).show()
    }

    private fun recordTest() {
        if (!Engine.running) { Toast.makeText(this, "Start processing first.", Toast.LENGTH_SHORT).show(); return }
        val n = Engine.sampleRate * 4
        Engine.testRaw = ShortArray(n); Engine.testProc = ShortArray(n); Engine.testPos = 0
        Engine.testing = true; wasTesting = true
        testInfo.text = "Recording 4 seconds — speak now…"
    }

    private fun analyze() {
        val d = Engine.testRaw; val n = Engine.testPos
        if (n == 0) return
        var pk = 0; var sum = 0.0; var clip = 0
        for (i in 0 until n) { val a = abs(d[i].toInt()); if (a > pk) pk = a; if (a >= 32700) clip++; sum += a.toDouble() * a }
        val peakDb = 20 * log10(max(pk, 1) / 32768.0)
        val rmsDb = 20 * log10(max(Math.sqrt(sum / n), 1.0) / 32768.0)
        val advice = when {
            pk < 200 -> "Almost no signal. Check permission, mic hole not blocked, another app using the mic. If still silent in other apps, the mic may be faulty — software cannot repair hardware."
            clip > 5 -> "Clipping detected: speak further from the mic or lower input gain."
            peakDb < -30 -> "Very quiet: move closer to the mic; consider raising Output volume."
            else -> "Level looks usable."
        }
        testInfo.text = "Test result: peak %.1f dBFS, avg %.1f dBFS, clipped samples %d, noise floor %.1f dBFS.\n%s\nNow compare Play original vs Play processed."
            .format(peakDb, rmsDb, clip, Engine.noiseDb, advice)
    }

    private fun pct(db: Float) = ((db + 80f) / 80f * 100f).coerceIn(0f, 100f).toInt()

    private fun refresh() {
        val on = Engine.running
        startBtn.text = if (on) "STOP / रोकें" else "START / शुरू"
        val err = Engine.error
        status.text = when {
            err != null && !on -> "ERROR: $err"
            on -> "Processing ON (mic is live, offline). ${Engine.info}"
            else -> "Stopped. Offline — no network permission requested."
        }
        inBar.progress = pct(Engine.inDb); outBar.progress = pct(Engine.outDb)
        meters.text = "In %.0f dB | Out %.0f dB | noise floor %.0f dB | speech: %s | clips: %d | underruns: %d\nBuffer-based latency estimate: %.0f ms (NOT measured end-to-end; Bluetooth adds more)"
            .format(Engine.inDb, Engine.outDb, Engine.noiseDb, if (Engine.speech) "yes" else "no", Engine.clips, Engine.underruns, Engine.latencyMs)
        if (wasTesting && !Engine.testing) { wasTesting = false; analyze() }
    }

    private fun typeName(t: Int) = when (t) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "built-in mic"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO (call-quality)"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "built-in speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        else -> "type $t"
    }

    private fun deviceText(): String {
        val ins = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val headOut = outs.any { it.type in intArrayOf(AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) }
        return "Inputs: " + ins.joinToString { typeName(it.type) } +
            "\nOutputs: " + outs.joinToString { typeName(it.type) } +
            if (headOut) "" else "\n⚠ No headphones detected: live monitoring through the speaker will cause feedback/echo."
    }
}
