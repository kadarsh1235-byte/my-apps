package com.adarsh.voxnoxfx

/** st = semitone shift, ring = ring-mod Hz (0=off), echo*, hp/lp = filter corner Hz (lp 0 = off). */
data class Preset(
    val id: Int, val name: String, val st: Float = 0f, val ring: Float = 0f,
    val echoMs: Int = 0, val echoMix: Float = 0f, val echoFb: Float = 0f,
    val hp: Float = 90f, val lp: Float = 0f
)

object Presets {
    val all = listOf(
        Preset(0, "Original (bypass)"),
        Preset(1, "Deep Male", st = -3.5f),
        Preset(2, "Female", st = 4f),
        Preset(3, "Chipmunk", st = 8f),
        Preset(4, "Demon", st = -7f, echoMs = 90, echoMix = 0.25f, echoFb = 0.3f),
        Preset(5, "Robot", ring = 50f),
        Preset(6, "Echo", echoMs = 220, echoMix = 0.4f, echoFb = 0.35f),
        Preset(7, "Telephone", hp = 300f, lp = 3400f)
    )
}
