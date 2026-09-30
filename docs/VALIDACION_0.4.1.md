# Validación 0.4.1 — 2026-09-29

Tres APK Debug: versionName 0.4.1, versionCode 5. Firmas verificadas con apksigner.

Cambios: intercambio de ubicación y estilo de Ocultar app/Detener en DJI y Demo; enlace desde coordenadas del detalle de las gráficas a Ruta y altura; selección bidireccional mapa/perfil por sesión y secuencia, con halo y recuperación del encuadre cuando la muestra queda fuera por zoom/paneo.

Validación Gradle offline: testDebugUnitTest, assembleDebug y lintDebug en Receptor; testDemoDebugUnitTest, testDjiDebugUnitTest, assembleDemoDebug, assembleDjiDebug, lintDemoDebug y lintDjiDebug en emisor. Se limitaron recursos a dos workers, heap de 1024 MB y compilador Kotlin en proceso.

- Receptor: 31 pruebas aprobadas, lint 0 errores / 4 advertencias.
- Demo: 47 pruebas aprobadas, lint 0 errores / 11 advertencias.
- DJI: 47 pruebas aprobadas, lint 0 errores / 10 advertencias.

No había dispositivos conectados en adb. Las pruebas existentes no verifican gestos de Compose: queda pendiente la prueba visual descrita en el manual (coordenadas → mapa, mapa ↔ altura, zoom, ausencia de GPS/altura y botones).

## SHA-256

- `MicroGas-DJI-debug.apk`: `6be4500a52081f8a55ee93eeab23e6833afed484b160c5fffbb5c1dcc8d66c17`
- `MicroGas-Receptor-debug.apk`: `624e7161b6978be43920bab0c001dd4e6329cf463de263ff0818432af7d646f5`
- `MicroGas-Simulado-debug.apk`: `73b06398a606738ccee2b4a0133812aa3db0924218e944b414802e58d3829bc4`
