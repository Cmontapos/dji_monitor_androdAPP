# Validación MicroGas 0.4.0 — 2026-09-29

Las tres variantes declaran versionName 0.4.0 y versionCode 4.

## Cambios

Demo genera coordenadas sintéticas y altura periódica desde cero (0–60 m). DJI incorpora la altura cruda de KeyAircraftLocation3D, sin offset ni conversión vertical. El campo opcional Bluetooth `aircraft_position.altitude_m` se guarda en `altura_m`, al final de ambos CSV. Receptor incorpora Ruta y altura con proyección local, zoom, paneo, recentrado, selección y perfil temporal.

## Comprobaciones

- Receptor: 31 pruebas aprobadas; lint sin errores, 4 advertencias.
- Demo y DJI: 47 pruebas aprobadas por variante; lint sin errores, 11 advertencias en Demo y 10 en DJI.
- Compilación de los tres APK Debug y verificación de firmas con apksigner.
- Contrato compartido route.ndjson: simulador → Bluetooth → parser receptor → CSV y respaldo persistente.
- Altura cero, negativa, ausente y no finita; conservación en filas retenidas, frescura GPS y proyección local con huecos.

Comandos Gradle desde cada proyecto:

```sh
# app_receptor
./gradlew --offline :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
# app_dji
./gradlew --offline :app:testDemoDebugUnitTest :app:testDjiDebugUnitTest :app:assembleDemoDebug :app:assembleDjiDebug :app:lintDemoDebug :app:lintDjiDebug
```

La ejecución usó `--no-daemon --max-workers=2 -Dorg.gradle.jvmargs='-Xmx1024m -Dfile.encoding=UTF-8' -Pkotlin.compiler.execution.strategy=in-process` para limitar recursos.

## Pendiente en hardware

`adb devices` no mostró equipos conectados. No se instalaron los APK ni se verificaron gestos, transmisión Bluetooth física o altura real del dron. Probar Simulado y Receptor en dos dispositivos y contrastar la lectura DJI con Pilot 2. La referencia vertical sigue pendiente; no se declara altura sobre terreno ni nivel del mar.

## SHA-256 de los instaladores

- `MicroGas-DJI-debug.apk`: `ef8e146c1d8107b240d9e5c1f2760dba2cb5fe0ca6925be08a4e8bf4b3860978`
- `MicroGas-Receptor-debug.apk`: `5c887c3e2a8c163819bd32bbe91b311261b8f18e81afa834a5bfa8302e4e9215`
- `MicroGas-Simulado-debug.apk`: `cfc8bb0897dc2343346f0b555890663cfd6ae60bfbba2509b3a0d5e10122263e`
