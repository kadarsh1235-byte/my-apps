package com.adarsh.voxnoxfx

import android.content.Context
import android.media.*
import android.media.audiofx.*
import android.os.Process
import kotlin.math.max
import kotlin.math.min

object Engine {
    @Volatile var running = false
    @Volatile var error: String? = null
    @Volatile var presetId = 0
    @Volatile var semiOff = 0f
    @Volatile var gain = 1f
    @Volatile var gate = true
    @Volatile var useSysFx = true
    @Volatile var muteLive = false
    @Volatile var inDb = -90f
    @Volatile var outDb = -90f
    @Volatile var noiseDb = -90f
    @Volatile var clips = 0
    @Volatile var underruns = 0
    @Volatile var speech = false
    @Volatile var info = ""
    @Volatile var latencyMs = 0f
    @Volatile var sampleRate = 48000
    @Volatile var testing = false
    @Volatile var testPos = 0
    @Volatile var testRaw = ShortArray(0)
    @Volatile var testProc = ShortArray(0)
    @Volatile var onEnded: (() -> Unit)? = null
    private var th: Thread? = null

    @Synchronized fun start(ctx: Context) {
        if (running) return
        error = null; clips = 0; underruns = 0
        running = true
        val app = ctx.applicationContext
        th = Thread({ loop(app) }, "VoxNox-Audio").also { it.start() }
    }

    @Synchronized fun stop() {
        running = false
        th?.join(1500); th = null
    }

    private fun loop(ctx: Context) {
        var rec: AudioRecord? = null
        var track: AudioTrack? = null
        val fx = ArrayList<AudioEffect>()
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val prefSr = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
            val fpb = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 256
            var sr = 0; var minRec = 0
            for (c in intArrayOf(prefSr, 48000, 44100, 16000)) {
                val m = AudioRecord.getMinBufferSize(c, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (m > 0) { sr = c; minRec = m; break }
            }
            check(sr > 0) { "No supported capture sample rate" }
            sampleRate = sr
            val block = fpb * 2
            val src = if (useSysFx) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC
            rec = AudioRecord(src, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                max(minRec, block * 4))
            check(rec.state == AudioRecord.STATE_INITIALIZED) {
                "AudioRecord failed to initialise (permission denied, or microphone in use by another app)"
            }
            val names = ArrayList<String>()
            if (useSysFx) {
                val id = rec.audioSessionId
                if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(id)?.let { it.enabled = true; fx.add(it); names.add("NoiseSuppressor") }
                if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(id)?.let { it.enabled = true; fx.add(it); names.add("AGC") }
                if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(id)?.let { it.enabled = true; fx.add(it); names.add("AEC") }
            }
            val minTr = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(max(minTr, block * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            latencyMs = (block + track.bufferSizeInFrames) * 1000f / sr
            info = "${sr} Hz, block $block frames, source ${if (useSysFx) "VOICE_COMMUNICATION" else "MIC"}, " +
                "platform effects: ${if (names.isEmpty()) (if (useSysFx) "none available on this device" else "off") else names.joinToString("+")}"
            val inBuf = ShortArray(block); val outBuf = ShortArray(block)
            val dsp = Dsp(sr)
            track.play(); rec.startRecording()
            while (running) {
                val n = rec.read(inBuf, 0, block)
                check(n >= 0) { "AudioRecord.read error $n" }
                if (n == 0) continue
                val p = Presets.all[presetId.coerceIn(0, Presets.all.size - 1)]
                dsp.block(inBuf, outBuf, n, p, semiOff, gate, gain)
                inDb = dsp.inDb; outDb = dsp.outDb; noiseDb = dsp.noiseDb; clips = dsp.clips; speech = dsp.speech
                if (testing) {
                    val pos = testPos; val c = min(n, testRaw.size - pos)
                    if (c > 0) {
                        System.arraycopy(inBuf, 0, testRaw, pos, c)
                        System.arraycopy(outBuf, 0, testProc, pos, c)
                        testPos = pos + c
                    }
                    if (testPos >= testRaw.size) testing = false
                }
                if (muteLive) outBuf.fill(0, 0, n)
                val w = track.write(outBuf, 0, n)
                check(w >= 0) { "AudioTrack.write error $w" }
                underruns = track.underrunCount
            }
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        } finally {
            try { rec?.stop() } catch (_: Exception) {}
            rec?.release()
            fx.forEach { try { it.release() } catch (_: Exception) {} }
            try { track?.stop() } catch (_: Exception) {}
            track?.release()
            running = false; testing = false
            onEnded?.invoke()
        }
    }

    /** Plays the recorded test through the speaker/headset. Live monitor is muted meanwhile. */
    fun playTest(raw: Boolean, done: () -> Unit) {
        val data = if (raw) testRaw else testProc
        val len = min(testPos, data.size)
        if (len <= 0) { done(); return }
        muteLive = true
        Thread {
            try {
                val sr = sampleRate
                val t = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(max(AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT), 8192))
                    .setTransferMode(AudioTrack.MODE_STREAM).build()
                t.play(); t.write(data, 0, len)
                Thread.sleep(len * 1000L / sr + 250)
                t.stop(); t.release()
            } catch (_: Exception) {
            } finally { muteLive = false; done() }
        }.start()
    }
}
