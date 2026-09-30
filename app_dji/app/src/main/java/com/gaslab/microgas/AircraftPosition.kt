package com.gaslab.microgas

/** Aircraft telemetry received by MSDK or explicitly tagged synthetic demo coordinates.
 * Age is frozen when CO2 arrives, not recomputed on CSV hold rows.
 */
data class AircraftPosition(
    val latitude: Double,
    val longitude: Double,
    val receivedAtMs: Long,
    val ageAtSampleMs: Long,
    val signalLevel: Int,
    val altitudeM: Double? = null,
    val source: String = SOURCE,
) {
    init {
        require(validCoordinates(latitude, longitude))
        require(receivedAtMs >= 0 && ageAtSampleMs in 0..MAX_AGE_MS)
        require(usableSignal(signalLevel))
        require(altitudeM == null || altitudeM.isFinite())
        require(source == SOURCE || source == "simulado")
    }
    companion object {
        const val SOURCE = "dji_aircraft_msdk"
        const val MAX_AGE_MS = 5000L
        fun usableSignal(level: Int?) = level in 3..5 || level == 10
        fun validCoordinates(latitude: Double?, longitude: Double?) =
            latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite() &&
                latitude in -90.0..90.0 && longitude in -180.0..180.0
    }
}

/** Connection generations reject callbacks from a disconnected/replaced aircraft. */
class AircraftPositionTracker {
    private var generation = 0L
    private var connected = false
    private var latest: AircraftPosition? = null
    private var receivedElapsedMs = 0L

    @Synchronized
    fun connectionChanged(isConnected: Boolean) {
        generation++
        connected = isConnected
        latest = null
    }

    @Synchronized
    fun connectionToken(): Long? = if (connected) generation else null

    @Synchronized
    fun invalidate(token: Long) {
        if (connected && token == generation) latest = null
    }

    @Synchronized
    fun update(
        token: Long, latitude: Double?, longitude: Double?, signalLevel: Int?,
        receivedAtMs: Long, elapsedMs: Long, altitudeM: Double? = null,
    ): Boolean {
        if (!connected || token != generation) return false
        latest = null
        if (!AircraftPosition.validCoordinates(latitude, longitude) ||
            !AircraftPosition.usableSignal(signalLevel) || receivedAtMs < 0 || elapsedMs < 0) return false
        latest = AircraftPosition(latitude!!, longitude!!, receivedAtMs, 0, signalLevel!!, altitudeM?.takeIf { it.isFinite() })
        receivedElapsedMs = elapsedMs
        return true
    }

    @Synchronized
    fun snapshot(nowElapsedMs: Long): AircraftPosition? {
        if (!connected) return null
        val position = latest ?: return null
        val age = nowElapsedMs - receivedElapsedMs
        return if (age in 0..AircraftPosition.MAX_AGE_MS) position.copy(ageAtSampleMs = age) else null
    }
}
