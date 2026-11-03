package com.savoo.scclient.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class VinylEngineTest {
    private val sr = 44100

    private fun rms(a: FloatArray, from: Int, to: Int): Double =
        sqrt((from until to).sumOf { (a[it] * a[it]).toDouble() } / (to - from))

    private fun risingZeroCrossingsPerSecond(a: FloatArray, from: Int, to: Int): Double {
        var z = 0
        for (i in from + 1 until to) if (a[i - 1] <= 0f && a[i] > 0f) z++
        return z / ((to - from) / sr.toDouble())
    }

    private fun scratchRun(plan: List<Pair<Float, Double>>): FloatArray {
        val e = ScratchEngine(sr)
        var phase = 0.0
        val out = ArrayList<Float>()
        fun feed(active: Boolean, speed: Float, frames: Int) = repeat(frames) {
            phase += 2 * PI * 440.0 / sr
            val x = (0.5 * sin(phase)).toFloat()
            e.process(x, x, active, speed)
            out.add(e.outLeft)
        }
        feed(false, 0f, sr * 2)
        for ((speed, seconds) in plan) feed(true, speed, (sr * seconds).toInt())
        return out.toFloatArray()
    }

    @Test
    fun passThroughWhenNotScratching() {
        val e = ScratchEngine(sr)
        repeat(sr) { i ->
            val x = (0.4 * sin(i * 0.05)).toFloat()
            e.process(x, -x, false, 0f)
            assertEquals(x, e.outLeft, 0f)
            assertEquals(-x, e.outRight, 0f)
        }
    }

    @Test
    fun heldRecordIsSilent() {
        val o = scratchRun(listOf(0f to 0.6))
        val start = sr * 2 + (sr * 0.3).toInt()
        assertTrue(rms(o, start, o.size) < 0.001)
    }

    @Test
    fun reverseAtDoubleSpeedIsAnOctaveUp() {
        val o = scratchRun(listOf(-2f to 0.6))
        val start = sr * 2 + (sr * 0.2).toInt()
        val f = risingZeroCrossingsPerSecond(o, start, o.size)
        assertTrue("freq was $f", abs(f - 880.0) < 40.0)
    }

    @Test
    fun scratchHasNoClicks() {
        val o = scratchRun(listOf(0f to 0.5, -2f to 0.5, 1f to 0.5, 0f to 0.3))
        var maxJump = 0f
        for (i in 1 until o.size) maxJump = maxOf(maxJump, abs(o[i] - o[i - 1]))
        // A 0.5 amplitude sine at 880 Hz has a maximum step of about 0.063.
        assertTrue("max jump was $maxJump", maxJump < 0.08f)
    }

    @Test
    fun noiseIsSilentWhenOffEvenAfterNeedleDrop() {
        val n = VinylNoise(sr)
        n.needleDrop()
        repeat(sr) { assertEquals(0f, n.next(0f), 0f) }
    }

    @Test
    fun noiseStaysSubtleAndFinite() {
        val n = VinylNoise(sr)
        val buf = FloatArray(sr * 10) { n.next(0.4f) }
        assertTrue(buf.none { it.isNaN() || it.isInfinite() })
        assertTrue(buf.maxOf { abs(it) } < 0.3f)
        assertTrue(rms(buf, 0, buf.size) in 0.001..0.02)
    }
}
