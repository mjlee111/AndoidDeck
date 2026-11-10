package dev.menggu.androiddeck

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import dev.menggu.androiddeck.data.Net

class DeckApplication : Application(), SingletonImageLoader.Factory {
    /** App icons come from the PCs; requests go through each PC's pinned, token-bearing client. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { Net.callFactory }))
                add(SvgDecoder.Factory())
            }
            .crossfade(true)
            .build()
}
