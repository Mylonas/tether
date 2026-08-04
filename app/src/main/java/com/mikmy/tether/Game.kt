package com.mikmy.tether

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * TETHER
 *
 * You are falling up a shaft with a rising void underneath you. Hold anywhere
 * and a grapple fires at the nearest anchor above and hauls you in; let go and
 * you fly. Let go at the right instant and the arc carries you to the next
 * anchor and through the sparks strung between them. Let go badly and the void
 * gets a little closer.
 *
 * The simulation lives in [World]; this class is presentation, input and juice.
 */
class Game(ctx: Context, private val sfx: Sfx) {

    private val colBg = 0xFF07050D.toInt()
    private val colRope = 0xFFFFFFFF.toInt()
    private val colPlayer = 0xFFFFF3D6.toInt()
    private val colAnchor = 0xFFFFB648.toInt()
    private val colDrift = 0xFF8A5CFF.toInt()
    private val colOnce = 0xFF4FD1C5.toInt()
    private val colGold = 0xFFFFD84D.toInt()
    private val colSpark = 0xFF7FE7FF.toInt()
    private val colVoid = 0xFFFF2E5B.toInt()

    private enum class Phase { TITLE, PLAY, OVER }

    private var phase = Phase.TITLE
    private var world: World? = null

    private var w = 1f
    private var h = 1f
    private var unit = 1f

    private var clock = 0f
    private var overTimer = 0f
    private var shake = 0f
    private var flash = 0f
    private var goldFlash = 0f
    private var hitStop = 0f
    private var holding = false
    private var newBest = false
    private var reachPulse = 0f

