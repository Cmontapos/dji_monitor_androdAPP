package com.gaslab.microgas

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Append-only session journal. All disk operations must run on an IO dispatcher. */
class CsvJournal(private val directory: File, private val origin: String = "simulado") {
    private var active: File? = null

    @Synchronized
    fun append(reading: CsvReading): String {
        val sample = reading.sample
        val gps = sample.aircraftPosition
        require(sample.co2Ppm.isFinite() && sample.co2Ppm >= 0f)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("No se pudo crear el registro")
        val file = active ?: File(directory, newName()).also { active = it }
        RandomAccessFile(file, "rw").use { output ->
            val previousLength = output.length()
            try {
                output.seek(previousLength)
                if (previousLength == 0L) output.write(HEADER.toByteArray(Charsets.UTF_8))
                val row = "${Instant.ofEpochMilli(reading.recordedAtMs)},${sample.co2Ppm}," +
                    "${if (reading.newData) 1 else 0},${Instant.ofEpochMilli(sample.receivedAtMs)}," +
                    "${reading.sequence},${gps?.latitude ?: ""},${gps?.longitude ?: ""},${sample.origin},${sample.senderBoot ?: ""}," +
                    "${sample.senderSequence ?: ""},${sample.acquiredUptimeMs ?: ""}," +
                    "${gps?.source ?: ""},${gps?.let { Instant.ofEpochMilli(it.receivedAtMs) } ?: ""}," +
                    "${gps?.ageAtSampleMs ?: ""},${gps?.signalLevel ?: ""},${sample.temperatureC ?: ""},${sample.humidityPct ?: ""},${sample.pm1 ?: ""},${sample.pm2_5 ?: ""},${sample.pm4 ?: ""},${sample.pm10 ?: ""},${sample.vocIndex ?: ""},${sample.noxIndex ?: ""},${gps?.altitudeM ?: ""}\n"
                output.write(row.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            } catch (error: IOException) {
                // A failed append must not leave a partial row before the next sample.
                try { output.setLength(previousLength); output.fd.sync() } catch (_: IOException) { active = null }
                throw error
            }
        }
        return file.name
    }

    @Synchronized
    fun sessions(): List<String> = directory.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".csv") && it.length() > 0 }
        ?.map { it.name }?.sortedDescending() ?: emptyList()

    @Synchronized
    fun activeSession(): String? = active?.name

    /** Called after the storage loop exits. A subsequent start opens a new session. */
    @Synchronized
    fun finishSession() { active = null }

    @Synchronized
    fun deleteSession(name: String) {
        require(File(name).name == name && name.endsWith(".csv")) { "Nombre de sesión inválido" }
        val file = File(directory, name)
        require(file.canonicalFile.parentFile == directory.canonicalFile) { "Sesión fuera del directorio" }
        if (active?.name == name) throw IOException("Detén la adquisición antes de borrar esta sesión")
        if (!file.isFile) throw IOException("La sesión ya no existe")
        if (!file.delete()) throw IOException("No se pudo borrar la sesión")
    }

    /** Captures a complete-row boundary; later appends are excluded from this export. */
    @Synchronized
    fun snapshot(name: String): CsvSnapshot {
        require(File(name).name == name && name.endsWith(".csv"))
        val file = File(directory, name)
        if (!file.isFile) throw IOException("La sesión ya no existe")
        // A process/power failure can leave an incomplete tail; never export that tail.
        val length = RandomAccessFile(file, "r").use { input ->
            var end = input.length()
            while (end > 0) {
                input.seek(end - 1)
                if (input.read() == '\n'.code) break
                end--
            }
            end
        }
        return CsvSnapshot(file, length)
    }

    private fun newName(): String {
        val time = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS").withZone(ZoneOffset.UTC).format(Instant.now())
        return "MicroGas_${origin}_${time}_${UUID.randomUUID().toString().take(8)}.csv"
    }

    companion object {
        const val HEADER = "fecha_hora_utc,co2_ppm,sen66_new_data,sen66_muestra_utc,sen66_secuencia,latitud,longitud,origen,payload_boot,payload_secuencia,payload_uptime_ms,gps_origen,gps_recepcion_utc,gps_edad_al_recibir_co2_ms,gps_nivel_senal,temperatura_c,humedad_pct,pm1_0,pm2_5,pm4_0,pm10,voc_index,nox_index,altura_m\n"
    }
}

class CsvSnapshot internal constructor(private val file: File, val length: Long) {
    fun copyTo(output: OutputStream) {
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var remaining = length
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count < 0) throw IOException("El archivo de origen está incompleto")
                output.write(buffer, 0, count)
                remaining -= count
            }
        }
        output.flush()
    }
}
