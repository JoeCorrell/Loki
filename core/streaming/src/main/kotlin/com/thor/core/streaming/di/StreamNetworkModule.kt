package com.thor.core.streaming.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * The one HTTP client the process uses.
 *
 * It lived in `:data`'s `NetworkModule` while the launcher was the only app in
 * the build. It is here now because `:core:streaming` needs it and must not
 * depend on `:data` — and it is *moved* rather than copied, because a second
 * unqualified `OkHttpClient` in the same Hilt graph is a duplicate binding, and
 * a second one behind a qualifier would be two connection pools and two caches
 * where the launcher previously had one.
 *
 * `:data` reaches it through its `api` dependency on this module, so every
 * scraper and media client keeps the client it already had, configured exactly
 * as it already was.
 */
@Module
@InstallIn(SingletonComponent::class)
object StreamNetworkModule {

    @Provides
    @Singleton
    fun providesOkHttpClient(
        @ApplicationContext context: Context,
    ): OkHttpClient = OkHttpClient.Builder()
        // Metadata responses are highly cacheable and a rescan re-requests the
        // same endpoints; an on-disk cache turns a repeat scrape into no traffic.
        .cache(Cache(java.io.File(context.cacheDir, "http"), HTTP_CACHE_BYTES))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private const val HTTP_CACHE_BYTES = 64L * 1024 * 1024
}
