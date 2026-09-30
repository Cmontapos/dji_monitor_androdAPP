package com.gaslab.microgas

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CsvJournalTest {
    private fun row(time: Long, co2: Float) = CsvReading(time, GasSample(time, co2), 1, true)

    @get:Rule val temp = TemporaryFolder()

    @Test fun savesEverySampleAndSurvivesReopeningWithLocaleIndependentDecimals() {
        val folder = temp.newFolder()
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val journal = CsvJournal(folder)
            var name = ""
            repeat(12) { name = journal.append(row(it * 1000L, 650.5f)) }
            val reopened = CsvJournal(folder)
            assertEquals(listOf(name), reopened.sessions())
            val output = ByteArrayOutputStream()
            reopened.snapshot(name).copyTo(output)
            val lines = output.toString("UTF-8").trimEnd().lines()
            assertEquals(13, lines.size)
            assertEquals("1970-01-01T00:00:00Z,650.5,1,1970-01-01T00:00:00Z,1,,,simulado,,,,,,,,,,,,,,,,", lines[1])
            val next = reopened.append(row(13000, 700f))
            assertNotEquals(name, next)
            assertEquals(2, reopened.sessions().size)
        } finally { Locale.setDefault(original) }
    }

    @Test fun exportSnapshotExcludesLaterSamplesAndKeepsOriginal() {
        val folder = temp.newFolder()
        val journal = CsvJournal(folder)
        val name = journal.append(row(0, 10f))
        val snapshot = journal.snapshot(name)
        journal.append(row(1000, 20f))
        val output = ByteArrayOutputStream()
        snapshot.copyTo(output)
        assertEquals(2, output.toString("UTF-8").trimEnd().lines().size)
        assertEquals(3, File(folder, name).readLines().size)
    }

    @Test fun incompleteTailFromInterruptedWriteIsExcluded() {
        val folder = temp.newFolder()
        val journal = CsvJournal(folder)
        val name = journal.append(row(0, 10f))
        File(folder, name).appendText("incomplete")
        val output = ByteArrayOutputStream()
        CsvJournal(folder).snapshot(name).copyTo(output)
        assertFalse(output.toString("UTF-8").contains("incomplete"))
        assertTrue(output.toString("UTF-8").endsWith("\n"))
    }

    @Test fun failingDestinationDoesNotChangeSource() {
        val folder = temp.newFolder()
        val journal = CsvJournal(folder)
        val name = journal.append(row(0, 10f))
        val before = File(folder, name).readBytes()
        try {
            journal.snapshot(name).copyTo(object : OutputStream() {
                override fun write(value: Int) { throw IOException("USB desconectado") }
            })
            fail("Expected export failure")
        } catch (_: IOException) { }
        assertArrayEquals(before, File(folder, name).readBytes())
    }

    @Test fun unavailableStorageReportsError() {
        val journal = CsvJournal(temp.newFile())
        try {
            journal.append(row(0, 10f))
            fail("Expected recording failure")
        } catch (_: IOException) { }
    }
    @Test fun activeSessionCannotBeDeletedAndRecordingContinues() {
        val folder = temp.newFolder()
        val journal = CsvJournal(folder)
        val name = journal.append(row(0, 650f))
        val before = File(folder, name).readBytes()
        try {
            journal.deleteSession(name)
            fail("Expected active session protection")
        } catch (_: IOException) { }
        assertArrayEquals(before, File(folder, name).readBytes())
        assertEquals(name, journal.append(row(1000, 700f)))
        assertEquals(3, File(folder, name).readLines().size)
    }

    @Test fun finishedSessionCanBeDeletedWithoutTouchingExportOrNextSession() {
        val folder = temp.newFolder()
        val journal = CsvJournal(folder)
        val previous = journal.append(row(0, 650f))
        val exported = temp.newFile("exported.csv")
        exported.outputStream().use { journal.snapshot(previous).copyTo(it) }
        val copyBytes = exported.readBytes()
        journal.finishSession()
        assertNull(journal.activeSession())
        val current = journal.append(row(1000, 700f))
        assertNotEquals(previous, current)
        journal.deleteSession(previous)
        assertFalse(File(folder, previous).exists())
        assertEquals(listOf(current), journal.sessions())
        assertArrayEquals(copyBytes, exported.readBytes())
        assertEquals(current, journal.append(row(2000, 800f)))
    }

    @Test fun previousProcessSessionCanBeDeletedAndListBecomesEmpty() {
        val folder = temp.newFolder()
        val name = CsvJournal(folder).append(row(0, 650f))
        val reopened = CsvJournal(folder)
        reopened.deleteSession(name)
        assertTrue(reopened.sessions().isEmpty())
        try {
            reopened.deleteSession(name)
            fail("Expected missing session error")
        } catch (_: IOException) { }
    }

    @Test fun deletionRejectsTraversalAndNonCsvFiles() {
        val parent = temp.newFolder()
        val folder = File(parent, "sessions").apply { mkdir() }
        val outside = File(parent, "outside.csv").apply { writeText("keep") }
        val other = File(folder, "notes.txt").apply { writeText("keep") }
        val journal = CsvJournal(folder)
        for (name in listOf("../outside.csv", outside.absolutePath, "notes.txt")) {
            try {
                journal.deleteSession(name)
                fail("Expected invalid name rejection")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals("keep", outside.readText())
        assertEquals("keep", other.readText())
    }

    @Test fun deletionFailureForDirectoryLeavesContentsIntact() {
        val folder = temp.newFolder()
        val directory = File(folder, "not-a-file.csv").apply { mkdir() }
        val child = File(directory, "keep.txt").apply { writeText("keep") }
        try {
            CsvJournal(folder).deleteSession(directory.name)
            fail("Expected non-file rejection")
        } catch (_: IOException) { }
        assertEquals("keep", child.readText())
    }

    @Test fun aircraftPositionIsPairedWithSampleAndAbsentFixStaysBlank() {
        val journal = CsvJournal(temp.newFolder())
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val fix = AircraftPosition(9.123456, -84.987654, 1000, 234, 4)
            val hold = SampleHold()
            hold.accept(GasSample(1234, 700f, "dji", aircraftPosition = fix))
            val first = hold.reading(1300)!!
            val name = journal.append(first)
            hold.saved(first)
            journal.append(hold.reading(20000)!!)
            hold.accept(GasSample(21000, 705f, "dji"))
            journal.append(hold.reading(21000)!!)
            val out = ByteArrayOutputStream()
            journal.snapshot(name).copyTo(out)
            val rows = out.toString("UTF-8").trimEnd().lines().map { it.split(',') }
            val headers = rows.first()
            assertTrue(rows.all { it.size == headers.size })
            fun cell(row: Int, column: String) = rows[row][headers.indexOf(column)]
            assertEquals("9.123456", cell(1, "latitud"))
            assertEquals("-84.987654", cell(1, "longitud"))
            assertEquals("dji_aircraft_msdk", cell(1, "gps_origen"))
            assertEquals("1970-01-01T00:00:01Z", cell(1, "gps_recepcion_utc"))
            assertEquals("234", cell(1, "gps_edad_al_recibir_co2_ms"))
            assertEquals("4", cell(1, "gps_nivel_senal"))
            assertEquals("0", cell(2, "sen66_new_data"))
            assertEquals(cell(1, "latitud"), cell(2, "latitud"))
            assertEquals(cell(1, "gps_edad_al_recibir_co2_ms"), cell(2, "gps_edad_al_recibir_co2_ms"))
            for (column in listOf("latitud", "longitud", "gps_origen", "gps_recepcion_utc", "gps_edad_al_recibir_co2_ms", "gps_nivel_senal")) {
                assertEquals("", cell(3, column))
            }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun legacyCsvExportsWithoutRewritingHeaderOrRows() {
        val folder = temp.newFolder()
        val original = "fecha_hora_utc,co2_ppm\n1970-01-01T00:00:00Z,700\n"
        File(folder, "previous.csv").writeText(original)
        val out = ByteArrayOutputStream()
        CsvJournal(folder).snapshot("previous.csv").copyTo(out)
        assertEquals(original, out.toString("UTF-8"))
    }

}
