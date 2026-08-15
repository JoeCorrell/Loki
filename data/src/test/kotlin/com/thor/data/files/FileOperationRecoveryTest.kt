package com.thor.data.files

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Process-death recovery, using only files the journal explicitly names. */
class FileOperationRecoveryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `recovery removes an abandoned staging file and leaves the source untouched`() = runTest {
        val from = temporaryFolder.newFolder("from")
        val to = temporaryFolder.newFolder("to")
        val source = File(from, "game.sfc").apply { writeText("original") }
        val staging = File(to, ".loki-part-interrupted").apply { writeText("partial") }
        val destination = File(to, "game.sfc")
        val journal = InMemoryFileOperationJournal()
        journal.begin(
            FileOperationRecord(
                id = "copy",
                kind = FileOperationKind.COPY,
                sources = listOf(source.absolutePath),
                destination = destination.absolutePath,
                staging = staging.absolutePath,
            ),
        )
        val repository = repository(journal)

        val report = repository.recoverInterruptedOperations()

        assertThat(report.interrupted).isEqualTo(1)
        assertThat(report.partialsRemoved).isEqualTo(1)
        assertThat(report.unresolved).isEqualTo(0)
        assertThat(staging.exists()).isFalse()
        assertThat(destination.exists()).isFalse()
        assertThat(source.readText()).isEqualTo("original")
        assertThat(journal.pending()).isEmpty()
    }

    @Test
    fun `recovery keeps both copies when a published move was interrupted`() = runTest {
        val from = temporaryFolder.newFolder("from")
        val to = temporaryFolder.newFolder("to")
        val source = File(from, "game.sfc").apply { writeText("original") }
        val destination = File(to, "game.sfc").apply { writeText("original") }
        val journal = InMemoryFileOperationJournal()
        journal.begin(
            FileOperationRecord(
                id = "move",
                kind = FileOperationKind.MOVE,
                sources = listOf(source.absolutePath),
                destination = destination.absolutePath,
                staging = File(to, ".loki-part-already-published").absolutePath,
                removeSourcesAfterPublish = true,
                phase = FileOperationPhase.PUBLISHED,
            ),
        )
        val repository = repository(journal)

        val report = repository.recoverInterruptedOperations()

        assertThat(report.publishedKept).isEqualTo(1)
        assertThat(report.moveSourcesRetained).isEqualTo(1)
        assertThat(source.readText()).isEqualTo("original")
        assertThat(destination.readText()).isEqualTo("original")
        assertThat(journal.pending()).isEmpty()
    }

    @Test
    fun `an offline share stays journaled for a later recovery`() = runTest {
        val journal = InMemoryFileOperationJournal()
        val staging = "smb://nas/share/.loki-part-interrupted"
        journal.begin(
            FileOperationRecord(
                id = "offline",
                kind = FileOperationKind.COPY,
                sources = listOf(temporaryFolder.newFile("game.sfc").absolutePath),
                destination = "smb://nas/share/game.sfc",
                staging = staging,
            ),
        )
        val remote = mockk<SmbFileSource>()
        every { remote.handles(any()) } answers {
            firstArg<String>().startsWith("smb://")
        }
        every { remote.parentOf(staging) } returns "smb://nas/share/"
        coEvery { remote.list("smb://nas/share/") } returns FileListing.Offline("Server is asleep")
        val repository = FileRepository(
            local = LocalFileSource(),
            remote = remote,
            discovery = mockk(relaxed = true),
            ioDispatcher = Dispatchers.Unconfined,
            operationJournal = journal,
        )

        val report = repository.recoverInterruptedOperations()

        assertThat(report.unresolved).isEqualTo(1)
        assertThat(journal.pending()).hasSize(1)
    }

    private fun repository(journal: FileOperationJournal) = FileRepository(
        local = LocalFileSource(),
        remote = SmbFileSource { emptyList() },
        discovery = mockk(relaxed = true),
        ioDispatcher = Dispatchers.Unconfined,
        operationJournal = journal,
    )
}
