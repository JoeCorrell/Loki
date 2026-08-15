package com.thor.data.files

import com.thor.core.datastore.SettingsRepository
import com.thor.core.model.SmbServer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object FilesModule {

    /** The on-disk operation ledger behind crash-safe explorer recovery. */
    @Provides
    @Singleton
    fun provideFileOperationJournal(
        journal: DurableFileOperationJournal,
    ): FileOperationJournal = journal

    /**
     * The configured shares, read from settings each time they are wanted.
     *
     * Read rather than cached, and it costs nothing: DataStore keeps the decoded
     * document in memory, so `current()` is a map over a value already in hand.
     * Caching it here would mean a server edited in Settings kept its old password
     * until something invalidated a copy — and the thing most likely to be edited
     * is a password that did not work.
     */
    @Provides
    @Singleton
    fun provideSmbServerDirectory(settings: SettingsRepository): SmbServerDirectory =
        SmbServerDirectory {
            runCatching { settings.current().smbServers }.getOrDefault(emptyList<SmbServer>())
        }
}
