package com.rx8.dash.ui.knob

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import com.rx8.dash.data.KnobAction

/**
 * Accumulates rotary scroll so one detent produces one step regardless of the encoder's pixel scale.
 * Kept outside composition state: it only buffers sub-threshold motion.
 */
class KnobAccumulator(private val pixelsPerStep: Float = DEFAULT_PIXELS_PER_STEP) {
    private var pending: Float = 0f
    private val consumedDown = HashSet<Int>()

    /** Remembers that a KeyDown for [keyCode] was consumed, so its KeyUp is consumed too. */
    fun markConsumed(keyCode: Int) {
        consumedDown.add(keyCode)
    }

    /** True (and forgets the key) when this key's KeyDown was consumed. */
    fun releaseConsumed(keyCode: Int): Boolean = consumedDown.remove(keyCode)

    /** Returns +n (next), -n (previous) or 0 steps for this scroll delta. */
    fun feed(pixels: Float): Int {
        if (pixels.isNaN()) return 0
        // Reverse direction discards the leftover so a direction change reacts immediately.
        if ((pending > 0f && pixels < 0f) || (pending < 0f && pixels > 0f)) pending = 0f
        pending += pixels
        var steps = 0
        while (pending >= pixelsPerStep) {
            pending -= pixelsPerStep
            steps++
        }
        while (pending <= -pixelsPerStep) {
            pending += pixelsPerStep
            steps--
        }
        return steps
    }

    companion object {
        const val DEFAULT_PIXELS_PER_STEP: Float = 24f
    }
}

@Composable
fun rememberKnobAccumulator(): KnobAccumulator = remember { KnobAccumulator() }

/**
 * Knob input: rotary encoders (onRotaryScrollEvent) and key-emitting knobs/DPADs (onPreviewKeyEvent), mapped through
 * the user's keycode -> [KnobAction] table. [onAction] returns true when it consumed the action; returning false lets
 * the key continue to the focused child (e.g. so a focused button handles DPAD_CENTER itself).
 *
 * Only KeyDown is dispatched; the matching KeyUp of a consumed KeyDown is consumed too.
 */
fun Modifier.knobInput(
    keyMap: Map<Int, KnobAction>,
    accumulator: KnobAccumulator,
    onAction: (KnobAction, keyCode: Int?) -> Boolean,
): Modifier = this
    .onRotaryScrollEvent { event ->
        val steps = accumulator.feed(event.verticalScrollPixels)
        if (steps > 0) {
            repeat(steps) { onAction(KnobAction.NEXT, null) }
        } else if (steps < 0) {
            repeat(-steps) { onAction(KnobAction.PREVIOUS, null) }
        }
        true
    }
    .onPreviewKeyEvent { event ->
        val keyCode = event.nativeKeyEvent.keyCode
        val action = keyMap[keyCode] ?: KnobAction.NONE
        if (action == KnobAction.NONE) {
            false
        } else if (event.type == KeyEventType.KeyDown) {
            val consumed = onAction(action, keyCode)
            if (consumed) accumulator.markConsumed(keyCode)
            consumed
        } else if (event.type == KeyEventType.KeyUp) {
            // Swallow the KeyUp of a consumed KeyDown so e.g. BACK does not also reach the system.
            accumulator.releaseConsumed(keyCode)
        } else {
            false
        }
    }
