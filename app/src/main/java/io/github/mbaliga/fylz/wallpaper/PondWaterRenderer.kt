package io.github.mbaliga.fylz.wallpaper

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Ambient water only — ported from Animalcules' `World.kt` pond ambiance (its bokeh blobs,
 * drifting motes, poke ripple and background wash), deliberately WITHOUT any species/critter
 * layer (owner decision: "just do pond water").
 *
 * [step] is pure simulation: it takes `dt` from the caller instead of reading a clock, so
 * position/velocity math is unit-testable without a fake `Choreographer` or Robolectric. All
 * randomness comes from the injected [random] rather than a global RNG, so a fixed-seed instance
 * reproduces the exact same field and drift every run — what makes [step]'s deltas assertable in
 * `PondWaterRendererTest`.
 *
 * No `android.graphics` object is ever constructed until [draw] actually runs (paints and
 * shaders are built lazily on first/next draw, never in a field initializer or in [resize] /
 * [step] / [poke]) — that keeps the whole simulation surface safe to construct and drive from a
 * plain JVM unit test with no Robolectric, since `android.graphics.Paint()` etc. throw under the
 * unit-test `android.jar`'s stub method bodies. [draw] itself is therefore the one member never
 * exercised directly by a JVM test; it is exercised by the two real callers that own a canvas —
 * [io.github.mbaliga.fylz.ui.desktop.WallpaperLayer]'s Compose `Canvas` and
 * [FylzPondWallpaperService]'s `SurfaceHolder` canvas.
 */
