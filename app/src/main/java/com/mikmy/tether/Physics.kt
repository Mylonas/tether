package com.mikmy.tether

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The whole simulation, with no Android types anywhere, so it can be tuned and
 * unit tested on the JVM. [Game] only draws what this produces and turns the
 * event flags into noise and particles.
 *
 * Every distance is in units of [World.unit] (the short screen edge), so the
 * game plays identically on any phone.
 *
 * These constants were chosen by running a bot through this exact model
 * thousands of times — see the notes in README.md.
 */
object Tune {
    const val GRAVITY = 3.5f        // unit/s^2
    const val PULL = 7.0f           // unit/s^2, the rope hauling you in
    const val MAX_ROPE = 0.34f
    const val MIN_ROPE = 0.07f
    const val SNAP = 0.05f          // rope pops when you reach the anchor
    const val GRAB_R = 0.45f
    const val RELEASE_KICK = 1.04f
    const val MAX_SPEED = 3.2f      // unit/s
    const val DAMP = 0.999f

    const val STEP_EASY = 0.55f     // anchor spacing as a fraction of GRAB_R
    const val STEP_HARD = 0.92f
    const val RAMP_UNITS = 45f      // screens climbed before difficulty tops out

    const val VOID_BASE = 0.09f     // unit/s
    const val VOID_ACCEL = 0.0022f  // unit/s^2

    const val CAM_ANCHOR = 0.62f    // where the player sits on screen while climbing
    const val CAM_UP = 9f
    const val CAM_DOWN = 1.6f

    /**
     * How long after a press the grapple keeps reaching for an anchor.
     *
     * Retrying every frame for as long as the finger was down turned holding
     * into a magnet that latched every anchor automatically, so a run could be
     * carried without ever using the release: measured at 67s for
     * press-once-never-let-go versus 13s for actually timing the releases.
     *
     * Bounding the reach to a window after each press is what was measured to
     * fix it — hold-forever drops to about 2s while a bot that plays properly
     * is unchanged. The window is generous on purpose: pressing early still
     * catches, and a press that snaps quickly can still take the next anchor.
     */
    const val GRAB_WINDOW = 0.9f

    // 0.09 puts a bot's collection rate around 70%: chains are achievable, but
    // breaking one is a real event. Measured, not guessed.
    const val SPARK_R = 0.09f
    const val METRES_PER_UNIT = 10f
    const val MILESTONE_M = 25
}

const val ANCHOR_NORMAL = 0
const val ANCHOR_DRIFT = 1
const val ANCHOR_ONCE = 2
const val ANCHOR_GOLD = 3

class Anchor(
    @JvmField val x: Float,
    @JvmField val y: Float,
    @JvmField val drift: Float,
    @JvmField val phase: Float,
    @JvmField val kind: Int
) {
    /** Current x after drifting. */
    @JvmField var ax: Float = x

    /** A one-shot anchor that has been used up. */
    @JvmField var spent: Boolean = false
    @JvmField var fade: Float = 1f
    @JvmField var pulse: Float = 0f
}

class Spark(@JvmField val x: Float, @JvmField val y: Float) {
    @JvmField var taken = false
    @JvmField var missed = false
    @JvmField var pop = 0f
}

class World(@JvmField val vw: Float, @JvmField val vh: Float, seed: Long = 1L) {

    @JvmField val unit: Float = min(vw, vh)

    private var rngState: Long = if (seed == 0L) 1L else seed
    private fun rnd(): Float {
        // xorshift64* — deterministic, so a seed reproduces a run exactly.
        var s = rngState
        s = s xor (s shl 13)
        s = s xor (s ushr 7)
        s = s xor (s shl 17)
        rngState = s
        return ((s ushr 11).toDouble() / (1L shl 53).toDouble()).toFloat()
    }

    // ---- player ----
    @JvmField var px = vw / 2f
    @JvmField var py = 0f
    @JvmField var vx = 0f
    @JvmField var vy = -1.1f * unit
    @JvmField var anchor: Anchor? = null
    @JvmField var ropeLen = 0f

    // ---- world ----
    @JvmField val anchors = ArrayList<Anchor>(64)
    @JvmField val sparks = ArrayList<Spark>(64)
    @JvmField var camY = -vh * Tune.CAM_ANCHOR
    @JvmField var voidY = vh * 0.9f
    private var topGen = 0f
    private var lastAnchorX = vw / 2f
    private var lastAnchorY = 0f

