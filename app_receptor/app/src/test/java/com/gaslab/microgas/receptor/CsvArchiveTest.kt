package com.gaslab.microgas.receptor

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class CsvArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun sample(i: Long) = Measurement(1790000000123, 3010.5f, origin = "dji", session = "a", sequence = i)
    @Test fun persistsAcrossReopeningAndKeepsAllRows() {
        val directory = temporary.newFolder()
        val first = CsvArchive(directory)
        repeat(20) { first.append("session.csv", sample(it.toLong())) }
        val reopened = CsvArchive(directory)
        assertEquals(listOf("session.csv"), reopened.sessions())
        val output = ByteArrayOutputStream()
        reopened.copy("session.csv", output)
        assertEquals(21, output.toString("UTF-8").trimEnd().lines().size)
        assertEquals(1, output.toString("UTF-8").lines().count { it.startsWith("fecha_hora_utc") })
    }
    @Test fun truncatedTailExcludedFromExportAndRepairedBeforeAppend() {
        val directory = temporary.newFolder(); val archive = CsvArchive(directory)
        archive.append("a.csv", sample(1))
        File(directory, "a.csv").appendText("broken tail")
        val output = ByteArrayOutputStream(); archive.copy("a.csv", output)
        assertFalse(output.toString().contains("broken"))
        archive.append("a.csv", sample(2))
        assertFalse(File(directory, "a.csv").readText().contains("broken"))
        assertEquals(3, File(directory, "a.csv").readLines().size)
    }
    @Test fun protectsActiveAndRejectsTraversal() {
        val archive = CsvArchive(temporary.newFolder()); archive.append("a.csv", sample(1))
        assertThrows(IllegalArgumentException::class.java) { archive.delete("a.csv", "a.csv") }
        assertThrows(IllegalArgumentException::class.java) { archive.delete("../a.csv", null) }
        assertThrows(IllegalArgumentException::class.java) { archive.copy("../a.csv", ByteArrayOutputStream()) }
        archive.delete("a.csv", null)
        assertTrue(archive.sessions().isEmpty())
    }
    @Test fun exportExcludesSamplesAppendedAfterItsBoundary() {
        val directory = temporary.newFolder(); val archive = CsvArchive(directory)
        archive.append("a.csv", sample(1))
        val bytes = ByteArrayOutputStream()
        val output = object : java.io.OutputStream() {
            var appended = false
            override fun write(b: Int) {
                if (!appended) { appended = true; archive.append("a.csv", sample(2)) }
                bytes.write(b)
            }
        }
        archive.copy("a.csv", output)
        assertEquals(2, bytes.toString("UTF-8").trimEnd().lines().size)
        assertEquals(3, File(directory, "a.csv").readLines().size)
    }
    @Test fun failedExportRetainsOriginal() {
        val directory = temporary.newFolder(); val archive = CsvArchive(directory)
        archive.append("a.csv", sample(1)); val before = File(directory, "a.csv").readBytes()
        val failed = object : java.io.OutputStream() { override fun write(b: Int) { throw java.io.IOException("removed") } }
        assertThrows(java.io.IOException::class.java) { archive.copy("a.csv", failed) }
        assertArrayEquals(before, File(directory, "a.csv").readBytes())
    }
    @Test fun writeFailureIsReportedAndNextWriteCanRecover() {
        val directory = File(temporary.newFolder(), "sessions"); directory.writeText("blocked")
        val archive = CsvArchive(directory)
        assertThrows(java.io.IOException::class.java) { archive.append("a.csv", sample(1)) }
        directory.delete(); directory.mkdir()
        archive.append("a.csv", sample(2))
        assertEquals(2, File(directory, "a.csv").readLines().size)
    }
}
