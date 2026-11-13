package com.voluene.monobrowser.ui.browser

import kotlin.math.abs
import kotlin.math.sign

/**
 * Maths of the address-bar swipe between tabs (Safari style: the bar follows the finger and the neighbouring tab's
 * bar slides in beside it). Offsets are in pixels: negative = the finger moved left (towards the next tab), positive
 * = right (towards the previous one). Pure, unit-tested.
 */
object BarSwipe {
    enum class Axis { HORIZONTAL, VERTICAL }

    /** What releasing the finger does. */
    enum class Outcome { PREVIOUS, NEXT, NEW_TAB, NONE }

    /** Share of the bar width the finger has to travel to switch. */
    const val COMMIT_FRACTION = 1f / 3f

    /** Axis of a gesture once it left the touch slop: the dominant one (ties are horizontal). */
    fun axis(dx: Float, dy: Float): Axis = if (abs(dx) >= abs(dy)) Axis.HORIZONTAL else Axis.VERTICAL

    /** The way the bar can be pulled at most: its own width plus the gap between the two bars. */
    fun travel(width: Float, gap: Float): Float = width + gap

    /**
     * Rubber band: [raw] pixels of finger travel become fewer pixels of movement that never exceed [limit]
     * (iOS formula, derivative 0.55 at the start). Keeps the sign.
     */
    fun rubberBand(raw: Float, limit: Float): Float {
        if (raw == 0f || limit <= 0f) return 0f
        val distance = abs(raw)
        return sign(raw) * (1f - 1f / (distance * 0.55f / limit + 1f)) * limit
    }

    /**
     * Where the bar is drawn for a finger travel of [raw] pixels. Pulling right with no previous tab is damped by
     * [rubberBand]; every other direction follows the finger up to [travel].
     */
    fun displayOffset(raw: Float, width: Float, gap: Float, hasPrevious: Boolean): Float {
        if (width <= 0f) return 0f
        val limit = travel(width, gap)
        return when {
            raw > 0f -> if (hasPrevious) raw.coerceAtMost(limit) else rubberBand(raw, width * RUBBER_LIMIT_FRACTION)
            else -> raw.coerceAtLeast(-limit)
        }
    }

    private const val RUBBER_LIMIT_FRACTION = 0.25f

    /**
     * Decision on release. [raw] is the finger travel, [velocity] px/s (same sign convention).
     * A switch needs [COMMIT_FRACTION] of [width], or a quick flick ([flingVelocity] px/s) that moved at least
     * [minFlingDistance]. Past the last tab a new tab opens only by distance (a flick must not create tabs);
     * before the first tab nothing happens.
     */
    fun decide(
        raw: Float,
        velocity: Float,
        width: Float,
        hasPrevious: Boolean,
        hasNext: Boolean,
        flingVelocity: Float,
        minFlingDistance: Float,
    ): Outcome {
        if (width <= 0f || raw == 0f) return Outcome.NONE
        val distance = abs(raw)
        val far = distance >= width * COMMIT_FRACTION
        val flick = abs(velocity) >= flingVelocity && distance >= minFlingDistance && sign(velocity) == sign(raw)
        return if (raw < 0f) {
            when {
                hasNext -> if (far || flick) Outcome.NEXT else Outcome.NONE
                far -> Outcome.NEW_TAB
                else -> Outcome.NONE
            }
        } else {
            if (hasPrevious && (far || flick)) Outcome.PREVIOUS else Outcome.NONE
        }
    }

    /** Where the bar rests after the release animation: a full step to the side, or back to zero. */
    fun settleTarget(outcome: Outcome, width: Float, gap: Float): Float = when (outcome) {
        Outcome.PREVIOUS -> travel(width, gap)
        Outcome.NEXT, Outcome.NEW_TAB -> -travel(width, gap)
        Outcome.NONE -> 0f
    }

    /**
     * X position (px, relative to the bar) of the incoming bar for the current [offset]: it waits one [travel] away
     * on the side the finger moves from (right for a leftward drag, left for a rightward one).
     */
    fun neighbourX(offset: Float, width: Float, gap: Float): Float = when {
        offset < 0f -> travel(width, gap) + offset
        offset > 0f -> offset - travel(width, gap)
        else -> 0f
    }

