package com.mikmy.tether

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * SurfaceView + a dedicated render thread running a plain variable-timestep
 * loop. Touch events arrive on the UI thread, so every mutation of [Game] is
 * guarded by a single lock shared with the render thread.
 */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    private val sfx = Sfx()
    private val game = Game(context, sfx)
    private val lock = Any()

    @Volatile private var running = false
    @Volatile private var paused = false
    private var thread: Thread? = null

    init {
        holder.addCallback(this)
        isFocusable = true
        keepScreenOn = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        sfx.start()
        startThread()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        synchronized(lock) { game.resize(width, height) }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopThread()
        sfx.stop()
    }

    fun onPauseGame() {
        paused = true
    }

    fun onResumeGame() {
        paused = false
    }

    fun handleBack(): Boolean = synchronized(lock) { game.handleBack() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // The entire game is one bit: is a finger down or not.
        synchronized(lock) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> game.onDown()
                MotionEvent.ACTION_UP -> game.onUp()
                MotionEvent.ACTION_CANCEL -> game.onUp()
            }
        }
        return true
    }

    private fun startThread() {
        if (running) return
        running = true
        thread = Thread {
            var last = System.nanoTime()
            while (running) {
                if (paused) {
                    try {
                        Thread.sleep(60)
                    } catch (e: InterruptedException) {
                        break
                    }
                    last = System.nanoTime()
                    continue
                }
                val now = System.nanoTime()
                val dt = ((now - last) / 1_000_000_000.0).toFloat()
                last = now

                // GPU-backed canvas (API 26+); every frame repaints the whole
                // surface, so the unpreserved back buffer is fine.
                val canvas = try {
                    holder.lockHardwareCanvas()
                } catch (e: Throwable) {
                    try {
                        holder.lockCanvas()
                    } catch (e2: Throwable) {
                        null
                    }
                }
                if (canvas == null) {
                    try {
                        Thread.sleep(8)
                    } catch (e: InterruptedException) {
                        break
                    }
                    continue
                }
                try {
                    synchronized(lock) {
                        game.update(dt)
                        game.draw(canvas)
                    }
                } finally {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (e: Throwable) {
                        // surface went away mid-frame
                    }
                }
            }
        }.also { it.name = "chroma-render"; it.start() }
    }

    private fun stopThread() {
        running = false
        thread?.let {
            it.interrupt()
            try {
                it.join(800)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        thread = null
    }
}
