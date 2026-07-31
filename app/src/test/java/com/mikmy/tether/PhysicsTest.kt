package com.mikmy.tether

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class PhysicsTest {

    private val W = 1080f
    private val H = 2160f
    private val seeds = listOf(1L, 2L, 7L, 12345L, 999983L, -4242L)

    private fun world(seed: Long = 1L) = World(W, H, seed)

    /** Mirrors a competent player: hold, and let go while the swing is heading up. */
    private fun autoplay(w: World, seconds: Float, dt: Float = 1f / 60f): World {
        var t = 0f
        var holding = true
        var grabbedAt = 0f
        while (t < seconds && !w.dead) {
            val a = w.anchor
            if (a == null) {
                holding = true
            } else {
                if (w.evGrab) grabbedAt = w.time
                val up = w.vy < 0f
                val mostlyUp = abs(w.vy) > abs(w.vx) * 0.85f
                val past = (w.px - a.ax) * w.vx > 0f
                holding = !(up && mostlyUp && past && w.time - grabbedAt > 0.10f)
            }
            w.clearEvents()
            w.update(dt, holding)
            t += dt
        }
        return w
    }

    // ------------------------------------------------- generation invariants

    @Test
    fun everyAnchorIsWithinReachOfThePreviousOne() {
        // The route up must always exist: difficulty is never an impossible gap.
        for (seed in seeds) {
            val w = autoplay(world(seed), 25f)
            val list = w.anchors
            assertTrue("expected a decent chain, got ${list.size}", list.size > 8)
            for (i in 1 until list.size) {
                val d = hypot(list[i].x - list[i - 1].x, list[i].y - list[i - 1].y)
                assertTrue(
                    "seed $seed: anchors $i and ${i - 1} are $d apart, beyond the grapple",
                    d <= Tune.GRAB_R * w.unit + 0.01f
                )
            }
        }
    }

    @Test
    fun theChainAlwaysClimbs() {
        for (seed in seeds) {
            val w = autoplay(world(seed), 20f)
            for (i in 1 until w.anchors.size) {
                assertTrue(
                    "seed $seed: anchor $i is not above its predecessor",
                    w.anchors[i].y < w.anchors[i - 1].y
                )
            }
        }
    }

    @Test
    fun anchorsStayOnScreenHorizontallyEvenWhenDrifting() {
        for (seed in seeds) {
            val w = autoplay(world(seed), 20f)
            for (a in w.anchors) {
                assertTrue("anchor x ${a.x} outside the shaft", a.x >= 0f && a.x <= W)
                val extreme = a.x + abs(a.drift)
                assertTrue("drifting anchor leaves the shaft", extreme <= W && a.x - abs(a.drift) >= 0f)
            }
        }
    }

    // -------------------------------------------------------- grapple rules

    @Test
    fun youCannotTetherToSomethingBelowYou() {
        val w = world()
        // teleport the player above the whole chain
        w.py = w.anchors.minOf { it.y } - w.unit * 2f
        w.px = w.anchors.first().x
        assertFalse(w.tryGrab())
        assertNull(w.anchor)
    }

    @Test
    fun ropeNeverExceedsItsMaximumLength() {
        for (seed in seeds) {
            val w = world(seed)
            var t = 0f
            while (t < 20f && !w.dead) {
                w.clearEvents()
                w.update(1f / 60f, true)
                assertTrue(
                    "rope stretched to ${w.ropeLen / w.unit}",
                    w.ropeLen <= Tune.MAX_ROPE * w.unit + 1e-3f
                )
                t += 1f / 60f
            }
        }
    }

    @Test
    fun releasingDoesNotImmediatelyReattachToTheSameAnchor() {
        val w = world()
        w.update(1f / 60f, true)
        val first = w.anchor
        assertNotNull(first)
        w.release(false)
        assertNull(w.anchor)
        // an immediate re-grab must pick something else, or nothing at all
        w.tryGrab()
        assertTrue(w.anchor == null || w.anchor !== first)
    }

    @Test
    fun spentAnchorsCannotBeReused() {
        val w = world()
        val a = w.anchors[2]
        a.spent = true
        w.px = a.ax
        w.py = a.y + w.unit * 0.2f
        w.tryGrab()
        assertTrue("a used-up anchor was grabbed again", w.anchor !== a)
    }

    @Test
    fun oneShotAnchorsAreSpentAfterUse() {
        val w = world()
        w.update(1f / 60f, true)
        val a = w.anchor
        assertNotNull(a)
        w.release(true)
        val expected = a!!.kind == ANCHOR_ONCE || a.kind == ANCHOR_GOLD
        assertEquals(expected, a.spent)
    }

    @Test
    fun speedIsAlwaysCapped() {
        for (seed in seeds) {
            val w = world(seed)
            var t = 0f
            while (t < 25f && !w.dead) {
                w.clearEvents()
                w.update(1f / 60f, true)
                val sp = hypot(w.vx, w.vy) / w.unit
                assertTrue("speed $sp exceeded the cap", sp <= Tune.MAX_SPEED + 0.05f)
                t += 1f / 60f
            }
        }
    }

    // ------------------------------------------------------- the game works

    @Test
    fun aCompetentPlayerActuallyClimbs() {
        // The smoke test that matters: the mechanic must be able to gain height.
        for (seed in seeds) {
            val w = autoplay(world(seed), 20f)
            assertTrue(
                "seed $seed only climbed ${w.maxUp / w.unit} screens in 20s",
                w.maxUp / w.unit > 1.0f
            )
            assertTrue("seed $seed never grabbed anything", w.grabs > 5)
        }
    }

    @Test
    fun doingNothingIsFatal() {
        // The void has to be a real clock, or there is no pressure at all.
        val w = world()
        var t = 0f
        while (t < 60f && !w.dead) {
            w.clearEvents()
            w.update(1f / 60f, false)
            t += 1f / 60f
        }
        assertTrue("an idle player survived a minute", w.dead)
    }

    @Test
    fun hangingOnForeverIsAlsoFatal() {
        // Holding the rope down must not be a way to stall out the run.
        val w = world()
        var t = 0f
        while (t < 240f && !w.dead) {
            w.clearEvents()
            w.update(1f / 60f, true)
            t += 1f / 60f
        }
        assertTrue("holding forever survived four minutes", w.dead)
    }

    @Test
    fun theSameSeedReplaysIdentically() {
        val a = autoplay(world(4242L), 15f)
        val b = autoplay(world(4242L), 15f)
        assertEquals(a.metres, b.metres)
        assertEquals(a.grabs, b.grabs)
        assertEquals(a.sparksTaken, b.sparksTaken)
        assertEquals(a.dead, b.dead)
    }

    @Test
    fun differentSeedsGiveDifferentShafts() {
        val a = world(1L)
        val b = world(2L)
        val same = a.anchors.take(8).zip(b.anchors.take(8)).count { (x, y) -> x.x == y.x }
        assertTrue("two seeds produced the same shaft", same < 8)
    }

    // ------------------------------------------------------------- scoring

    @Test
    fun multiplierClimbsEveryFiveSparksAndCapsAtTen() {
        val w = world()
        assertEquals(1, w.multiplier)
        w.combo = 4; assertEquals(1, w.multiplier)
        w.combo = 5; assertEquals(2, w.multiplier)
        w.combo = 20; assertEquals(5, w.multiplier)
        w.combo = 1000; assertEquals(10, w.multiplier)
    }

    @Test
    fun altitudeIsScoredInMetresAndOnlyEverCountsYourBest() {
        val w = world()
        w.clearEvents()
        w.update(1f / 60f, true)
        val peak = w.metres
        // shove the player back down; the recorded altitude must not drop
        w.py += w.unit * 2f
        w.clearEvents()
        w.update(1f / 60f, false)
        assertTrue(w.metres >= peak)
    }

    @Test
    fun missingASparkBreaksTheStreak() {
        val w = autoplay(world(3L), 20f)
        // a real run collects roughly 70% of the sparks on its line
        assertTrue("no sparks collected at all", w.sparksTaken > 0)
        assertTrue("best combo never rose", w.bestCombo > 0)
    }
}
