package com.gaslab.microgas.receptor

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.io.StringWriter
import java.time.Instant
import java.util.UUID

/** Disk operations run on IO. Export captures a complete boundary without blocking later appends. */
class CsvArchive(private val directory: File) {
    private fun file(name: String): File {
        require(File(name).name == name && name.endsWith(".csv")) { "Nombre de archivo inválido" }
        return File(directory, name).also {
            require(it.canonicalFile.parentFile == directory.canonicalFile) { "Archivo fuera del directorio" }
        }
    }
    @Synchronized fun append(name: String, sample: Measurement) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("No se pudo crear el respaldo")
        val text = StringWriter().also { MeasurementRepository.exportCsv(listOf(sample), it) }.toString()
        RandomAccessFile(file(name), "rw").use { output ->
            val previous = completeLength(output)
            output.setLength(previous)
            try {
                output.seek(previous)
                output.write((if (previous == 0L) text else text.substringAfter('\n')).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            } catch (error: IOException) {
                try { output.setLength(previous); output.fd.sync() } catch (_: IOException) { }
                throw error
            }
        }
    }
    @Synchronized fun sessions(): List<String> = directory.listFiles()?.filter {
        it.isFile && it.name.endsWith(".csv") && it.length() > 0
    }?.map { it.name }?.sortedDescending() ?: emptyList()

    @Synchronized fun delete(name: String, activeName: String?) {
        require(name != activeName) { "Desconecta antes de borrar la sesión activa" }
        val target = file(name)
        if (!target.isFile || !target.delete()) throw IOException("No se pudo borrar el archivo")
    }
    fun copy(name: String, output: OutputStream) {
        val (source, length) = synchronized(this) {
            val source = file(name)
            source to RandomAccessFile(source, "r").use { completeLength(it) }
        }
        RandomAccessFile(source, "r").use { input ->
            var remaining = length
            val bytes = ByteArray(8192)
            while (remaining > 0) {
                val count = input.read(bytes, 0, minOf(bytes.size.toLong(), remaining).toInt())
                if (count < 0) throw IOException("Respaldo incompleto")
                output.write(bytes, 0, count)
                remaining -= count
            }
            output.flush()
        }
    }
    private fun completeLength(input: RandomAccessFile): Long {
        var end = input.length()
        while (end > 0) {
            input.seek(end - 1)
            if (input.read() == 10) break
            end--
        }
        return end
    }
    companion object {
        fun newName(): String = "MicroGas_receptor_${Instant.now().toString().replace(':', '-')}_${UUID.randomUUID().toString().take(8)}.csv"
    }
}
