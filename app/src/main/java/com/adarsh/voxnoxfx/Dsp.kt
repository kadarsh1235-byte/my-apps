package com.adarsh.voxnoxfx

import kotlin.math.*

class Biquad {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
    private var a1 = 0f; private var a2 = 0f
    private var z1 = 0f; private var z2 = 0f
    fun highpass(sr: Int, f: Float, q: Float = 0.707f) {
        val w = 2f * PI.toFloat() * f / sr; val c = cos(w); val al = sin(w) / (2f * q); val a0 = 1f + al
        b0 = (1f + c) / 2f / a0; b1 = -(1f + c) / a0; b2 = b0; a1 = -2f * c / a0; a2 = (1f - al) / a0
    }
    fun lowpass(sr: Int, f: Float, q: Float = 0.707f) {
        val w = 2f * PI.toFloat() * f / sr; val c = cos(w); val al = sin(w) / (2f * q); val a0 = 1f + al
        b0 = (1f - c) / 2f / a0; b1 = (1f - c) / a0; b2 = b0; a1 = -2f * c / a0; a2 = (1f - al) / a0
    }
    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }
}

/**
 * Two-tap crossfaded delay-line pitch shifter (time-domain).
 * Known limits: does NOT preserve formants (large shifts sound "chipmunk/demon"),
 * and adds mild modulation artefacts. A formant-preserving shifter is Phase 4.
 */
class PitchShifter(sr: Int) {
    private val win = (sr * 0.03f).toInt()
    private val size = win * 2 + 8
    private val buf = FloatArray(size)
    private var w = 0
    private var phase = 0f

    private fun tap(p: Float): Float {
        var pos = w - p * win
        if (pos < 0f) pos += size
        var i0 = pos.toInt(); if (i0 >= size) i0 -= size
        val fr = pos - i0
        val i1 = if (i0 + 1 >= size) 0 else i0 + 1
        return buf[i0] * (1f - fr) + buf[i1] * fr
    }

    fun process(x: Float, ratio: Float): Float {
        buf[w] = x
        phase += (1f - ratio) / win
        if (phase >= 1f) phase -= 1f else if (phase < 0f) phase += 1f
        var p2 = phase + 0.5f; if (p2 >= 1f) p2 -= 1f
        val g1 = 1f - abs(2f * phase - 1f)
        val g2 = 1f - abs(2f * p2 - 1f)
        val y = tap(phase) * g1 + tap(p2) * g2
        w = if (w + 1 >= size) 0 else w + 1
        return y
    }
}

/** Per-block processor. Allocates only at construction. Not an AI model: plain DSP. */
class Dsp(private val sr: Int) {
    private val hp = Biquad(); private val lp = Biquad()
    private val ps = PitchShifter(sr)
    private val echo = FloatArray(sr); private var ei = 0
    private var ringPh = 0f
    private var floor = 0.01f; private var g = 1f
    private var cfgFor = -1
    private val att = 1f - exp(-1f / (sr * 0.005f))
    private val rel = 1f - exp(-1f / (sr * 0.08f))
    @Volatile var speech = false
    @Volatile var inDb = -90f
    @Volatile var outDb = -90f
    @Volatile var noiseDb = -90f
    @Volatile var clips = 0

    private fun db(v: Float) = if (v < 1e-5f) -100f else 20f * log10(v)

    fun block(inp: ShortArray, out: ShortArray, n: Int, p: Preset, semiOff: Float, gate: Boolean, gain: Float) {
        if (cfgFor != p.id) {
            hp.highpass(sr, p.hp); if (p.lp > 0f) lp.lowpass(sr, p.lp); cfgFor = p.id
        }
        var sum = 0f; var pk = 0f
        for (i in 0 until n) {
            val a = abs(inp[i] / 32768f)
            sum += a * a; if (a > pk) pk = a; if (a >= 0.998f) clips++
        }
        val rms = sqrt(sum / n)
        floor = if (rms < floor) floor * 0.8f + rms * 0.2f else floor * 1.0008f
        floor = floor.coerceIn(1e-4f, 0.05f)
        speech = rms > max(floor * 3f, 0.006f)
        val target = if (!gate || speech) 1f else 0.12f
        inDb = db(pk); noiseDb = db(floor)

        val st = p.st + semiOff
        val shift = abs(st) > 0.01f
        val ratio = 2f.pow(st / 12f)
        val d = min(p.echoMs * sr / 1000, sr - 1)
        var opk = 0f
        for (i in 0 until n) {
            g += (target - g) * (if (target > g) att else rel)
            var x = inp[i] / 32768f
            x = hp.process(x)
            if (p.lp > 0f) x = lp.process(x)
            x *= g
            if (shift) x = ps.process(x, ratio)
            if (p.ring > 0f) {
                ringPh += p.ring / sr; if (ringPh >= 1f) ringPh -= 1f
                x *= sin(2f * PI.toFloat() * ringPh) * 1.4f
            }
            if (d > 0) {
                var ri = ei - d; if (ri < 0) ri += sr
                val e = echo[ri]
                echo[ei] = x + p.echoFb * e
                x += p.echoMix * e
                ei = if (ei + 1 >= sr) 0 else ei + 1
            }
            x *= gain
            val ax = abs(x)
            if (ax > 0.8f) x = sign(x) * (0.8f + 0.2f * tanh((ax - 0.8f) / 0.2f))
            if (abs(x) > opk) opk = abs(x)
            out[i] = (x * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
        outDb = db(opk)
    }
}
