package com.thor.data.files

import android.content.Context
import com.thor.core.common.log.ThorLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One unpublished file operation whose temporary output may survive a process.
 *
 * The actual bytes are always written beside [destination] under [staging] and
 * renamed only after verification. This record is the second half of that
 * protocol: if Android kills Loki between those two steps, the next explorer
 * session knows exactly which hidden item is incomplete and may remove it
 * without guessing from a filename prefix.
 *
 * A published move deliberately remains in the journal until its source has
 * been removed. Recovery never resumes that deletion. If the process stopped in
 * the middle, the verified destination is kept and whatever remains at the
 * source is kept too. Duplicates are untidy; guessing which one to delete is how
 * recovery software loses data.
 */
@Serializable
data class FileOperationRecord(
    val id: String = UUID.randomUUID().toString(),
    val kind: FileOperationKind,
    val sources: List<String>,
    val destination: String,
    val staging: String,
    val removeSourcesAfterPublish: Boolean = false,
    val phase: FileOperationPhase = FileOperationPhase.WRITING,
    val startedAtEpochMs: Long = System.currentTimeMillis(),
)

@Serializable
enum class FileOperationKind { COPY, MOVE, COMPRESS, EXTRACT }

@Serializable
enum class FileOperationPhase {
    /** Bytes may be absent, partial, or still buffered. */
    WRITING,

    /** The staged tree matched the bytes read from the source. */
    VERIFIED,

    /** The staged tree was renamed to [FileOperationRecord.destination]. */
    PUBLISHED,
}

/** Small persistence seam, with an in-memory implementation for repository tests. */
interface FileOperationJournal {
    suspend fun begin(record: FileOperationRecord): Boolean
    suspend fun mark(id: String, phase: FileOperationPhase): Boolean
    suspend fun finish(id: String): Boolean
    suspend fun pending(): List<FileOperationRecord>
}

/**
 * A durable journal in app-private SharedPreferences.
 *
 * Each record has its own key and the index is committed in the same editor
 * transaction. SharedPreferences keeps a backup while replacing its XML file,
 * which gives this tiny document the atomic write that an ordinary JSON file
 * would not have when the battery dies during a rename.
 */
@Singleton
class DurableFileOperationJournal @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json,
) : FileOperationJournal {

    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    override suspend fun begin(record: FileOperationRecord): Boolean = mutex.withLock {
        val ids = ids() + record.id
        commit(
            preferences.edit()
                .putString(record.key(), json.encodeToString(record))
                .putStringSet(INDEX, ids),
            "begin ${record.kind.name.lowercase()}",
        )
    }

    override suspend fun mark(id: String, phase: FileOperationPhase): Boolean = mutex.withLock {
        val record = read(id) ?: return@withLock false
        commit(
            preferences.edit().putString(record.key(), json.encodeToString(record.copy(phase = phase))),
            "mark $id ${phase.name.lowercase()}",
        )
    }

    override suspend fun finish(id: String): Boolean = mutex.withLock {
        val ids = ids() - id
        commit(
            preferences.edit().remove(key(id)).putStringSet(INDEX, ids),
            "finish $id",
        )
    }

    override suspend fun pending(): List<FileOperationRecord> = mutex.withLock {
        ids().mapNotNull(::read)
    }

    private fun ids(): Set<String> =
        preferences.getStringSet(INDEX, emptySet()).orEmpty().toSet()

    private fun read(id: String): FileOperationRecord? {
        val payload = preferences.getString(key(id), null) ?: return null
        return runCatching { json.decodeFromString<FileOperationRecord>(payload) }
            .onFailure { ThorLog.w(TAG, "Could not read operation $id: ${it.message}") }
            .getOrNull()
    }

    private fun commit(editor: android.content.SharedPreferences.Editor, action: String): Boolean =
        runCatching { editor.commit() }
            .onFailure { ThorLog.w(TAG, "Could not $action in the file journal: ${it.message}") }
            .getOrDefault(false)
            .also { committed ->
                if (!committed) ThorLog.w(TAG, "Could not $action in the file journal")
            }

    private fun FileOperationRecord.key(): String = key(id)

    private companion object {
        const val TAG = "Files"
        const val PREFERENCES = "file_operation_journal"
        const val INDEX = "operation_ids"
        const val RECORD_PREFIX = "operation_"

        fun key(id: String): String = "$RECORD_PREFIX$id"
    }
}

/** Deterministic and deliberately non-durable; used by local JVM tests. */
class InMemoryFileOperationJournal : FileOperationJournal {
    private val records = LinkedHashMap<String, FileOperationRecord>()
    private val mutex = Mutex()

    override suspend fun begin(record: FileOperationRecord): Boolean = mutex.withLock {
        records[record.id] = record
        true
    }

    override suspend fun mark(id: String, phase: FileOperationPhase): Boolean = mutex.withLock {
        val record = records[id] ?: return@withLock false
        records[id] = record.copy(phase = phase)
        true
    }

    override suspend fun finish(id: String): Boolean = mutex.withLock {
        records.remove(id)
        true
    }

    override suspend fun pending(): List<FileOperationRecord> = mutex.withLock {
        records.values.toList()
    }
}

/** What startup recovery found and handled, for a calm one-line UI notice. */
data class FileRecoveryReport(
    val interrupted: Int = 0,
    val partialsRemoved: Int = 0,
    val publishedKept: Int = 0,
    val moveSourcesRetained: Int = 0,
    val unresolved: Int = 0,
) {
    val foundAnything: Boolean get() = interrupted > 0
}
