package io.github.mbaliga.fylz.wallpaper

import android.content.res.Configuration
import android.graphics.Canvas
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder

/**
 * The pond-water live wallpaper. Mirrors Animalcules' `AnimalculesWallpaperService`'s engine
 * shape -- Choreographer-paced main-thread Canvas rendering capped near 60fps, visibility
 * gating, a battery-polite freeze after a stretch of continuous visibility -- applied to
 * [PondWaterRenderer] instead of the full critter `World`. No settings activity, no scenes, no
 * sensors: for v1 this wallpaper is exactly the ambient water, nothing configurable.
 *
 * Registered as `io.github.mbaliga.fylz.wallpaper.FylzPondWallpaperService` with metadata at
 * `res/xml/fylz_wallpaper.xml` -- both names are load-bearing for the `<service>` block another
 * workstream wires into the manifest, so neither may move without updating that block too.
 */
class FylzPondWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = PondEngine()

    private inner class PondEngine : Engine() {
        private val renderer = PondWaterRenderer(isDark = isNightMode())
        private val choreographer = Choreographer.getInstance()
        private var scheduled = false
        private var visible = false
        private var hasSize = false
        private var lastNanos = 0L
        private var visibleSinceNanos = 0L

        private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
            scheduled = false
            drawFrame(frameTimeNanos)
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            scheduleFrame()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            renderer.resize(width, height, resources.displayMetrics.density)
            hasSize = true
            scheduleFrame()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                renderer.setDark(isNightMode())
                lastNanos = 0L
                visibleSinceNanos = System.nanoTime()
                scheduleFrame()
            } else if (!isPreview) {
                stopFrames()
            }
        }

        override fun onTouchEvent(event: MotionEvent) {
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                renderer.poke(event.x, event.y)
                // A touch re-animates for another active window even mid-freeze, same as a
                // fresh visibility change would.
                if (visible) {
                    visibleSinceNanos = System.nanoTime()
                    if (!scheduled) { lastNanos = 0L; scheduleFrame() }
                }
            }
            super.onTouchEvent(event)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            stopFrames()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            stopFrames()
            super.onDestroy()
        }

        private fun isNightMode(): Boolean {
            val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return mode == Configuration.UI_MODE_NIGHT_YES
        }

        private fun scheduleFrame() {
            if (scheduled) return
            scheduled = true
            choreographer.postFrameCallback(frameCallback)
        }

        private fun stopFrames() {
            choreographer.removeFrameCallback(frameCallback)
            scheduled = false
            lastNanos = 0L
        }

        private fun drawFrame(frameTimeNanos: Long) {
            if (!visible && !isPreview) return
            // Nothing to draw sensibly before the first onSurfaceChanged -- try again next vsync
            // rather than rendering a 1x1-seeded field into a full-size surface.
            if (!hasSize) { scheduleFrame(); return }

            // ~60fps cap: on high-refresh panels, skip the extra vsyncs.
            if (lastNanos != 0L && frameTimeNanos - lastNanos < MIN_FRAME_NANOS) { scheduleFrame(); return }

            val holder = surfaceHolder
            val canvas: Canvas? = try { holder.lockCanvas() } catch (_: Throwable) { null }
            if (canvas != null) {
                try {
                    var dt = if (lastNanos == 0L) 0f else (frameTimeNanos - lastNanos) / 1_000_000_000f
                    lastNanos = frameTimeNanos
                    if (dt > 0.05f) dt = 0.05f
                    renderer.step(dt)
                    renderer.draw(canvas)
                } catch (_: Throwable) {
                    // Never let one bad frame crash the wallpaper.
                } finally {
                    try { holder.unlockCanvasAndPost(canvas) } catch (_: Throwable) {}
                }
            }

            // Battery-polite freeze: after ACTIVE_NANOS of continuous visibility, stop scheduling
            // and hold the last drawn frame. onVisibilityChanged / onTouchEvent both re-animate.
            if (!isPreview && visibleSinceNanos != 0L && frameTimeNanos - visibleSinceNanos > ACTIVE_NANOS) {
                return
            }

            scheduleFrame()
        }
    }

    private companion object {
        const val MIN_FRAME_NANOS = 1_000_000_000L / 63L
        const val ACTIVE_NANOS = 8_000_000_000L
    }
}