    @JvmField var time = 0f
    @JvmField var dead = false
    @JvmField var maxUp = 0f
    @JvmField var grabs = 0
    @JvmField var snaps = 0
    @JvmField var combo = 0
    @JvmField var bestCombo = 0
    @JvmField var sparksTaken = 0
    @JvmField var sparkScore = 0

    private var lastReleased: Anchor? = null
    private var lastReleaseAt = -99f
    private var wasHolding = false
    private var pressAt = -99f
    private var nextMilestone = Tune.MILESTONE_M

    // ---- one-frame events, drained by the presenter ----
    @JvmField var evGrab = false
    @JvmField var evSnap = false
    @JvmField var evRelease = false
    @JvmField var evGold = false
    @JvmField var evDie = false
    @JvmField var evSparks = 0
    @JvmField var evComboBreak = false
    @JvmField var evMilestone = 0

    init {
        generate()
    }

    fun clearEvents() {
        evGrab = false; evSnap = false; evRelease = false; evGold = false
        evDie = false; evSparks = 0; evComboBreak = false; evMilestone = 0
    }

    /** Altitude in the score's units. */
    val metres: Int get() = (maxUp / unit * Tune.METRES_PER_UNIT).toInt()

    val multiplier: Int get() = min(10, 1 + combo / 5)

    val score: Int get() = metres + sparkScore

    /** True while a press is still hunting for something to grab. */
    val reaching: Boolean
        get() = wasHolding && anchor == null && time - pressAt < Tune.GRAB_WINDOW

    /** How far into the difficulty ramp the player has climbed, 0..1. */
    val ramp: Float get() = min(1f, (-topGen / unit) / Tune.RAMP_UNITS)

    // ------------------------------------------------------------ generation

    /**
     * Anchors are laid down as a meandering chain: each one is placed within
     * reach of the previous, so a route up always exists. Difficulty is the
     * step growing toward the limit of the grapple, plus drifting and one-shot
     * anchors — never an impossible gap.
     */
    private fun generate() {
        val margin = vw * 0.12f
        while (topGen > camY - vh) {
            val r = min(1f, (-topGen / unit) / Tune.RAMP_UNITS)
            val maxStep = Tune.GRAB_R * unit * (Tune.STEP_EASY + r * (Tune.STEP_HARD - Tune.STEP_EASY))
            val dy = (0.55f + rnd() * 0.32f) * maxStep
            val dxMax = sqrt(max(0f, maxStep * maxStep - dy * dy))

            var nx = lastAnchorX + (rnd() * 2f - 1f) * dxMax
            if (nx < margin || nx > vw - margin) nx = lastAnchorX - (nx - lastAnchorX)
            nx = nx.coerceIn(margin, vw - margin)
            val ny = lastAnchorY - dy

            val roll = rnd()
            val kind = when {
                roll < 0.030f + r * 0.02f -> ANCHOR_GOLD
                roll < 0.10f + r * 0.25f -> ANCHOR_DRIFT
                roll < 0.16f + r * 0.34f -> ANCHOR_ONCE
                else -> ANCHOR_NORMAL
            }
            val drift = if (kind == ANCHOR_DRIFT) (rnd() * 2f - 1f) * 0.07f * vw else 0f
            val a = Anchor(nx, ny, drift, rnd() * 6.2832f, kind)
            anchors.add(a)

            // A spark on the line between the two anchors marks the good route.
            if (anchors.size > 1) {
                val mx = (lastAnchorX + nx) * 0.5f + (rnd() * 2f - 1f) * unit * 0.05f
                val my = (lastAnchorY + ny) * 0.5f
                sparks.add(Spark(mx, my))
            }

            lastAnchorX = nx
            lastAnchorY = ny
            topGen = ny
        }
        if (anchors.size > 70) {
            val keep = anchor
            anchors.retainAll { it.y < camY + vh * 1.6f || it === keep }
            sparks.retainAll { it.y < camY + vh * 1.6f }
        }
    }

    // ------------------------------------------------------------- simulation

