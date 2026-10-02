# Receptor 0.4.3 — 2026-09-30

Receptor: versionName 0.4.3, versionCode 7. DJI y Simulado: 0.4.1 (5).

Home persistente durante la sesión de recepción, máximos globales por cada una de las nueve variables (con empates estables y conservación fuera del buffer), ejes geográficos y administración separada de CSV con confirmación y protección del archivo activo.

Validación realizada durante la implementación:

- `make test`: 36 pruebas en Receptor y 47 en cada variante del emisor; 0 fallos.
- `make lint`: 0 errores; 4 advertencias en Receptor, 11 en Demo y 10 en DJI.
- `make apks`: las tres variantes compiladas y copiadas a `apks/`.
- `apksigner verify`: las tres firmas verificadas. `aapt dump badging`: Receptor 0.4.3 (7).
- Se usó `GRADLE_FLAGS='--offline --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx1024m -Dfile.encoding=UTF-8" -Pkotlin.compiler.execution.strategy=in-process'`.

Las cinco pruebas nuevas cubren conservación de máximos y Home tras expulsión del buffer, valores inválidos, empates, duplicados, cambios de sesión del emisor, reinicio de la sesión receptora, anclaje de la proyección e inversa geográfica, incluido cruce del antimeridiano y polo. Las pruebas existentes cubren la protección del CSV activo. No se ejecutaron pruebas visuales de Compose ni Bluetooth físico: ADB mostró un dispositivo `unauthorized`.

SHA-256 de `apks/MicroGas-Receptor-debug.apk`: `ef973054fe377acb29205557b6c131cb3dc7dc4613f2b2e81c6030be77dd0a5c`.