class PondWaterRenderer(
    private val random: Random = Random.Default,
    isDark: Boolean = true,
) {
    private var isDark: Boolean = isDark

    private class Blob(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var wavePhase: Float,
        val waveSpeed: Float,
        val radiusX: Float,
        val radiusY: Float,
        val depth: Float,
        val baseAlpha: Float,
    ) {
        var shader: RadialGradient? = null
    }

    private class Mote(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        val radius: Float,
        val depth: Float,
    )

    private var width = 1
    private var height = 1
    private var density = 1f

    private val blobs = ArrayList<Blob>()
    private val motes = ArrayList<Mote>()

    private var pokeX = 0f
    private var pokeY = 0f
    private var pokeStrength = 0f

    // Reused across [computePoke] calls instead of allocating a Pair/array every entity every
    // frame — cheap on a JVM but needless churn on a 60fps main-thread wallpaper loop.
    private var pokeDx = 0f
    private var pokeDy = 0f

    private var paintsReady = false
    private lateinit var backgroundPaint: Paint
    private lateinit var blobPaint: Paint
    private lateinit var motePaint: Paint
    private lateinit var pokePaint: Paint

    private var shadersDirty = true
    private var backgroundShader: LinearGradient? = null

    /**
     * (Re)seeds the field for a new surface size. Safe to call repeatedly (e.g. on rotation) —
     * entities are rebuilt from scratch at the new bounds, mirroring `World.resize()` +
     * `ensurePrepared()`'s "rebuild ambiance at the new size" rather than trying to rescale
     * entities that were laid out for different bounds.
     */
    fun resize(w: Int, h: Int, density: Float) {
        width = max(1, w)
        height = max(1, h)
        this.density = max(0.5f, density)
        seedField()
        shadersDirty = true
    }

    /** Switches the dark/light water palette. A no-op (no reseed, no shader rebuild) if unchanged. */
    fun setDark(dark: Boolean) {
        if (isDark == dark) return
        isDark = dark
        shadersDirty = true
    }

    fun poke(x: Float, y: Float) {
        pokeX = x
        pokeY = y
        pokeStrength = 1f
    }

    /**
     * Advances the simulation by [dtSeconds]. `dt <= 0f` is a no-op — it never reverses drift or
     * corrupts the decaying poke impulse, which is what makes `dt = 0` an identity on positions.
     */
    fun step(dtSeconds: Float) {
        if (dtSeconds <= 0f) return
        val dt = dtSeconds.coerceAtMost(0.1f)

        // Continuous exponential decay rather than a fixed per-frame multiplier: correct
        // regardless of the caller's frame rate, unlike `pokeT *= 0.94f` tuned for one cadence.
        if (pokeStrength > 0f) pokeStrength *= exp(-dt * POKE_DECAY_RATE)
        if (pokeStrength < 0.005f) pokeStrength = 0f

        for (b in blobs) {
            b.wavePhase += dt * b.waveSpeed
            b.vx += cos(b.wavePhase) * 3f * density * dt
            b.vy += sin(b.wavePhase * 1.3f) * 3f * density * dt
            b.vx *= 0.98f
            b.vy *= 0.98f
            val maxV = 9f * density
            val speed = hypot(b.vx, b.vy)
            if (speed > maxV && speed > 0f) {
                b.vx = b.vx / speed * maxV
                b.vy = b.vy / speed * maxV
            }
            // Blobs feel the poke more gently than motes -- they're bigger, slower water, not flecks.
            computePoke(b.x, b.y, dt)
            b.vx += pokeDx * 0.5f
            b.vy += pokeDy * 0.5f
            b.x += b.vx * dt
            b.y += b.vy * dt
            val marginX = b.radiusX * 2f
            val marginY = b.radiusY * 2f
            if (b.x < -marginX) b.x = width + marginX else if (b.x > width + marginX) b.x = -marginX
            if (b.y < -marginY) b.y = height + marginY else if (b.y > height + marginY) b.y = -marginY
        }

        for (m in motes) {
            m.vx += (random.nextFloat() - 0.5f) * 52f * density * dt
            m.vy += (random.nextFloat() - 0.5f) * 52f * density * dt
            m.vx *= 0.9f
            m.vy *= 0.9f
            computePoke(m.x, m.y, dt)
            m.vx += pokeDx
            m.vy += pokeDy
            m.x += m.vx * dt
            m.y += m.vy * dt
            if (m.x < 0f) m.x += width else if (m.x > width) m.x -= width
            if (m.y < 0f) m.y += height else if (m.y > height) m.y -= height
        }
    }

    fun draw(canvas: Canvas) {
        ensurePaints()
        if (shadersDirty) {
            rebuildShaders()
            shadersDirty = false
        }

        backgroundPaint.shader = backgroundShader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        for (b in blobs) {
            val shader = b.shader ?: continue
            blobPaint.shader = shader
            blobPaint.alpha = (b.baseAlpha.coerceIn(0f, 1f) * (0.6f + b.depth) * 255f).toInt().coerceIn(0, 255)
            canvas.save()
            canvas.translate(b.x, b.y)
            canvas.drawOval(-b.radiusX, -b.radiusY, b.radiusX, b.radiusY, blobPaint)
            canvas.restore()
        }

        motePaint.color = moteTint()
        for (m in motes) {
            motePaint.alpha = (0.55f * m.depth * 255f).toInt().coerceIn(0, 255)
            canvas.drawCircle(m.x, m.y, m.radius, motePaint)
        }

        if (pokeStrength > POKE_VISIBLE_THRESHOLD) drawPokeRing(canvas)
    }

    private fun drawPokeRing(canvas: Canvas) {
        val radius = (1f - pokeStrength) * POKE_RADIUS * density
        pokePaint.color = moteTint()
        pokePaint.alpha = (pokeStrength * 90f).toInt().coerceIn(0, 255)
        pokePaint.strokeWidth = 1.5f * density
        canvas.drawCircle(pokeX, pokeY, radius.coerceAtLeast(1f), pokePaint)
    }

    private fun seedField() {
        blobs.clear()
        motes.clear()

        val area = (width.toFloat() * height) / (density * density)
        val areaK = (area / 260_000f).coerceIn(0.6f, 1.7f)

        val blobCount = (8 + random.nextFloat() * 6f).toInt().coerceIn(MIN_BLOBS, MAX_BLOBS)
        repeat(blobCount) {
            val rx = (18f + random.nextFloat() * 20f) * density
            blobs += Blob(
                x = random.nextFloat() * width,
                y = random.nextFloat() * height,
                vx = (random.nextFloat() - 0.5f) * 8f * density,
                vy = (random.nextFloat() - 0.5f) * 8f * density,
                wavePhase = random.nextFloat() * TAU,
                waveSpeed = 0.3f + random.nextFloat() * 0.3f,
                radiusX = rx,
                radiusY = rx * (0.6f + random.nextFloat() * 0.35f),
                depth = 0.12f + random.nextFloat() * 0.28f,
                baseAlpha = 0.14f + random.nextFloat() * 0.26f,
            )
        }

        val moteCount = max(MIN_MOTES, (MOTE_TARGET * areaK).toInt())
        repeat(moteCount) {
            motes += Mote(
                x = random.nextFloat() * width,
                y = random.nextFloat() * height,
                vx = (random.nextFloat() - 0.5f) * 10f * density,
                vy = (random.nextFloat() - 0.5f) * 10f * density,
                radius = (0.7f + random.nextFloat() * 0.9f) * density,
                depth = 0.2f + random.nextFloat() * 0.8f,
            )
        }
    }

    /**
     * Computes the poke's velocity contribution at (x, y) for this frame into [pokeDx]/[pokeDy].
     * Linear falloff to zero at [POKE_RADIUS] (scaled by density) from the poke centre, so an
     * entity near the tap feels a stronger push than one far away — the behaviour
     * `PondWaterRendererTest` pins for motes.
     */
    private fun computePoke(x: Float, y: Float, dt: Float) {
        pokeDx = 0f
        pokeDy = 0f
        if (pokeStrength <= POKE_VISIBLE_THRESHOLD) return
        val dx = x - pokeX
        val dy = y - pokeY
        val radius = POKE_RADIUS * density
        val distSq = dx * dx + dy * dy
        if (distSq >= radius * radius) return
        val dist = sqrt(distSq).let { if (it < 1f) 1f else it }
        val falloff = 1f - dist / radius
        val force = falloff * pokeStrength * POKE_FORCE * density * dt
        pokeDx = dx / dist * force
        pokeDy = dy / dist * force
    }

    private fun ensurePaints() {
        if (paintsReady) return
        backgroundPaint = Paint()
        blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        motePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        pokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        paintsReady = true
    }

    private fun rebuildShaders() {
        val base = if (isDark) DEEP_TEAL else LIGHT_TEAL
        backgroundShader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(
                shade(base, if (isDark) 0.18f else 0.12f),
                base,
                shade(base, if (isDark) -0.28f else -0.10f),
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )

        val tint = if (isDark) BLOB_TINT_DARK else BLOB_TINT_LIGHT
        val transparentTint = tint and 0x00FFFFFF
        for (b in blobs) {
            val r = max(b.radiusX, b.radiusY)
            b.shader = RadialGradient(
                0f, 0f, r,
                intArrayOf(tint, transparentTint),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
    }

    private fun moteTint(): Int = if (isDark) MOTE_TINT_DARK else MOTE_TINT_LIGHT

    /** Test/debug seam: entity centres in canvas space. Insertion order is stable for a given
     *  [random] seed only because [seedField] always rebuilds blobs then motes in the same order. */
    internal fun blobPositions(): List<Pair<Float, Float>> = blobs.map { it.x to it.y }
    internal fun motePositions(): List<Pair<Float, Float>> = motes.map { it.x to it.y }
    internal fun blobCount(): Int = blobs.size
    internal fun moteCount(): Int = motes.size

    private companion object {
        const val TAU = 6.2831855f

        const val MIN_BLOBS = 8
        const val MAX_BLOBS = 14
        const val MOTE_TARGET = 40
        const val MIN_MOTES = 10

        const val POKE_RADIUS = 240f
        const val POKE_FORCE = 900f
        const val POKE_DECAY_RATE = 2.4f
        const val POKE_VISIBLE_THRESHOLD = 0.01f

        val DEEP_TEAL = 0xFF395C6A.toInt()
        val LIGHT_TEAL = 0xFFB8D4DC.toInt()
        val BLOB_TINT_DARK = 0xFFDCEEEC.toInt()
        val BLOB_TINT_LIGHT = 0xFF224852.toInt()
        val MOTE_TINT_DARK = 0xFFE6F2F0.toInt()
        val MOTE_TINT_LIGHT = 0xFF2E4A54.toInt()

        /** Lerp a colour toward white (f>0) or black (f<0) by |f| -- same idea as Animalcules'
         *  `MathUtil.shade`, hand-written here since Fylz doesn't depend on that app's module. */
        fun shade(color: Int, f: Float): Int {
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            val target = if (f < 0f) 0 else 255
            val amount = abs(f).coerceIn(0f, 1f)
            val nr = (r + (target - r) * amount).toInt().coerceIn(0, 255)
            val ng = (g + (target - g) * amount).toInt().coerceIn(0, 255)
            val nb = (b + (target - b) * amount).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
    }
}
