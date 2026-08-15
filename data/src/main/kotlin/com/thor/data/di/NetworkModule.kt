package com.thor.data.di

import com.thor.data.metadata.MetadataProvider
import com.thor.data.metadata.ScreenScraperProvider
import com.thor.data.metadata.SteamGridDbProvider
import com.thor.data.metadata.WikidataProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun providesJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = false
    }

    /*
     * The `OkHttpClient` this module used to provide now lives in
     * `:core:streaming`, as `StreamNetworkModule`.
     *
     * It moved rather than being copied: the streaming layer is its own module
     * now and needs a client, and two unqualified `OkHttpClient` bindings in one
     * Hilt graph will not compile. Everything here still injects it exactly as
     * before, through this module's `api` dependency on `:core:streaming`.
     */
}

/**
 * Binds the available metadata providers into a set.
 *
 * Adding a provider is a matter of writing the class and adding one `@Binds`
 * here; nothing downstream changes, because the aggregator only ever sees the
 * set.
 */
@Module
@InstallIn(SingletonComponent::class)
interface MetadataProviderModule {

    /**
     * The leading source: hash-matched, and the only one that answers the whole
     * question rather than a corner of it.
     */
    @Binds
    @IntoSet
    fun bindsScreenScraper(provider: ScreenScraperProvider): MetadataProvider

    /**
     * The square cell icon, which nothing else here holds -- ScreenScraper's
     * nearest square image is a photograph of the cartridge. See ICON_PROVIDER.
     */
    @Binds
    @IntoSet
    fun bindsSteamGridDb(provider: SteamGridDbProvider): MetadataProvider

    /**
     * Keyless, so it is the only provider that contributes with nothing signed
     * in to anywhere.
     */
    @Binds
    @IntoSet
    fun bindsWikidata(provider: WikidataProvider): MetadataProvider
}
