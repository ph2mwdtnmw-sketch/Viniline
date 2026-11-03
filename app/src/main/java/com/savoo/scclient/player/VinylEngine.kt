package com.savoo.scclient.player

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/*
 * Pure Kotlin DSP for the vinyl feel. No Android or Media3 imports on purpose, so the logic can be
 * unit-tested and benchmarked on a plain JVM. Nothing here allocates per sample.
 */

/**
 * Synthesised record surface noise: a soft hiss, small ticks, the odd low pop, and a "needle drop"
 * thump followed by a few seconds of denser crackle.
 */
class VinylNoise(private val sampleRate: Int, initialSeed: Int = 0x2545F491) {
    private var seed = if (initialSeed == 0) 0x2545F491 else initialSeed
    private var hissLowpass = 0f
    private var tickEnvelope = 0f
    private var popEnvelope = 0f
    private var popLowpass = 0f
    private var dropSamplesLeft = 0
    private var dropSamplesTotal = 1

    private val tickDecay = exp(-1f / (sampleRate * 0.0007f))
    private val popDecay = exp(-1f / (sampleRate * 0.006f))
    private val ticksPerSample = TICKS_PER_SECOND / sampleRate
    private val popsPerSample = POPS_PER_SECOND / sampleRate

    fun needleDrop() {
        dropSamplesTotal = (sampleRate * DROP_SECONDS).toInt().coerceAtLeast(1)
        dropSamplesLeft = dropSamplesTotal
        popEnvelope = DROP_THUMP
    }

    /** Next mono noise sample. [level] is 0..1. [extraHiss] adds hiss (used for scratch friction). */
    fun next(level: Float, extraHiss: Float = 0f): Float {
        if (level <= 0f) {
            // Noise is switched off: stay completely silent (and forget any pending needle drop).
            tickEnvelope = 0f
            popEnvelope = 0f
            dropSamplesLeft = 0
            if (extraHiss <= 0f) return 0f
            val white = random() * 2f - 1f
            hissLowpass += (white - hissLowpass) * HISS_LOWPASS
            return (white - hissLowpass) * SCRATCH_HISS_AMPLITUDE * extraHiss
        }
        val boost = if (dropSamplesLeft > 0) {
            dropSamplesLeft--
            dropSamplesLeft.toFloat() / dropSamplesTotal
        } else {
            0f
        }
        val white = random() * 2f - 1f
        hissLowpass += (white - hissLowpass) * HISS_LOWPASS
        val highPassed = white - hissLowpass

        var out = 0f
        run {
            val boosted = level * (1f + 2f * boost)
            out += highPassed * HISS_AMPLITUDE * (level + 0.5f * boost)

            // One dice roll per sample decides between "tick", "pop" or nothing.
            val roll = random()
            val tickChance = ticksPerSample * boosted
            if (roll < tickChance) {
                val r = random()
                tickEnvelope = (0.02f + 0.20f * r * r * r) * (0.4f + 0.6f * level)
            } else if (roll < tickChance + popsPerSample * boosted) {
                popEnvelope = max(popEnvelope, (0.05f + 0.12f * random()) * (0.5f + 0.5f * level))
            }
        }
        // The envelopes are zero almost all the time, so skip the work then.
        if (tickEnvelope > SILENT) {
            out += tickEnvelope * (random() * 2f - 1f)
            tickEnvelope *= tickDecay
        } else {
            tickEnvelope = 0f
        }
        if (popEnvelope > SILENT) {
            popLowpass += (white - popLowpass) * POP_LOWPASS
            out += popEnvelope * popLowpass * POP_GAIN
            popEnvelope *= popDecay
        } else {
            popEnvelope = 0f
            popLowpass = 0f
        }

        if (extraHiss > 0f) out += highPassed * SCRATCH_HISS_AMPLITUDE * extraHiss
        return out
    }

    private fun random(): Float {
        // xorshift32 -> [0, 1)
        var x = seed
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        seed = x
        return (x ushr 8) / 16777216f
    }

    private companion object {
        const val HISS_AMPLITUDE = 0.02f
        const val HISS_LOWPASS = 0.12f
        const val SCRATCH_HISS_AMPLITUDE = 0.05f
        const val TICKS_PER_SECOND = 14f
        const val POPS_PER_SECOND = 0.5f
        const val POP_LOWPASS = 0.04f
        const val POP_GAIN = 8f
        const val DROP_SECONDS = 2.2f
        const val DROP_THUMP = 0.2f
        const val SILENT = 0.0003f
    }
}

/**
 * "Hand on the record". It keeps the last few seconds of audio that went through the processor.
 * While the finger is on the record, the normal stream is replaced by that history played at the
 * speed and direction of the finger: backwards, forwards, slowing to a stop, the classic scratch.
 *
 * Call [process] once per input frame, in order. While not scratching it is a pass-through that
 * just records the history.
 */
class ScratchEngine(private val sampleRate: Int) {
    private val capacity = sampleRate * HISTORY_SECONDS
    private val history = FloatArray(capacity * 2)
    private var written = 0L
    private var blend = 0f
    private var speed = 0f
    private var readPos = 0.0
    private var wasActive = false
    private var atEdge = false

    private val blendStep = 1f / (sampleRate * 0.012f)
    private val speedCoef = 1f - exp(-1f / (sampleRate * 0.03f))

    var outLeft = 0f
        private set
    var outRight = 0f
        private set

    /** 0..1: how much scratch friction noise to add right now. */
    val friction: Float get() = min(1f, abs(speed)) * blend

    /** True while the output is (even partly) the scratch signal. */
    val isEngaged: Boolean get() = blend > 0f

    /** Forget the history. Call when the stream was flushed (seek, new track). */
    fun reset() {
        written = 0L
        blend = 0f
        speed = 0f
        wasActive = false
        atEdge = false
    }

    fun process(left: Float, right: Float, active: Boolean, targetSpeed: Float) {
        val slot = (written % capacity).toInt() * 2
        history[slot] = left
        history[slot + 1] = right
        written++

        if (active && !wasActive) {
            readPos = (written - 1).toDouble()
            speed = 1f
        }
        wasActive = active

        if (blend == 0f && !active) {
            outLeft = left
            outRight = right
            return
        }

        blend = if (active) min(1f, blend + blendStep) else max(0f, blend - blendStep)
        speed += (targetSpeed - speed) * speedCoef

        val newest = (written - 1).toDouble()
        val oldest = max(0L, written - capacity + 2).toDouble()
        readPos += speed
        atEdge = false
        if (readPos >= newest) {
            readPos = newest
            atEdge = speed > 0f
        } else if (readPos <= oldest) {
            readPos = oldest
            atEdge = speed < 0f
        }

        val i0 = floor(readPos).toLong()
        val frac = (readPos - i0).toFloat()
        val i1 = if (i0 + 1 < written) i0 + 1 else i0
        val a = (i0 % capacity).toInt() * 2
        val b = (i1 % capacity).toInt() * 2
        val scratchLeft = history[a] + (history[b] - history[a]) * frac
        val scratchRight = history[a + 1] + (history[b + 1] - history[a + 1]) * frac

        // Quieter when nearly stopped (a constant value would be a DC click), silent when stuck
        // against the end of the history.
        val gain = if (atEdge && speed < 0f) 0f else min(1f, abs(speed) / FULL_GAIN_SPEED)
        val dry = 1f - blend
        outLeft = left * dry + scratchLeft * gain * blend
        outRight = right * dry + scratchRight * gain * blend
    }

    private companion object {
        const val HISTORY_SECONDS = 3
        const val FULL_GAIN_SPEED = 0.12f
    }
}
