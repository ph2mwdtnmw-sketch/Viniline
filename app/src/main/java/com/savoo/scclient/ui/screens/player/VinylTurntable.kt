package com.savoo.scclient.ui.screens.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.savoo.scclient.ui.components.TrackArtwork
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.exp

/**
 * A vinyl record on a turntable that replaces the plain cover art in the full-screen player.
 *
 * - The record spins at 33 1/3 rpm multiplied by the current playback rate, so "slowed" and
 *   "sped up" modes visibly change the turntable speed. Starting and stopping has inertia.
 * - Touching the record stops it (like a hand on the platter). Dragging it in a circle winds the
 *   track forward (clockwise) or backward (counter-clockwise). Letting go resumes playback.
 * - The tonearm lowers onto the record while playing and travels from the outer groove to the
 *   inner groove as the track progresses.
 *
 * Performance notes for old phones: the record is drawn once and only its layer rotation changes
 * every frame (no recomposition, no redraw). The frame loop sleeps completely while the record is
 * stopped. There are no blurs, shadows or large bitmaps.
 */
@Composable
fun VinylTurntable(
    artworkUrl: String?,
    artworkSeed: Any?,
    isPlaying: Boolean,
    /** Effective playback rate: user speed multiplied by the sound mode rate. 1f = 33 1/3 rpm. */
    rate: Float,
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    /** Finger landed on a record that is playing: switch the audio to scratch mode. */
    onScratchStart: () -> Unit,
    /** Finger speed as a multiple of normal 33 1/3 rpm forward speed (negative = backwards). */
    onScratchSpeed: (Float) -> Unit,
    onScratchEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val angle = remember { mutableFloatStateOf(0f) }
    val targetSpeed = remember { mutableFloatStateOf(0f) }
    var held by remember { mutableStateOf(false) }
    var scrubPositionMs by remember { mutableLongStateOf(0L) }

    val latestIsPlaying by rememberUpdatedState(isPlaying)
    val latestPositionMs by rememberUpdatedState(positionMs)
    val latestDurationMs by rememberUpdatedState(durationMs)
    val latestSeek by rememberUpdatedState(onSeek)
    val latestScratchStart by rememberUpdatedState(onScratchStart)
    val latestScratchSpeed by rememberUpdatedState(onScratchSpeed)
    val latestScratchEnd by rememberUpdatedState(onScratchEnd)

    SideEffect {
        targetSpeed.floatValue = if (isPlaying && !held) BASE_DEGREES_PER_SECOND * rate.coerceAtLeast(0f) else 0f
    }

    // The spin loop. It sleeps (no frames requested at all) while the record is fully stopped.
    LaunchedEffect(Unit) {
        var lastNanos = withFrameNanosCompat()
        var velocity = 0f
        while (true) {
            val target = targetSpeed.floatValue
            if (target == 0f && velocity < IDLE_DEGREES_PER_SECOND) {
                velocity = 0f
                snapshotFlow { targetSpeed.floatValue }.first { it > 0f }
                lastNanos = withFrameNanosCompat()
                continue
            }
            val now = withFrameNanosCompat()
            val dt = ((now - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
            lastNanos = now
            val tau = when {
                target > velocity -> SPIN_UP_TAU
                held -> HAND_BRAKE_TAU
                else -> COAST_TAU
            }
            velocity += (target - velocity) * (1f - exp(-dt / tau))
            angle.floatValue = (angle.floatValue + velocity * dt) % 360f
        }
    }

    val progress = if (durationMs > 0L) {
        ((if (held) scrubPositionMs else positionMs).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val armDown = (isPlaying || held) && durationMs > 0L
    val armAngle = animateFloatAsState(
        targetValue = if (armDown) ARM_START_DEG + (ARM_END_DEG - ARM_START_DEG) * progress else ARM_REST_DEG,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
        label = "tonearm",
    )

    BoxWithConstraints(modifier = modifier) {
        val s = maxWidth
        val recordSize = s * (RECORD_RADIUS * 2f)
        val labelSize = recordSize * LABEL_FRACTION

        // 1) Static platter + soft shadow, behind everything.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val sc = size.minDimension
            val c = Offset(sc * CENTER_X, sc * CENTER_Y)
            drawCircle(Color(0x33000000), radius = sc * (PLATTER_RADIUS + 0.012f), center = c + Offset(0f, sc * 0.014f))
            drawCircle(Color(0xFF26262A), radius = sc * PLATTER_RADIUS, center = c)
            drawCircle(
                Color(0xFF3C3C42),
                radius = sc * PLATTER_RADIUS,
                center = c,
                style = Stroke(width = sc * 0.004f),
            )
        }

        // 2) The record itself. Gestures live on the NON-rotating wrapper, so finger coordinates
        //    stay in screen space while the layer inside rotates.
        Box(
            modifier = Modifier
                .offset(x = s * (CENTER_X - RECORD_RADIUS), y = s * (CENTER_Y - RECORD_RADIUS))
                .size(recordSize)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val dx0 = down.position.x - cx
                        val dy0 = down.position.y - cy
                        val radius = size.width / 2f
                        if (dx0 * dx0 + dy0 * dy0 > radius * radius) return@awaitEachGesture
                        down.consume()

                        // A playing record is "scratched": the audio engine plays the last seconds of
                        // the track at the finger's speed, and the real player is only moved on release.
                        // A stopped record just seeks silently while it is dragged.
                        val scratching = latestIsPlaying
                        val startDuration = latestDurationMs
                        held = true
                        scrubPositionMs = latestPositionMs
                        if (scratching) latestScratchStart()
                        var lastAngle = atan2(dy0, dx0)
                        var lastTime = down.uptimeMillis
                        var fingerSpeed = 0f
                        var lastSeekAt = 0L
                        var moved = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                val a = atan2(change.position.y - cy, change.position.x - cx)
                                var d = (a - lastAngle) * RAD_TO_DEG
                                if (d > 180f) d -= 360f else if (d < -180f) d += 360f
                                lastAngle = a
                                val now = change.uptimeMillis
                                val dt = now - lastTime
                                lastTime = now
                                if (d != 0f) {
                                    moved = true
                                    angle.floatValue = (angle.floatValue + d) % 360f
                                    val maxMs = latestDurationMs.coerceAtLeast(0L)
                                    scrubPositionMs = (scrubPositionMs + (d / 360f * MS_PER_REVOLUTION).toLong())
                                        .coerceIn(0L, maxMs)
                                    if (scratching) {
                                        if (dt > 0L) {
                                            val instant = (d / dt * 1000f) / BASE_DEGREES_PER_SECOND
                                            fingerSpeed += (instant - fingerSpeed) * SPEED_SMOOTHING
                                            latestScratchSpeed(fingerSpeed)
                                        }
                                    } else if (now - lastSeekAt >= SEEK_THROTTLE_MS) {
                                        lastSeekAt = now
                                        latestSeek(scrubPositionMs)
                                    }
                                }
                                change.consume()
                            }
                        } finally {
                            if (scratching) latestScratchEnd()
                            // A quick tap that never turned the record must not rewind or interrupt
                            // the music. Holding it for a moment does count (the record was stopped).
                            // If the track changed under the finger (it ended while held), do not
                            // drag the new track to a position that belonged to the old one.
                            val heldMs = lastTime - down.uptimeMillis
                            val sameTrack = latestDurationMs == startDuration
                            if (sameTrack && (moved || heldMs >= TAP_MAX_MS)) latestSeek(scrubPositionMs)
                            held = false
                        }
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { rotationZ = angle.floatValue }
                    .drawBehind { drawRecordGrooves() },
                contentAlignment = Alignment.Center,
            ) {
                TrackArtwork(
                    artworkUrl = artworkUrl,
                    contentDescription = null,
                    seed = artworkSeed,
                    shape = CircleShape,
                    modifier = Modifier.size(labelSize),
                )
                Box(
                    modifier = Modifier
                        .size(recordSize * 0.035f)
                        .background(Color(0xFF1A1A1D), CircleShape),
                )
            }
            // Fixed light reflection on top of the spinning vinyl: this is what makes the
            // rotation readable, since the grooves themselves are uniform circles.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val brush = Brush.sweepGradient(
                            0.00f to Color.Transparent,
                            0.06f to Color(0x2EFFFFFF),
                            0.14f to Color.Transparent,
                            0.50f to Color.Transparent,
                            0.56f to Color(0x2EFFFFFF),
                            0.64f to Color.Transparent,
                            1.00f to Color.Transparent,
                            center = center,
                        )
                        drawCircle(brush = brush, radius = size.minDimension / 2f, center = center)
                    },
            )
        }

        // 3) Tonearm, on top. Reads the animated angle only in the draw phase.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val sc = size.minDimension
            val pivot = Offset(sc * PIVOT_X, sc * PIVOT_Y)
            val armLength = sc * ARM_LENGTH
            val headLength = sc * 0.06f

            drawCircle(Color(0xFF1B1B1F), radius = sc * 0.07f, center = pivot)
            drawCircle(Color(0xFF4A4A52), radius = sc * 0.07f, center = pivot, style = Stroke(width = sc * 0.006f))
            rotate(degrees = armAngle.value, pivot = pivot) {
                // counterweight behind the pivot
                drawLine(
                    color = Color(0xFF6E6E78),
                    start = pivot,
                    end = pivot + Offset(0f, -sc * 0.07f),
                    strokeWidth = sc * 0.045f,
                    cap = StrokeCap.Round,
                )
                // arm tube
                drawLine(
                    color = Color(0xFFCFCFD6),
                    start = pivot,
                    end = pivot + Offset(0f, armLength - headLength),
                    strokeWidth = sc * 0.014f,
                    cap = StrokeCap.Round,
                )
                // headshell with the needle at its tip
                drawLine(
                    color = Color(0xFF9B9BA5),
                    start = pivot + Offset(0f, armLength - headLength),
                    end = pivot + Offset(0f, armLength),
                    strokeWidth = sc * 0.032f,
                    cap = StrokeCap.Round,
                )
            }
            drawCircle(Color(0xFFE6E6EB), radius = sc * 0.02f, center = pivot)
        }
    }
}

