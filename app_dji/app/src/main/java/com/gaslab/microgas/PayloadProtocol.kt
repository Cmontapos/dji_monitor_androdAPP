package com.gaslab.microgas

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** MicroGas: legacy v1 (32 bytes) and nine-channel v2 (64 bytes), little endian. */
class PayloadProtocol {
    private var boot: Long? = null
    private var sequence: Long? = null
    private val retiredBoots = mutableSetOf<Long>()

    @Synchronized
    fun decode(bytes: ByteArray, receivedAtMs: Long): GasSample? {
        if (bytes.size !in listOf(32, 64) || !bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(77, 71, 65, 83))) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val version = bytes[4].toInt()
        if (!((version == 1 && bytes.size == 32) || (version == 2 && bytes.size == 64))) return null
        if (bytes[5].toInt() != 1 || buffer.getShort(6).toInt() != 0) return null
        val crcOffset = bytes.size - 4
        val crc = CRC32().apply { update(bytes, 0, crcOffset) }.value
        if (crc != (buffer.getInt(crcOffset).toLong() and 0xffffffffL)) return null
        fun optional(offset: Int, valid: (Float) -> Boolean = { it >= 0 }): Float? =
            if (version == 1) null else buffer.getFloat(offset).takeIf { it.isFinite() && valid(it) }
        val nextBoot = buffer.getInt(8).toLong() and 0xffffffffL
        val nextSequence = buffer.getInt(12).toLong() and 0xffffffffL
        val uptime = buffer.getLong(16)
        val co2 = buffer.getFloat(24)
        if (uptime < 0 || !co2.isFinite() || co2 < 0) return null
        if (boot == nextBoot) {
            val delta = (nextSequence - sequence!!) and 0xffffffffL
            if (delta == 0L || delta >= 0x80000000L) return null
        } else {
            if (nextBoot in retiredBoots) return null
            boot?.let { retiredBoots.add(it) }
        }
        boot = nextBoot
        sequence = nextSequence
        return GasSample(receivedAtMs, co2, "dji", nextBoot, nextSequence, uptime,
            temperatureC = optional(28) { true }, humidityPct = optional(32) { it in 0f..100f },
            pm1 = optional(36), pm2_5 = optional(40), pm4 = optional(44), pm10 = optional(48),
            vocIndex = optional(52) { it in 1f..500f }, noxIndex = optional(56) { it in 1f..500f },
        )
    }
}