    private val prefs = ctx.getSharedPreferences("tether", Context.MODE_PRIVATE)
    private var best = prefs.getInt("best", 0)
    private var bestHeight = prefs.getInt("bestHeight", 0)
    private var bestCombo = prefs.getInt("bestCombo", 0)
    private var runs = prefs.getInt("runs", 0)

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    } catch (e: Throwable) {
        null
    }

    private class Particle(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var life: Float, var maxLife: Float, var size: Float, var col: Int, var streak: Boolean
    )

    private class FloatText(
        var x: Float, var y: Float, var text: String, var col: Int,
        var life: Float, var maxLife: Float, var size: Float
    )

    private val parts = ArrayList<Particle>(400)
    private val texts = ArrayList<FloatText>(12)
    private val trailX = FloatArray(18)
    private val trailY = FloatArray(18)
    private var trailN = 0

    private var starX = FloatArray(0)
    private var starY = FloatArray(0)
    private var starS = FloatArray(0)
    private var starD = FloatArray(0)

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fontBold: Typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
    private val fontCond: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    fun resize(width: Int, height: Int) {
        val changed = width.toFloat() != w || height.toFloat() != h
        w = width.toFloat()
        h = height.toFloat()
        unit = min(w, h)
        val n = 90
        starX = FloatArray(n); starY = FloatArray(n); starS = FloatArray(n); starD = FloatArray(n)
        for (i in 0 until n) {
            starX[i] = Random.nextFloat() * w
            starY[i] = Random.nextFloat() * h
            starS[i] = unit * (0.0012f + Random.nextFloat() * 0.003f)
            starD[i] = 0.25f + Random.nextFloat() * 0.75f   // parallax depth
        }
        // Only a real size change invalidates a run in progress; coming back
        // from the background must not wipe your climb.
        if (changed && phase == Phase.PLAY) startRun()
    }

    // =============================================================== UPDATE

    fun update(rawDt: Float) {
        var dt = rawDt.coerceIn(0f, 0.05f)
        clock += dt
        if (hitStop > 0f) {
            hitStop -= dt
            dt *= 0.3f
        }
        shake *= (1f - min(1f, dt * 9f))
        flash = max(0f, flash - dt * 2.4f)
        goldFlash = max(0f, goldFlash - dt * 1.6f)
        reachPulse = max(0f, reachPulse - dt * 3f)

        when (phase) {
            Phase.PLAY -> updatePlay(dt)
            Phase.OVER -> overTimer += dt
            Phase.TITLE -> {}
        }

        updateParticles(dt)
        updateTexts(dt)
    }

    private fun updatePlay(dt: Float) {
        val world = this.world ?: return
        world.clearEvents()
        world.update(dt, holding)

        val px = world.px
        val py = world.py

        if (world.evGrab) {
            sfx.play("grab", 0.8f)
            reachPulse = 1f
            burst(px, py, colAnchor, 8, unit * 0.25f)
            tap(8)
        }
        if (world.evSnap) {
            sfx.playRung(min(11, world.combo / 2), 0.85f)
            burst(px, py, colAnchor, 16, unit * 0.6f)
            tap(12)
        }
        if (world.evRelease) sfx.play("release", 0.55f)
        if (world.evSparks > 0) {
            repeat(world.evSparks) { burst(px, py, colSpark, 12, unit * 0.5f) }
            sfx.playRung(min(11, world.combo / 2), 0.7f)
            if (world.combo > 0 && world.combo % 10 == 0) {
                pushText(px, py - unit * 0.12f, "x${world.multiplier}", colSpark, 1.1f, unit * 0.06f)
                sfx.play("tier", 0.9f)
                tap(18)
            }
        }
        if (world.evComboBreak) {
            pushText(px, py - unit * 0.1f, "STREAK LOST", colVoid, 0.8f, unit * 0.038f)
            sfx.play("break", 0.7f)
        }
        if (world.evGold) {
            goldFlash = 1f
            hitStop = 0.06f
            shake = max(shake, unit * 0.012f)
            burst(px, py, colGold, 44, unit * 1.0f)
            pushText(px, py - unit * 0.12f, "LAUNCH", colGold, 1.2f, unit * 0.06f)
            sfx.play("gold", 1f)
            tap(26)
        }
        if (world.evMilestone > 0) {
            pushText(w / 2f, py - h * 0.30f, "${world.evMilestone} m", colAnchor, 1.2f, unit * 0.055f)
            sfx.play("milestone", 0.8f)
        }
        if (world.evDie) gameOver()

        // motion trail
        if (trailN < trailX.size) trailN++
        for (i in trailN - 1 downTo 1) {
            trailX[i] = trailX[i - 1]; trailY[i] = trailY[i - 1]
        }
        trailX[0] = px; trailY[0] = py

        // void spray along the rising edge
        if (Random.nextFloat() < dt * 30f) {
            val vx0 = Random.nextFloat() * w
            parts.add(
                Particle(
                    vx0, world.voidY, (Random.nextFloat() - 0.5f) * unit * 0.2f,
                    -unit * (0.2f + Random.nextFloat() * 0.5f),
                    0.7f, 0.7f, unit * 0.005f, colVoid, false
                )
            )
        }
    }

    private fun startRun() {
        world = World(w, h, System.nanoTime())
        phase = Phase.PLAY
        parts.clear()
        texts.clear()
        trailN = 0
        holding = false
        flash = 0f
        sfx.play("start", 0.9f)
    }

    /** Set by MainActivity so a finished run can offer an interstitial. */
    @JvmField var onRunEnded: (() -> Unit)? = null

    private fun gameOver() {
        val world = this.world ?: return
        phase = Phase.OVER
        overTimer = 0f
        newBest = world.score > best
        runs++
        if (world.score > best) best = world.score
        if (world.metres > bestHeight) bestHeight = world.metres
        if (world.bestCombo > bestCombo) bestCombo = world.bestCombo
        prefs.edit()
            .putInt("best", best)
            .putInt("bestHeight", bestHeight)
            .putInt("bestCombo", bestCombo)
            .putInt("runs", runs)
            .apply()
        shake = unit * 0.03f
        flash = 1f
        burst(world.px, world.py, colVoid, 60, unit * 1.2f)
        sfx.play("die", 1f)
        tap(70)
        onRunEnded?.invoke()
    }

    // ================================================================ INPUT

    fun onDown() {
        when (phase) {
            Phase.TITLE -> {
                startRun()
                holding = true          // the starting tap is also your first grapple
            }
            Phase.PLAY -> holding = true   // World fires the grapple on the press edge
            Phase.OVER -> if (overTimer > 0.6f) startRun()
        }
    }

    fun onUp() {
        holding = false
    }

    fun handleBack(): Boolean = when (phase) {
        Phase.PLAY, Phase.OVER -> {
            phase = Phase.TITLE
            world = null
            true
        }
        Phase.TITLE -> false
    }

    // ------------------------------------------------------------- helpers

    private fun tap(ms: Long) {
        val v = vibrator ?: return
        try {
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Throwable) {
        }
    }

    private fun burst(x: Float, y: Float, col: Int, n: Int, power: Float) {
        if (parts.size > 440) return
        for (i in 0 until n) {
            val a = Random.nextFloat() * 6.2832f
            val sp = power * (0.25f + Random.nextFloat() * 0.9f)
            val life = 0.25f + Random.nextFloat() * 0.45f
            parts.add(
                Particle(
                    x, y, kotlin.math.cos(a) * sp, sin(a) * sp, life, life,
                    unit * (0.003f + Random.nextFloat() * 0.006f), col,
                    Random.nextFloat() < 0.4f
                )
            )
        }
    }

    private fun pushText(x: Float, y: Float, s: String, col: Int, life: Float, size: Float) {
        if (texts.size > 10) texts.removeAt(0)
        texts.add(FloatText(x, y, s, col, life, life, size))
    }

    private fun updateParticles(dt: Float) {
        var i = 0
        while (i < parts.size) {
            val q = parts[i]
            q.life -= dt
            if (q.life <= 0f) { parts.removeAt(i); continue }
            q.x += q.vx * dt
            q.y += q.vy * dt
            val drag = 1f - min(1f, dt * 2.2f)
            q.vx *= drag; q.vy *= drag
            i++
        }
    }

    private fun updateTexts(dt: Float) {
        var i = 0
        while (i < texts.size) {
            val t = texts[i]
            t.life -= dt
            if (t.life <= 0f) { texts.removeAt(i); continue }
            t.y -= dt * unit * 0.10f
            i++
        }
    }

    // ================================================================= DRAW

    fun draw(c: Canvas) {
        c.drawColor(colBg)
        val world = this.world

        val saved = c.save()
        if (shake > 0.4f) {
            c.translate(
                (Random.nextFloat() - 0.5f) * shake * 2f,
                (Random.nextFloat() - 0.5f) * shake * 2f
            )
        }

        val cam = world?.camY ?: 0f
        drawStars(c, cam)

        if (world != null) {
            drawSparks(c, world, cam)
            drawAnchors(c, world, cam)
            drawRope(c, world, cam)
            drawPlayer(c, world, cam)
            drawVoid(c, world, cam)
        }
        drawParticles(c, cam)
        drawTexts(c, cam)
        c.restoreToCount(saved)

        if (world != null && phase != Phase.TITLE) drawHud(c, world)

        if (flash > 0.001f) {
            p.style = Paint.Style.FILL
            p.color = Color.argb((flash * 95).toInt().coerceIn(0, 255), 255, 46, 91)
            c.drawRect(0f, 0f, w, h, p)
        }
        if (goldFlash > 0.001f) {
            p.style = Paint.Style.FILL
            p.color = Color.argb((goldFlash * 60).toInt().coerceIn(0, 255), 255, 216, 77)
            c.drawRect(0f, 0f, w, h, p)
        }

        when (phase) {
            Phase.TITLE -> drawTitle(c)
            Phase.OVER -> drawGameOver(c)
            else -> {}
        }
    }

    private fun drawStars(c: Canvas, cam: Float) {
        p.style = Paint.Style.FILL
        for (i in starX.indices) {
            // parallax: distant stars barely move as you climb
            var y = starY[i] - cam * starD[i] * 0.25f
            y = ((y % h) + h) % h
            val tw = 0.4f + 0.6f * (0.5f + 0.5f * sin(clock * 1.5f + i))
            p.color = Color.argb((120 * tw * starD[i]).toInt().coerceIn(0, 255), 170, 180, 255)
            c.drawCircle(starX[i], y, starS[i], p)
        }
    }

    private fun drawSparks(c: Canvas, world: World, cam: Float) {
        p.style = Paint.Style.FILL
        for (s in world.sparks) {
            val sy = s.y - cam
            if (sy < -unit * 0.2f || sy > h + unit * 0.2f) continue
            if (s.taken) {
                if (s.pop <= 0f) continue
                p.color = withAlpha(colSpark, (s.pop * 160).toInt())
                c.drawCircle(s.x, sy, unit * 0.02f * (1f + (1f - s.pop) * 2.5f), p)
                continue
            }
            val tw = 0.6f + 0.4f * sin(clock * 5f + s.x)
            p.color = withAlpha(colSpark, (60 * tw).toInt())
            c.drawCircle(s.x, sy, unit * 0.026f, p)
            p.color = withAlpha(colSpark, if (s.missed) 60 else 235)
            c.drawCircle(s.x, sy, unit * 0.011f, p)
        }
    }

    private fun drawAnchors(c: Canvas, world: World, cam: Float) {
        val inReach = Tune.GRAB_R * unit
        for (a in world.anchors) {
            val sy = a.y - cam
            if (sy < -unit * 0.3f || sy > h + unit * 0.3f) continue
            if (a.spent && a.fade <= 0f) continue

            val col = when (a.kind) {
                ANCHOR_GOLD -> colGold
                ANCHOR_DRIFT -> colDrift
                ANCHOR_ONCE -> colOnce
                else -> colAnchor
            }
            val alpha = if (a.spent) (a.fade * 255).toInt() else 255
            val near = !a.spent && a.y < world.py &&
                hypot(world.px - a.ax, world.py - a.y) < inReach
            val r = unit * 0.016f * (1f + a.pulse * 0.9f)

            p.style = Paint.Style.FILL
            p.color = withAlpha(col, (alpha * 0.16f).toInt())
            c.drawCircle(a.ax, sy, r * 3.2f, p)
            p.color = withAlpha(col, alpha)
            c.drawCircle(a.ax, sy, r, p)

            // a ring marks anything the grapple can currently reach
            p.style = Paint.Style.STROKE
            p.strokeWidth = unit * 0.004f
            if (near && world.anchor == null) {
                val g = 0.55f + 0.45f * sin(clock * 7f)
                p.color = withAlpha(col, (200 * g).toInt())
                c.drawCircle(a.ax, sy, r * (2.6f + reachPulse * 0.6f), p)
            } else {
                p.color = withAlpha(col, (alpha * 0.30f).toInt())
                c.drawCircle(a.ax, sy, r * 2.1f, p)
            }
            if (a.kind == ANCHOR_ONCE && !a.spent) {
                p.color = withAlpha(col, 120)
                p.strokeWidth = unit * 0.003f
                c.drawLine(a.ax - r * 1.5f, sy, a.ax + r * 1.5f, sy, p)
            }
        }
    }

    private fun drawRope(c: Canvas, world: World, cam: Float) {
        val a = world.anchor ?: return
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = unit * 0.012f
        p.color = withAlpha(colRope, 40)
        c.drawLine(a.ax, a.y - cam, world.px, world.py - cam, p)
        p.strokeWidth = unit * 0.004f
        p.color = withAlpha(colRope, 230)
        c.drawLine(a.ax, a.y - cam, world.px, world.py - cam, p)
    }

    private fun drawPlayer(c: Canvas, world: World, cam: Float) {
        p.style = Paint.Style.FILL
        for (i in trailN - 1 downTo 1) {
            val t = 1f - i.toFloat() / trailN
            p.color = withAlpha(colPlayer, (110 * t * t).toInt())
            c.drawCircle(trailX[i], trailY[i] - cam, unit * 0.016f * t, p)
        }
        val r = unit * 0.020f
        p.color = withAlpha(colPlayer, 60)
        c.drawCircle(world.px, world.py - cam, r * 2.6f, p)
        p.color = colPlayer
        c.drawCircle(world.px, world.py - cam, r, p)

        // The reach only lasts a moment after a press, so show it expiring
        // rather than letting the player hold a dead finger down.
        if (world.reaching) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = unit * 0.0025f
            p.color = withAlpha(colRope, 40)
            c.drawCircle(world.px, world.py - cam, Tune.GRAB_R * unit, p)
        }
    }

    private fun drawVoid(c: Canvas, world: World, cam: Float) {
        val vy = world.voidY - cam
        if (vy > h) return
        p.style = Paint.Style.FILL
        p.color = withAlpha(colVoid, 55)
        c.drawRect(0f, vy, w, h, p)
        p.color = withAlpha(colVoid, 120)
        c.drawRect(0f, vy, w, min(h, vy + unit * 0.03f), p)

        // jagged crest
        p.style = Paint.Style.STROKE
        p.strokeWidth = unit * 0.006f
        p.color = colVoid
        var x = 0f
        val stepX = unit * 0.06f
        var prevY = vy
        while (x < w) {
            val nx = x + stepX
            val ny = vy + sin(clock * 6f + x * 0.02f) * unit * 0.02f
            c.drawLine(x, prevY, nx, ny, p)
            prevY = ny
            x = nx
        }
    }

    private fun drawParticles(c: Canvas, cam: Float) {
        p.strokeCap = Paint.Cap.ROUND
        for (q in parts) {
            val t = (q.life / q.maxLife).coerceIn(0f, 1f)
            p.color = withAlpha(q.col, (255 * t * t).toInt())
            if (q.streak) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = q.size * 1.5f
                c.drawLine(q.x, q.y - cam, q.x - q.vx * 0.03f, q.y - cam - q.vy * 0.03f, p)
            } else {
                p.style = Paint.Style.FILL
                c.drawCircle(q.x, q.y - cam, q.size * (0.4f + t), p)
            }
        }
    }

    private fun drawTexts(c: Canvas, cam: Float) {
        p.style = Paint.Style.FILL
        p.typeface = fontCond
        p.textAlign = Paint.Align.CENTER
        for (t in texts) {
            val k = (t.life / t.maxLife).coerceIn(0f, 1f)
            p.textSize = t.size * (1f + (1f - k) * 0.2f)
            p.color = withAlpha(t.col, (255 * min(1f, k * 2.2f)).toInt())
            c.drawText(t.text, t.x.coerceIn(w * 0.18f, w * 0.82f), t.y - cam, p)
        }
    }

    private fun drawHud(c: Canvas, world: World) {
        p.style = Paint.Style.FILL
        p.typeface = fontBold
        p.textAlign = Paint.Align.CENTER
        val top = h * 0.055f

        p.textSize = unit * 0.10f
        p.color = Color.WHITE
        c.drawText("${world.metres} m", w / 2f, top + unit * 0.085f, p)

        p.typeface = fontCond
        p.textSize = unit * 0.030f
        p.color = withAlpha(Color.WHITE, 120)
        p.textAlign = Paint.Align.LEFT
        c.drawText("BEST $best", w * 0.06f, top + unit * 0.03f, p)
        p.textAlign = Paint.Align.RIGHT
        c.drawText("SCORE ${world.score}", w * 0.94f, top + unit * 0.03f, p)

        if (world.combo > 1) {
            p.textAlign = Paint.Align.CENTER
            p.typeface = fontBold
            p.textSize = unit * 0.042f
            p.color = if (world.multiplier >= 5) colGold else colSpark
            c.drawText("x${world.multiplier}   ${world.combo} SPARKS", w / 2f, top + unit * 0.135f, p)
        }

        // how much room is left before the void
        val gap = ((world.voidY - world.py) / h).coerceIn(0f, 1f)
        if (gap < 0.5f) {
            val a = ((0.5f - gap) / 0.5f).coerceIn(0f, 1f)
            p.style = Paint.Style.FILL
            p.color = withAlpha(colVoid, (a * 200).toInt())
            val bw = w * 0.5f
            c.drawRect(w / 2f - bw / 2f, h * 0.93f, w / 2f - bw / 2f + bw * (1f - gap * 2f), h * 0.937f, p)
        }
    }

    private fun drawTitle(c: Canvas) {
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.typeface = fontBold
        p.textSize = unit * 0.17f
        p.color = colAnchor
        c.drawText("TETHER", w / 2f, h * 0.24f, p)

        p.typeface = fontCond
        p.textSize = unit * 0.038f
        p.color = withAlpha(Color.WHITE, 150)
        val by = h * 0.76f
        c.drawText("HOLD  —  GRAPPLE THE ANCHOR ABOVE", w / 2f, by, p)
        c.drawText("LET GO  —  FLY", w / 2f, by + unit * 0.055f, p)
        c.drawText("THE VOID IS ALWAYS RISING", w / 2f, by + unit * 0.11f, p)

        val pulse = 0.55f + 0.45f * sin(clock * 3.2f)
        p.typeface = fontBold
        p.textSize = unit * 0.06f
        p.color = withAlpha(Color.WHITE, (255 * pulse).toInt())
        c.drawText("TAP TO CLIMB", w / 2f, h * 0.90f, p)

        if (best > 0) {
            p.typeface = fontCond
            p.textSize = unit * 0.042f
            p.color = withAlpha(colGold, 220)
            c.drawText("BEST  $best", w / 2f, h * 0.32f, p)
            p.textSize = unit * 0.030f
            p.color = withAlpha(Color.WHITE, 110)
            c.drawText("HIGHEST ${bestHeight}m   ·   TOP CHAIN $bestCombo   ·   RUNS $runs", w / 2f, h * 0.365f, p)
        }
    }

    private fun drawGameOver(c: Canvas) {
        val world = this.world ?: return
        p.style = Paint.Style.FILL
        p.color = Color.argb((min(1f, overTimer * 2.2f) * 185).toInt(), 5, 3, 10)
        c.drawRect(0f, 0f, w, h, p)

        p.textAlign = Paint.Align.CENTER
        p.typeface = fontBold
        p.textSize = unit * 0.085f
        p.color = colVoid
        c.drawText("SWALLOWED", w / 2f, h * 0.30f, p)

        p.textSize = unit * 0.17f
        p.color = Color.WHITE
        c.drawText(world.score.toString(), w / 2f, h * 0.45f, p)

        p.typeface = fontCond
        p.textSize = unit * 0.042f
        if (newBest) {
            val pulse = 0.5f + 0.5f * sin(clock * 6f)
            p.color = withAlpha(colGold, (255 * (0.6f + 0.4f * pulse)).toInt())
            c.drawText("NEW BEST!", w / 2f, h * 0.51f, p)
        } else {
            p.color = withAlpha(Color.WHITE, 130)
            c.drawText("BEST  $best", w / 2f, h * 0.51f, p)
        }

        p.textSize = unit * 0.036f
        p.color = withAlpha(Color.WHITE, 125)
        c.drawText("${world.metres} m climbed", w / 2f, h * 0.565f, p)
        c.drawText("${world.sparksTaken} sparks  ·  best chain ${world.bestCombo}", w / 2f, h * 0.61f, p)
        c.drawText("${world.time.toInt()}s", w / 2f, h * 0.655f, p)

        if (overTimer > 0.6f) {
            val pulse = 0.55f + 0.45f * sin(clock * 3.4f)
            p.typeface = fontBold
            p.textSize = unit * 0.06f
            p.color = withAlpha(Color.WHITE, (255 * pulse).toInt())
            c.drawText("TAP TO CLIMB AGAIN", w / 2f, h * 0.83f, p)
        }
    }

    private fun withAlpha(col: Int, a: Int): Int =
        (col and 0x00FFFFFF) or ((a.coerceIn(0, 255)) shl 24)
}
