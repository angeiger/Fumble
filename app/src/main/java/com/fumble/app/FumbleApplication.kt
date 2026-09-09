package com.fumble.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import coil.request.CachePolicy
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class FumbleApplication : Application(), ImageLoaderFactory {

    /**
     * Every image already lives on this device, so a disk cache would only duplicate
     * bytes the app exists to reclaim. A generous memory cache instead: it is what
     * makes swiping back and forth through the stack feel instant.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.30)
                .build()
        }
        .diskCachePolicy(CachePolicy.DISABLED)
        .crossfade(true)
        .build()
}