private fun DrawScope.drawRecordGrooves() {
    val c = center
    val r = size.minDimension / 2f
    drawCircle(Color(0xFF0B0B0D), radius = r, center = c)
    var radius = r * 0.97f
    var i = 0
    while (radius > r * 0.40f) {
        val color = when {
            i % 9 == 8 -> Color(0xFF2A2A2F) // a "gap" between tracks
            i % 3 == 0 -> Color(0xFF1D1D21)
            else -> Color(0xFF121215)
        }
        drawCircle(color = color, radius = radius, center = c, style = Stroke(width = 1.2f))
        radius -= r * 0.018f
        i++
    }
    drawCircle(Color(0x26FFFFFF), radius = r - 1f, center = c, style = Stroke(width = 2f))
}

private suspend fun withFrameNanosCompat(): Long =
    androidx.compose.runtime.withFrameNanos { it }

// Geometry, as fractions of the square the turntable is drawn in.
private const val CENTER_X = 0.45f
private const val CENTER_Y = 0.52f
private const val RECORD_RADIUS = 0.40f
private const val PLATTER_RADIUS = 0.415f
private const val LABEL_FRACTION = 0.38f
private const val PIVOT_X = 0.90f
private const val PIVOT_Y = 0.10f
private const val ARM_LENGTH = 0.55f

// Tonearm angle in degrees, clockwise from "pointing straight down". These were solved so the
// needle sits on the outer groove at START and on the inner groove at END; REST parks it off the record.
private const val ARM_START_DEG = 10f
private const val ARM_END_DEG = 29f
private const val ARM_REST_DEG = -6f

// 33 1/3 rpm = 200 degrees per second = one revolution every 1.8 s.
private const val BASE_DEGREES_PER_SECOND = 200f
private const val MS_PER_REVOLUTION = 1800f
private const val IDLE_DEGREES_PER_SECOND = 1f

// Inertia, in seconds.
private const val SPIN_UP_TAU = 0.35f
private const val COAST_TAU = 0.5f
private const val HAND_BRAKE_TAU = 0.07f

private const val SEEK_THROTTLE_MS = 180L
private const val SPEED_SMOOTHING = 0.5f
private const val TAP_MAX_MS = 350L
private val RAD_TO_DEG = (180.0 / PI).toFloat()
