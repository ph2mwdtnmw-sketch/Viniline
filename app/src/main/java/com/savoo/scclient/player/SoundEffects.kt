package com.savoo.scclient.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class SoundPreset(val rate: Float, val reverb: Float, val bass: Float) {
    OFF(1f, 0f, 0f),
    SLOWED_REVERB(0.85f, 0.55f, 0.25f),
    SLOWED(0.8f, 0f, 0.15f),
    SPED_UP(1.2f, 0f, 0f),
    NIGHTCORE(1.3f, 0f, 0.1f),
    CUSTOM(1f, 0f, 0f),
}

data class SoundMode(
    val preset: SoundPreset = SoundPreset.OFF,
    val customRate: Float = 1f,
    val customReverb: Float = 0f,
    val customBass: Float = 0f,
    // Gain in dB for each of the EqBands.COUNT bands. Independent of the preset.
    val eq: List<Float> = EqBands.FLAT,
    // Vinyl surface noise (hiss, ticks, pops, needle drop), 0..1. Independent of the preset.
    val noise: Float = DEFAULT_NOISE,
) {
    val rate: Float get() = if (preset == SoundPreset.CUSTOM) customRate else preset.rate
    val reverb: Float get() = if (preset == SoundPreset.CUSTOM) customReverb else preset.reverb
    val bass: Float get() = if (preset == SoundPreset.CUSTOM) customBass else preset.bass
    val isActive: Boolean get() = preset != SoundPreset.OFF

    companion object {
        const val MIN_RATE = 0.7f
        const val MAX_RATE = 1.4f
        const val DEFAULT_NOISE = 0.4f
    }
}

object EqBands {
    const val COUNT = 5
    const val MAX_DB = 12f
    const val Q = 1.0
    val FREQUENCIES_HZ = doubleArrayOf(60.0, 230.0, 910.0, 3600.0, 14000.0)
    val FLAT: List<Float> = List(COUNT) { 0f }
}

fun List<Float>.normalizedEq(): List<Float> =
    List(EqBands.COUNT) { getOrElse(it) { 0f }.coerceIn(-EqBands.MAX_DB, EqBands.MAX_DB) }

@Singleton
class SoundEffects @Inject constructor() {
    @Volatile var eq: FloatArray = FloatArray(EqBands.COUNT)
    @Volatile var noise: Float = 0f

    // "Hand on the record" state, written from the UI thread, read by the audio thread.
    @Volatile var scratchActive: Boolean = false
    @Volatile var scratchSpeed: Float = 0f
    @Volatile var scratchStampNanos: Long = 0L

    // Bumped on every new track; the audio thread plays the needle-drop sound when it changes.
    @Volatile var needleDropToken: Int = 0
    fun triggerNeedleDrop() {
        needleDropToken += 1
    }
    @Volatile var reverb: Float = 0f
    @Volatile var bass: Float = 0f
    @Volatile var outputRate: Float = 1f

    private val beatTimes = LongArray(BEAT_HISTORY)
    private val beatLevels = FloatArray(BEAT_HISTORY)
    @Volatile private var beatWrite = 0

    internal fun pushBeat(timeNanos: Long, level: Float) {
        val index = beatWrite % BEAT_HISTORY
        beatTimes[index] = timeNanos
        beatLevels[index] = level
        beatWrite = index + 1
    }

    fun beatLevelAt(timeNanos: Long): Float {
        val newest = beatWrite
        for (step in 1..BEAT_HISTORY) {
            val index = (newest - step + BEAT_HISTORY) % BEAT_HISTORY
            val time = beatTimes[index]
            if (time == 0L) return 0f
            if (time <= timeNanos) {
                return if (timeNanos - time > STALE_NANOS) 0f else beatLevels[index]
            }
        }
        return 0f
    }

    private companion object {
        const val BEAT_HISTORY = 512
        const val STALE_NANOS = 150_000_000L
    }
}

@UnstableApi
class SoundEffectsAudioProcessor(private val effects: SoundEffects) : BaseAudioProcessor() {

