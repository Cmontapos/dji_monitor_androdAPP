package com.gaslab.microgas

/** One storage tick, distinct from the arrival of a sensor measurement. */
data class CsvReading(
    val recordedAtMs: Long,
    val sample: GasSample,
    val sequence: Long,
    val newData: Boolean,
)

/** The source must emit only new, validated measurements, even if values are equal. */
class SampleHold {
    private var latest: GasSample? = null
    private var sequence = 0L
    private var savedSequence = 0L

    @Synchronized
    fun accept(sample: GasSample): Boolean {
        if (!sample.co2Ppm.isFinite() || sample.co2Ppm < 0f) return false
        latest = sample
        sequence++
        return true
    }

    @Synchronized
    fun reading(recordedAtMs: Long): CsvReading? = latest?.let {
        CsvReading(recordedAtMs, it, sequence, sequence > savedSequence)
    }

    /** A failed disk write must not consume the new-data marker. */
    @Synchronized
    fun saved(reading: CsvReading) {
        savedSequence = maxOf(savedSequence, reading.sequence)
    }
}