    fun update(dt: Float, holding: Boolean) {
        if (dead) return
        time += dt

        for (a in anchors) {
            a.ax = if (a.drift != 0f) a.x + sin(time * 1.4f + a.phase) * a.drift else a.x
            if (a.spent) a.fade = max(0f, a.fade - dt * 1.8f)
            if (a.pulse > 0f) a.pulse = max(0f, a.pulse - dt * 3f)
        }

        vy += Tune.GRAVITY * unit * dt
        px += vx * dt
        py += vy * dt

        // The grapple only reaches for a window after each press. See
        // Tune.GRAB_WINDOW: retrying for as long as the finger was down made
        // holding a magnet, and the release never had to be used.
        if (holding && !wasHolding) pressAt = time
        wasHolding = holding
        if (reaching) tryGrab()

        val a = anchor
        if (a != null) {
            if (!holding) {
                release(false)
            } else {
                val dx = px - a.ax
                val dy = py - a.y
                val dist = max(1e-4f, hypot(dx, dy))
                val nx = dx / dist
                val ny = dy / dist

                // The rope hauls you at the anchor. This is the only source of
                // energy in the system, and it makes hanging still impossible.
                vx -= nx * Tune.PULL * unit * dt
                vy -= ny * Tune.PULL * unit * dt

                // It also cannot stretch, so lateral speed becomes a swing.
                if (dist > ropeLen) {
                    px = a.ax + nx * ropeLen
                    py = a.y + ny * ropeLen
                    val radial = vx * nx + vy * ny
                    if (radial > 0f) {
                        vx -= nx * radial
                        vy -= ny * radial
                    }
                }
                vx *= Tune.DAMP
                vy *= Tune.DAMP

                if (dist < Tune.SNAP * unit) {
                    if (a.kind == ANCHOR_GOLD) {
                        vy -= 1.25f * unit
                        evGold = true
                    }
                    snaps++
                    evSnap = true
                    a.pulse = 1f
                    release(true)
                }
            }
        }

        val sp = hypot(vx, vy)
        val lim = Tune.MAX_SPEED * unit
        if (sp > lim) {
            vx *= lim / sp
            vy *= lim / sp
        }

        val wall = unit * 0.02f
        if (px < wall) { px = wall; vx = abs(vx) * 0.5f }
        if (px > vw - wall) { px = vw - wall; vx = -abs(vx) * 0.5f }

        collectSparks()

        // The camera chases upward fast and drifts back down slowly, so a
        // botched swing costs altitude instead of ending the run instantly.
        val target = py - vh * Tune.CAM_ANCHOR
        val k = if (target < camY) Tune.CAM_UP else Tune.CAM_DOWN
        camY += (target - camY) * min(1f, dt * k)

        voidY -= (Tune.VOID_BASE + time * Tune.VOID_ACCEL) * unit * dt
        voidY = min(voidY, camY + vh * 1.30f)
        camY = min(camY, voidY - vh * 0.80f)

        if (-py > maxUp) {
            maxUp = -py
            while (metres >= nextMilestone) {
                evMilestone = nextMilestone
                nextMilestone += Tune.MILESTONE_M
            }
        }

        generate()

        if (py > voidY) {
            dead = true
            evDie = true
        }
    }

    private fun collectSparks() {
        val r = Tune.SPARK_R * unit
        for (s in sparks) {
            if (s.taken) {
                if (s.pop > 0f) s.pop = max(0f, s.pop - 0.06f)
                continue
            }
            if (hypot(px - s.x, py - s.y) < r) {
                s.taken = true
                s.pop = 1f
                combo++
                if (combo > bestCombo) bestCombo = combo
                sparksTaken++
                sparkScore += 5 * multiplier
                evSparks++
            } else if (!s.missed && s.y > py + unit * 0.45f) {
                // Left behind: the streak is the thing you were protecting.
                s.missed = true
                if (combo > 0) {
                    combo = 0
                    evComboBreak = true
                }
            }
        }
    }

    /** @return true if a rope was attached. */
    fun tryGrab(): Boolean {
        if (anchor != null) return false
        var best: Anchor? = null
        var bd = Tune.GRAB_R * unit
        for (a in anchors) {
            if (a.spent) continue
            // You tether to something above you, and never straight back onto
            // the anchor you just let go of.
            if (a.y > py - Tune.MIN_ROPE * unit) continue
            if (a === lastReleased && time - lastReleaseAt < 0.5f) continue
            val d = hypot(px - a.ax, py - a.y)
            if (d < bd) { bd = d; best = a }
        }
        if (best == null) return false
        anchor = best
        ropeLen = bd.coerceIn(Tune.MIN_ROPE * unit, Tune.MAX_ROPE * unit)
        grabs++
        evGrab = true
        return true
    }

    fun release(snapped: Boolean) {
        val a = anchor ?: return
        lastReleased = a
        lastReleaseAt = time
        anchor = null
        if (a.kind == ANCHOR_ONCE || a.kind == ANCHOR_GOLD) a.spent = true
        vx *= Tune.RELEASE_KICK
        vy *= Tune.RELEASE_KICK
        if (!snapped) evRelease = true
    }
}