    private var sampleRate = 0
    private var channels = 0
    private var reverbs: Array<Freeverb> = emptyArray()
    private var shelves: Array<LowShelf> = emptyArray()
    private var appliedBass = -1f
    private var eqFilters: Array<Array<PeakingEq>> = emptyArray()
    private var appliedEq: FloatArray? = null
    private var engineSampleRate = 0
    private var scratch: ScratchEngine? = null
    private var noiseLeft: VinylNoise? = null
    private var noiseRight: VinylNoise? = null
    private var seenNeedleDrop = 0
    private var beatLowpass = 0f
    private var beatLowpassCoef = 0f
    private var beatSum = 0f
    private var beatCount = 0
    private var beatChunkFrames = 441
    private var beatPeak = 0f
    private var beatClockNanos = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        val processed = channels.coerceAtMost(2)
        reverbs = Array(processed) { channel -> Freeverb(sampleRate, stereoSpread = if (channel == 1) 23 else 0) }
        shelves = Array(processed) { LowShelf() }
        eqFilters = Array(processed) { Array(EqBands.COUNT) { PeakingEq() } }
        appliedEq = null
        appliedBass = -1f
        if (sampleRate > 0) {
            if (scratch == null || engineSampleRate != sampleRate) {
                scratch = ScratchEngine(sampleRate)
                noiseLeft = VinylNoise(sampleRate, initialSeed = 0x2545F491)
                noiseRight = VinylNoise(sampleRate, initialSeed = 0x6C8E9CF5)
                engineSampleRate = sampleRate
            } else {
                // The stream was flushed (seek, new track): the recorded history is no longer valid.
                scratch?.reset()
            }
        }
        beatLowpass = 0f
        beatLowpassCoef = if (sampleRate > 0) (1.0 - kotlin.math.exp(-2.0 * PI * BEAT_LOWPASS_HZ / sampleRate)).toFloat() else 0f
        beatChunkFrames = (sampleRate / 100).coerceAtLeast(1)
        beatSum = 0f
        beatCount = 0
        beatClockNanos = 0L
    }

    override fun onReset() {
        reverbs = emptyArray()
        shelves = emptyArray()
        eqFilters = emptyArray()
        appliedEq = null
        sampleRate = 0
        channels = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val output = replaceOutputBuffer(size)
        val input = inputBuffer.order(ByteOrder.nativeOrder())

        trackBeat(input.duplicate().order(ByteOrder.nativeOrder()), size)

        val scratchEngine = scratch
        val noiseL = noiseLeft
        val noiseR = noiseRight
        if (channels == 0 || channels > 2 || scratchEngine == null || noiseL == null || noiseR == null) {
            output.put(input)
            output.flip()
            return
        }

        val reverb = effects.reverb
        val bass = effects.bass
        val eq = effects.eq
        val eqActive = eq.any { it != 0f }
        val noiseLevel = effects.noise
        val scratchWanted = effects.scratchActive

        val dropToken = effects.needleDropToken
        if (dropToken != seenNeedleDrop) {
            seenNeedleDrop = dropToken
            noiseL.needleDrop()
            noiseR.needleDrop()
        }
        // The finger speed is only trusted while it is fresh: a finger that stopped moving sends
        // no more updates, and that has to read as "record held still".
        val fresh = System.nanoTime() - effects.scratchStampNanos < SCRATCH_STALE_NANOS
        val scratchSpeed = if (fresh) effects.scratchSpeed else 0f

        val effectsActive = reverb > 0f || bass > 0f || eqActive || noiseLevel > 0f ||
            scratchWanted || scratchEngine.isEngaged

        if (bass != appliedBass) {
            shelves.forEach { it.configure(sampleRate, BASS_FREQUENCY_HZ, bass * MAX_BASS_DB) }
            appliedBass = bass
        }
        val headroom = if (bass > 0f) 10f.pow(-(bass * MAX_BASS_DB) / 40f) else 1f
        if (eqActive) {
            if (eq !== appliedEq) {
                for (filters in eqFilters) {
                    for (band in 0 until EqBands.COUNT) {
                        val frequency = EqBands.FREQUENCIES_HZ[band]
                        // A band at or above ~Nyquist can't be built, leave it flat.
                        val gain = if (frequency >= sampleRate * 0.45) 0f else eq[band]
                        filters[band].configure(sampleRate, frequency, gain, EqBands.Q)
                    }
                }
                appliedEq = eq
            }
        } else {
            appliedEq = null
        }
        val eqHeadroom = if (eqActive) 10f.pow(-(eq.maxOrNull() ?: 0f).coerceAtLeast(0f) / 80f) else 1f
        val roomSize = 0.72f + 0.26f * reverb
        val wet = reverb * 0.9f
        val dry = 1f - reverb * 0.35f
        if (reverb > 0f) reverbs.forEach { it.setRoom(roomSize, damping = 0.45f) }

        val frames = size / (2 * channels)
        repeat(frames) {
            val rawLeft = input.getShort()
            val rawRight = if (channels == 2) input.getShort() else rawLeft

            // Always feed the scratch history, even when nothing else is on, so a scratch can
            // start at any moment and still have audio to play back.
            scratchEngine.process(rawLeft / 32768f, rawRight / 32768f, scratchWanted, scratchSpeed)
            if (!effectsActive) {
                output.putShort(rawLeft)
                if (channels == 2) output.putShort(rawRight)
                return@repeat
            }
            val left = scratchEngine.outLeft
            val right = scratchEngine.outRight

            var l = if (bass > 0f) shelves[0].process(left) * headroom else left
            var r = if (channels == 2) {
                if (bass > 0f) shelves[1].process(right) * headroom else right
            } else {
                l
            }

            if (eqActive) {
                l = applyEq(eqFilters[0], l) * eqHeadroom
                r = if (channels == 2) applyEq(eqFilters[1], r) * eqHeadroom else l
            }

            if (reverb > 0f) {
                val mono = (l + r) * FREEVERB_GAIN
                val wetL = reverbs[0].process(mono)
                if (channels == 2) {
                    val wetR = reverbs[1].process(mono)
                    l = l * dry + (wetL * 0.8f + wetR * 0.2f) * wet
                    r = r * dry + (wetR * 0.8f + wetL * 0.2f) * wet
                } else {
                    l = l * dry + wetL * wet
                }
            }

            // Record noise goes on last, so it is not coloured by the EQ or the reverb.
            val friction = scratchEngine.friction
            if (noiseLevel > 0f || friction > 0f) {
                l += noiseL.next(noiseLevel, friction)
                if (channels == 2) r += noiseR.next(noiseLevel, friction)
            }

            output.putShort(toPcm(l))
            if (channels == 2) output.putShort(toPcm(r))
        }
        output.flip()
    }

    private fun trackBeat(input: ByteBuffer, size: Int) {
        if (channels == 0 || sampleRate == 0) return
        val now = System.nanoTime()
        val rate = effects.outputRate.coerceAtLeast(0.1f)
        val chunkNanos = (beatChunkFrames * 1_000_000_000L / (sampleRate * rate)).toLong()
        if (beatClockNanos < now - chunkNanos * 4) beatClockNanos = now
        val frames = size / (2 * channels)
        repeat(frames) {
            var mono = 0f
            repeat(channels) { mono += input.getShort() / 32768f }
            mono /= channels
            beatLowpass += (mono - beatLowpass) * beatLowpassCoef
            beatSum += beatLowpass * beatLowpass
            if (++beatCount >= beatChunkFrames) {
                val rms = sqrt(beatSum / beatCount)
                beatPeak = maxOf(rms, beatPeak * BEAT_PEAK_DECAY)
                val level = if (beatPeak < BEAT_SILENCE) 0f else ((rms / beatPeak - 0.6f) / 0.4f).coerceIn(0f, 1f)
                beatClockNanos += chunkNanos
                effects.pushBeat(beatClockNanos, level)
                beatSum = 0f
                beatCount = 0
            }
        }
    }

    private fun applyEq(filters: Array<PeakingEq>, sample: Float): Float {
        var out = sample
        for (filter in filters) out = filter.process(out)
        return out
    }

    private fun toPcm(value: Float): Short {
        // Boosted EQ/bass can push loud tracks over full scale. Round the peaks off softly above
        // 0.9 instead of hard clipping, which sounds much harsher.
        val a = abs(value)
        val shaped = if (a > SOFT_KNEE) {
            val t = (a - SOFT_KNEE) / (1f - SOFT_KNEE)
            val soft = SOFT_KNEE + (1f - SOFT_KNEE) * (t / (1f + t))
            if (value < 0f) -soft else soft
        } else {
            value
        }
        return (shaped * 32767f).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    private companion object {
        const val BASS_FREQUENCY_HZ = 100.0
        const val MAX_BASS_DB = 9.0f
        const val FREEVERB_GAIN = 0.015f
        const val SCRATCH_STALE_NANOS = 80_000_000L
        const val SOFT_KNEE = 0.9f
        const val BEAT_LOWPASS_HZ = 150.0
        const val BEAT_PEAK_DECAY = 0.997f
        const val BEAT_SILENCE = 0.004f
    }
}