    /** What slides in beside the bar. */
    enum class Incoming { CARD, NEW_TAB, NONE }

    /**
     * [side] -1 = the bar is dragged left (from the right comes the next tab, or "new tab" past the last one),
     * 1 = dragged right (the previous tab; before the first tab only the rubber band, nothing comes in), 0 = at rest.
     */
    fun incoming(side: Int, hasPrevious: Boolean, hasNext: Boolean): Incoming = when {
        side < 0 -> if (hasNext) Incoming.CARD else Incoming.NEW_TAB
        side > 0 -> if (hasPrevious) Incoming.CARD else Incoming.NONE
        else -> Incoming.NONE
    }

    /** True while the neighbour is (at least partly) in view. */
    fun neighbourVisible(offset: Float): Boolean = abs(offset) >= 0.5f

    /**
     * X position (px) of the neighbouring PAGE: the pages move exactly like the bars (the current page by [offset], the
     * neighbour one [travel] beside it), so the page and its bar never drift apart. Same maths as [neighbourX].
     */
    fun neighbourPageX(offset: Float, width: Float, gap: Float): Float = neighbourX(offset, width, gap)

    /** How far (px) the gap between the current page and the neighbour is at [offset]: constant while both are in view. */
    fun pageGap(offset: Float, width: Float, gap: Float): Float =
        if (offset == 0f) 0f else abs(neighbourPageX(offset, width, gap) - offset) - width
}

/**
 * How long the neighbour's preview keeps covering the page after the switch, so that a tab that has not drawn yet
 * (a WebView being re-attached, a sleeping tab being rebuilt) never shows a white or black flash. Pure, unit-tested.
 */
object PageCover {
    /** A live tab only needs its WebView re-attached: a couple of frames. */
    const val LIVE_HOLD_MS = 120L

    /** A sleeping tab loads its page: until it draws, but never longer than this. */
    const val SLEEPING_MAX_MS = 700L

    /** Cross-fade from the preview to the real page. */
    const val FADE_MS = 160

    /** Longest time the preview is held ([firstFrame] = the page reported its first visible frame). */
    fun holdLimitMs(sleeping: Boolean): Long = if (sleeping) SLEEPING_MAX_MS else LIVE_HOLD_MS

    /** True once the preview may start fading: [elapsedMs] since the switch, [sleeping] tab, page drew [firstFrame]. */
    fun mayFade(elapsedMs: Long, sleeping: Boolean, firstFrame: Boolean): Boolean =
        if (sleeping) firstFrame || elapsedMs >= SLEEPING_MAX_MS else elapsedMs >= LIVE_HOLD_MS
}

/**
 * Maths of the vertical pull on the address bar that brings up the tab list (the list follows the finger).
 * [dy] is the travel towards the page in px (positive = the pulling direction). Pure, unit-tested.
 */
object TabsPullMath {
    /** Share of the pull distance that opens the list on release. */
    const val OPEN_FRACTION = 0.2f

    /** Pull distance (px) that shows the list completely: the whole screen, so the list follows the finger 1:1. */
    fun distance(screenHeight: Float): Float = screenHeight

    fun progress(dy: Float, distance: Float): Float = if (distance <= 0f) 0f else (dy / distance).coerceIn(0f, 1f)

    /** Opens past [OPEN_FRACTION] of the [distance], or on a quick flick ([flingVelocity] px/s) that moved at least [minFlingDistance]. */
    fun shouldOpen(dy: Float, velocity: Float, distance: Float, flingVelocity: Float, minFlingDistance: Float): Boolean =
        progress(dy, distance) >= OPEN_FRACTION || (velocity >= flingVelocity && dy >= minFlingDistance)
}

/** What the address bar tells the screen while the tab list is pulled in: progress 0..1 while dragging, then the decision. */
class TabsPull(
    val onDrag: (progress: Float) -> Unit,
    val onRelease: (open: Boolean, velocity: Float) -> Unit,
    /** Read at gesture time: true while a pull runs or its release animation is still over the page. */
    val busy: () -> Boolean = { false },
)
