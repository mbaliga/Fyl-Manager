package io.github.mbaliga.fylz

import android.app.Application
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.svg.SvgDecoder
import dev.aarso.crashrecovery.CrashRecovery

/**
 * Installs the shared Hyle-constellation crash-recovery handler (dev.aarso:crash-recovery,
 * wired via the hyle-design-system submodule + includeBuild). A crash is captured to an
 * app-private file only; nothing is ever transmitted anywhere. [MainActivity] checks for a
 * pending report first thing in `onCreate` and shows the recovery screen instead of its
 * normal content when one exists.
 *
 * Also supplies the process-wide Coil loader. The file-type icons are SVGs read out of `assets/`
 * (the artwork uses masks and filters that `VectorDrawable` cannot express), so the decoder has to
 * be registered somewhere every `AsyncImage` can see — a loader built per composable would give
 * each caller its own memory cache and decode the same icon once per screen.
 */
class FylzApplication : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        CrashRecovery.install(this, appLabel = "Fylz")
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(SvgDecoder.Factory())
                if (Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory()) else add(GifDecoder.Factory())
            }
            .build()
}