private class Freeverb(sampleRate: Int, stereoSpread: Int) {
    private val scale = sampleRate / 44100.0
    private val combs = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        .map { Comb(((it + stereoSpread) * scale).roundToInt().coerceAtLeast(1)) }
    private val allpasses = intArrayOf(556, 441, 341, 225)
        .map { Allpass(((it + stereoSpread) * scale).roundToInt().coerceAtLeast(1)) }

    fun setRoom(roomSize: Float, damping: Float) {
        combs.forEach {
            it.feedback = roomSize
            it.damp = damping
        }
    }

    fun process(input: Float): Float {
        var out = 0f
        for (comb in combs) out += comb.process(input)
        for (allpass in allpasses) out = allpass.process(out)
        return out * 3f
    }

    private class Comb(size: Int) {
        private val buffer = FloatArray(size)
        private var index = 0
        private var store = 0f
        var feedback = 0.8f
        var damp = 0.5f

        fun process(input: Float): Float {
            val output = buffer[index]
            store = output * (1f - damp) + store * damp
            buffer[index] = input + store * feedback
            if (++index >= buffer.size) index = 0
            return output
        }
    }

    private class Allpass(size: Int) {
        private val buffer = FloatArray(size)
        private var index = 0

        fun process(input: Float): Float {
            val buffered = buffer[index]
            buffer[index] = input + buffered * 0.5f
            if (++index >= buffer.size) index = 0
            return buffered - input
        }
    }
}

