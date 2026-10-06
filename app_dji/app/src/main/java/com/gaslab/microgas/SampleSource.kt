package com.gaslab.microgas

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.sin
import kotlin.math.cos

/** Emite exclusivamente mediciones nuevas y válidas, no retransmisiones ni valores retenidos.
 * La futura integración debe validar el protocolo y la identidad de muestra antes de emitir.
 */
interface SampleSource {
    val status: StateFlow<String>
    fun samples(): Flow<GasSample>
}

class SimulatedSampleSource : SampleSource {
    override val status = MutableStateFlow("Simulación · sensores, ruta y altura base cero a ~1 Hz")
    override fun samples(): Flow<GasSample> = flow {
        var step = 0
        while (true) {
            emit(simulatedMeasurement(step, System.currentTimeMillis()))
            step++
            delay(1_000)
        }
    }
}

private const val SIM_CENTER_LAT = 10.197183
private const val SIM_CENTER_LON = -84.232373
private const val SIM_RADIUS_DEG = 0.0009 // ≈100 m orbit around the centre

/** Deterministic simulator shared by demo and integration fixtures. Synthetic route and zero-based altitude; never device GPS. */
fun simulatedMeasurement(step: Int, receivedAtMs: Long): GasSample {
    val wave = sin(step / 8.0).toFloat()
    return GasSample(receivedAtMs, 650f + 160f * wave,
        aircraftPosition = AircraftPosition(
            latitude = SIM_CENTER_LAT + SIM_RADIUS_DEG * sin(step / 60.0),
            longitude = SIM_CENTER_LON + SIM_RADIUS_DEG / cos(Math.toRadians(SIM_CENTER_LAT)) * cos(step / 60.0),
            receivedAtMs = receivedAtMs, ageAtSampleMs = 0, signalLevel = 4,
            altitudeM = 30.0 * (1 - cos(step / 30.0)), source = "simulado",
        ),
        temperatureC = 24f + 2f * wave, humidityPct = 60f + 10f * wave,
        pm1 = 5f + wave, pm2_5 = 8f + 2f * wave, pm4 = 10f + 3f * wave,
        pm10 = 12f + 4f * wave, vocIndex = 100f + 30f * wave, noxIndex = 20f + 10f * wave,
    )
}
