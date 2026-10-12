package app.ondesk
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.file.Files

class FileSpoolTest {
    @Test fun copiesBytesBeforeSourceDisappearsAndRecordsHash() {
        val directory = Files.createTempDirectory("ondesk-test").toFile()
        try {
            val result = FileSpool(directory).copy("abc".byteInputStream(), "hello.txt", "text/plain")
            assertEquals(3L, result.size)
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", result.sha256)
            assertEquals("abc", File(result.path).readText())
            assertEquals(1, directory.listFiles()!!.size)
        } finally { directory.deleteRecursively() }
    }
    @Test fun brokenSourceLeavesNoPartialFile() {
        val directory = Files.createTempDirectory("ondesk-test").toFile()
        val source = object : InputStream() {
            var count = 0
            override fun read(): Int { if (++count < 5) return 42; throw java.io.IOException("Source disappeared") }
        }
        try {
            assertThrows(java.io.IOException::class.java) { FileSpool(directory).copy(source, "hello.txt", "text/plain") }
            assertEquals(0, directory.listFiles()!!.size)
        } finally { directory.deleteRecursively() }
    }
    @Test fun removesInterruptedCopiesButPreservesCommittedQueueAndUnknownFiles() {
        val directory = Files.createTempDirectory("ondesk-test").toFile()
        try {
            val spool = FileSpool(directory)
            val kept = spool.copy("abc".byteInputStream(), "kept.txt", "text/plain")
            val orphan = spool.copy("orphan".byteInputStream(), "lost.txt", "text/plain")
            val partial = File(directory, "${java.util.UUID.randomUUID()}.part").apply { writeText("partial") }
            val unknown = File(directory, "unknown.txt").apply { writeText("preserve") }
            spool.reconcile(setOf(kept.path))
            assertTrue(File(kept.path).exists())
            assertFalse(File(orphan.path).exists())
            assertFalse(partial.exists())
            assertTrue(unknown.exists())
        } finally { directory.deleteRecursively() }
    }
    @Test fun rejectsTraversalNames() {
        val directory = Files.createTempDirectory("ondesk-test").toFile()
        try {
            for (name in listOf("../x", "/tmp/x", "a\\b", "..", "", "bad\u0000name")) {
                assertThrows(IllegalArgumentException::class.java) { FileSpool(directory).copy("abc".byteInputStream(), name, "text/plain") }
            }
        } finally { directory.deleteRecursively() }
    }
}