private class LowShelf {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun configure(sampleRate: Int, frequency: Double, gainDb: Float) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * frequency / sampleRate
        val cosW = cos(w0)
        val alpha = sin(w0) / 2 * sqrt(2.0)
        val sqrtA2Alpha = 2 * sqrt(a) * alpha
        val a0 = (a + 1) + (a - 1) * cosW + sqrtA2Alpha
        b0 = a * ((a + 1) - (a - 1) * cosW + sqrtA2Alpha) / a0
        b1 = 2 * a * ((a - 1) - (a + 1) * cosW) / a0
        b2 = a * ((a + 1) - (a - 1) * cosW - sqrtA2Alpha) / a0
        a1 = -2 * ((a - 1) + (a + 1) * cosW) / a0
        a2 = ((a + 1) + (a - 1) * cosW - sqrtA2Alpha) / a0
    }

    fun process(input: Float): Float {
        val x = input.toDouble()
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y.toFloat()
    }
}

private class PeakingEq {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun configure(sampleRate: Int, frequency: Double, gainDb: Float, q: Double) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * frequency / sampleRate
        val cosW = cos(w0)
        val alpha = sin(w0) / (2 * q)
        val a0 = 1 + alpha / a
        b0 = (1 + alpha * a) / a0
        b1 = (-2 * cosW) / a0
        b2 = (1 - alpha * a) / a0
        a1 = (-2 * cosW) / a0
        a2 = (1 - alpha / a) / a0
    }

    fun process(input: Float): Float {
        val x = input.toDouble()
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y.toFloat()
    }
}
