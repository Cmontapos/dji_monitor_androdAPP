# Contexto de MicroGas

Actualizado: 2 de octubre de 2026.

## Objetivo

MicroGas es una aplicación Android para visualizar y guardar las mediciones de
gases de una Raspberry Pi 5 instalada en un DJI Mavic 3 Thermal (Mavic 3T).
El control es un DJI RC Pro Enterprise. Se busca consultar las mediciones sin
interferir con Pilot 2, utilizado para cámara, telemetría y funciones de vuelo.

## Versión actual

- **Versión:** 0.3.0 (versionCode 3)
- **Kotlin:** 2.0.21 con Jetpack Compose (BOM 2024.12.01, Material 3)
- **DJI MSDK V5 Aircraft:** 5.18.0 (solo flavor `dji`)
- **compileSdk:** 35 — **minSdk:** 29 (Android 10) — **targetSdk:** 34
- **JVM:** Java 17 — **ABI:** arm64-v8a

## Montaje actual

```text
SEN66 → I2C → Raspberry Pi 5 con PSDK
                         │ UART
                         ▼
                     E-Port V1
                         │
                     Mavic 3T
                         │ Enlace inalámbrico DJI
                         ▼
                  RC Pro Enterprise
                         │
                      MicroGas
```

El usuario ya comprobó que los datos llegan desde el Pi al control y se muestran
en Pilot 2. Se conserva ese montaje; sustituir la Pi por STM32 queda aplazado.
El SEN66 mide CO₂, no SO₂. El GPS del dron se consulta desde MicroGas DJI por
MSDK; falta probarlo en el equipo.

## Arquitectura

La app sigue MVVM con flujo unidireccional de datos:

```text
SampleSource (Flow<GasSample>)
        │
        ▼
  MonitorEngine (singleton)
        │
   ┌────┼──────────────────┐
   ▼    ▼                  ▼
SampleHold   MonitorState   onSample callback
 (ZOH)       (UI buffer)    (servicio: alarma, BT, pop-up)
   │              │
   ▼              ▼
CsvJournal   MonitorViewModel → Compose UI
(~10 Hz)
```

- **MonitorEngine** es un singleton por `Application`. Al llamar `start()` lanza
  dos corrutinas: `acquisition` (recolecta del `SampleSource`) y `storage`
  (cada ~100 ms lee `SampleHold.reading()` y escribe a `CsvJournal` con
  `fd.sync()`). Al llamar `stop()` cancela ambas y reinicia estados.
- **MonitorService** es un servicio en primer plano
  (`foregroundServiceType="connectedDevice"`) que contiene la alarma de audio
  (`ToneGenerator`), el servidor Bluetooth (`BluetoothRelay`) y la ventana
  flotante (`FloatingMonitor`). Su bucle principal corre cada 500 ms.
- **MonitorViewModel** expone flujos reactivos a Compose.

## Aplicación

Desarrollo Android con Kotlin y Jetpack Compose. Dos variantes (product flavors):

- **MicroGas Simulado (`demo`):** `com.gaslab.microgas.demo`. Genera CO₂
  sinusoidal (650 ± 160 ppm, ~1 Hz) sin permisos especiales ni SDK DJI.
  `SourceFactory` devuelve `SimulatedSampleSource`.
- **MicroGas DJI (`dji`):** `com.gaslab.microgas`. Inicializa MSDK V5 desde
  `MicroGasDjiApplication` (instala dependencias nativas en `attachBaseContext`).
  `SourceFactory` devuelve `DjiSampleSource`. Requiere App Key MSDK configurada
  en `local.properties` (`dji.msdk.apiKey`) o variable de entorno
  `DJI_MSDK_API_KEY`. Las credenciales PSDK del Pi no sustituyen esta clave.
  `SourceFactory` también solicita permisos de ubicación y almacenamiento.

La interfaz muestra CO₂ actual (28 sp, barra de 6 dp relativa al máximo),
reinicio de máximo y pausa de vista. La tabla permite seleccionar variable,
cantidad de lecturas (1–3 600) y retroceso X. Por ahora solo hay CO₂; muestra
10 lecturas por defecto y conserva hasta 3 600 lecturas nuevas en memoria
(~1 hora a 1 Hz).

## Protocolo de payload (MicroGas v1)

El Pi envía paquetes de 32 bytes por PSDK a `DJI_CHANNEL_ADDRESS_MASTER_RC_APP`.
MicroGas los recibe con `PayloadDataListener` en `PayloadIndexType.UP`.

| Offset | Tamaño | Campo |
|--------|--------|-------|
| 0–3 | 4 B | Magic `MGAS` |
| 4–5 | 2 B | Versión 0x01 0x01 |
| 6–7 | 2 B | Reservado (0x00 0x00) |
| 8–11 | 4 B | `boot` (uint32 LE) — identificador de arranque |
| 12–15 | 4 B | `sequence` (uint32 LE) — secuencia monotónica |
| 16–23 | 8 B | `uptime` (int64 LE) — tiempo monotónico de adquisición |
| 24–27 | 4 B | `co2` (IEEE-754 float LE) |
| 28–31 | 4 B | CRC32 estándar de bytes 0–27 (uint32 LE) |

`PayloadProtocol.decode()` valida longitud, magic, versión, CRC32 y orden de
secuencia. Dentro del mismo boot, la secuencia debe avanzar (maneja rollover
uint32). Un boot anterior se agrega a `retiredBoots` y se rechaza si reaparece.

El ejemplo emisor C está preparado, pero falta integrarlo al programa real del Pi.

## CSV y respaldo

El CO₂ se actualiza aproximadamente a 1 Hz. Un ciclo independiente guarda CSV
cada ~100 ms mediante `SampleHold` (zero-order hold), preparado para futuros
sensores más rápidos. Entre lecturas mantiene el último valor válido;
`sen66_new_data`, tiempos y secuencias distinguen valores retenidos de muestras
nuevas. `SampleHold` crea un nuevo buffer en cada `start()` para evitar fugas
de mediciones viejas.

El guardado es automático con `RandomAccessFile` y `fd.sync()` por cada fila.
Si ocurre un `IOException`, se restaura la longitud anterior del archivo
(`output.setLength(previousLength)` + `sync()`). Pausar la vista no pausa
el registro. Se crea un CSV por sesión en `files/sessions/`, almacenamiento
privado de la app. El nombre sigue el patrón
`MicroGas_${origen}_${yyyyMMdd_HHmmss_SSS}_${uuid8}.csv`.

«Archivos CSV → Exportar» usa Storage Access Framework (SAF) para copiar a una
llave USB reconocida por Android u otro destino, conservando el original. La
exportación usa `CsvSnapshot`, que escanea desde el final del archivo hasta el
último `\n` para excluir filas parciales causadas por cortes de energía.

«Archivos CSV → Borrar» elimina una sesión interna con confirmación y defensa
contra path traversal. La sesión activa queda protegida hasta detener la
adquisición; cada reinicio crea un CSV nuevo. Las copias exportadas se conservan.
No se permite borrar mientras se exporta. No hay todavía límite de disco ni
borrado automático. Desinstalar la app o borrar sus datos elimina los registros
privados. Una pérdida de conexión no borra las lecturas recibidas; para recuperar
muestras que nunca llegaron se necesitaría registro local en la Pi.

### Encabezado CSV histórico (15 columnas; anterior a SEN66 v2 y altura)

```csv
fecha_hora_utc,co2_ppm,sen66_new_data,sen66_muestra_utc,sen66_secuencia,latitud,longitud,origen,payload_boot,payload_secuencia,payload_uptime_ms,gps_origen,gps_recepcion_utc,gps_edad_al_recibir_co2_ms,gps_nivel_senal
```

El esquema actual tiene 24 columnas; consulta el manual principal. Este encabezado se conserva como referencia histórica.

Timestamps en ISO-8601 UTC con milisegundos. Decimales con punto independiente
del locale del sistema (se fuerza `Locale.US`). Latitud y longitud vacías si no
hay fix válido.

## GPS integrado por MSDK en el control

`DjiAircraftPosition` consulta `KeyAircraftLocation3D` y `KeyGPSSignalLevel`
cada 1 000 ms mediante `KeyManager.getInstance().getValue(key, callback)` (API
asíncrona de hardware, no caché). Ambas consultas se lanzan concurrentemente con
`async` y un timeout estricto de 2 000 ms.

`AircraftPositionTracker` es thread-safe (`@Synchronized`) y usa tokens de
generación: al cambiar la conexión del dron se incrementa el token y se borra la
posición; respuestas tardías de un token anterior no corrompen el estado activo.

Validación:
- Latitud ∈ [-90, 90], longitud ∈ [-180, 180], ambas finitas (0.0, 0.0 se
  acepta como coordenada matemáticamente válida).
- Señal GPS ∈ {3, 4, 5, 10}. Se acepta nivel 10 sin asumir RTK fijo. Niveles
  ≤ 2 o negativos se rechazan.
- Edad monotónica ∈ [0, 5 000] ms. Si supera 5 s o el dron se desconecta,
  `snapshot()` devuelve `null`.

La edad se congela al momento de recibir CO₂ y permanece fija en filas CSV
retenidas. No se usa el GPS del control ni el home point. El paquete de la Pi
no cambia.

Se agregan `gps_origen`, `gps_recepcion_utc`, `gps_edad_al_recibir_co2_ms` y
`gps_nivel_senal` al CSV, y `aircraft_position` al JSON de Bluetooth.

## Pop-up, alarma y Bluetooth

Implementado servicio en primer plano con notificación persistente
(`IMPORTANCE_LOW` para evitar sonido de notificación repetido). Se inicia
explícitamente desde «Iniciar pop-up» (intent `SHOW`) o «Segundo plano»
(intent `HIDE`) y se detiene desde la app o la notificación (intent `STOP`).
«Ocultar app» llama `moveTaskToBack(true)` para volver a Pilot 2 sin detener
adquisición. El pop-up incluye «Segundo plano» para quitar la ventana sin
detener el servicio. Se recupera abriendo la app desde la notificación y
pulsando «Iniciar pop-up».

La ventana flotante (`FloatingMonitor`) usa `TYPE_APPLICATION_OVERLAY` con
`FLAG_NOT_FOCUSABLE` y `WRAP_CONTENT` para que los toques fuera pasen a Pilot 2.
Es arrastrable con clamp a los bordes de pantalla. Modo colapsado muestra solo
CO₂ y dos indicadores compactos: `RX ✓`/`RX —` (recepción de sensor) y
`BT ↑`/`BT ✓`/`BT —` (transmisión Bluetooth). Colores: rojo en alarma, teal
si fresco, amarillo si obsoleto. Requiere permiso «Mostrar sobre otras apps».

Alarma configurable, predeterminada en 3 000 ppm inclusive: pitido de 220 ms cada
1,5 s mientras lleguen datos frescos (≤ 5 s) sobre el umbral. Se silencia si baja
o pasan más de cinco segundos sin una muestra nueva. Usa
`ToneGenerator(AudioManager.STREAM_ALARM, 80)` con el volumen de alarmas del
equipo. El umbral se guarda en `SharedPreferences("monitor")` (rango 1–100 000).
Pausar la vista no afecta alarma, CSV ni envío Bluetooth.

### Servidor Bluetooth clásico RFCOMM

- **Nombre SDP:** `MicroGas`
- **UUID:** `bb239920-bdbc-4d51-8a12-a5351875d891`
- **Formato:** NDJSON (una línea JSON UTF-8 terminada en `\n` por muestra nueva).
- **Emisión:** Solo muestras nuevas (~1 Hz), nunca filas retenidas del ZOH.
- **Canal conflado:** `Channel<Pair<Long, ByteArray>>(CONFLATED)` — solo la
  muestra más reciente en cola; clientes lentos no causan fugas de memoria.
- **Staleness:** Paquetes en cola > 5 s se descartan sin enviar.
- **Watchdog de escritura:** 3 s; si el socket bloquea, se cierra.
- **Detección de desconexión:** Lee `socket.inputStream.read()` concurrentemente
  para detectar desconexión inmediata del cliente.
- **Clientes:** Uno a la vez. Diferencia receptor ausente, conectado y escritura
  realizada; no confirma guardado remoto.

Esquema JSON v1 con extensión GPS:
```json
{
  "v": 1,
  "session": "uuid",
  "sequence": 15,
  "received_at_ms": 1790000000123,
  "co2_ppm": 3000.500,
  "origin": "dji",
  "sender_boot": 4,
  "sender_sequence": 99,
  "acquired_uptime_ms": 1000,
  "aircraft_position": {
    "latitude": 9.123456,
    "longitude": -84.987654,
    "source": "dji_aircraft_msdk",
    "received_at_ms": 1790000000000,
    "age_at_sample_ms": 123,
    "signal_level": 4
  }
}
```
En modo demo o sin GPS válido, `aircraft_position` y campos `sender_*` son `null`.

Protocolo y pruebas en `integration/BLUETOOTH.md`. El cliente Android independiente
está implementado en `../app_receptor/`; falta validarlo por Bluetooth físico.

## Interfaz compacta e historial

Pantalla desplazable: dos columnas desde 720 dp de ancho y paneles apilados por
debajo. Tarjeta de CO₂ reducida (28 sp, barra de 6 dp). Historial con área de
filas de 260 dp y encabezado fijo para que las filas no queden sin espacio en
pantallas bajas. Mensajes distintos para monitor detenido, espera y vista pausada.
Orientación forzada a landscape.

### Columnas del historial

Se preparó la tabla en orden CO₂, temperatura, humedad, latitud, longitud,
hora local de recepción, PM1, PM2.5, PM4 y PM10. Encabezados y filas se
desplazan horizontalmente juntos. Temperatura, humedad y partículas siguen en
«—». No se generan nuevas variables ficticias en demo.

## Archivos fuente

| Archivo | Propósito |
|---------|-----------|
| `SampleSource.kt` | Interfaz `SampleSource` y `SimulatedSampleSource` (demo) |
| `PayloadProtocol.kt` | Decodificación y validación de paquetes de 32 B |
| `AircraftPosition.kt` | Modelo de posición y `AircraftPositionTracker` thread-safe |
| `SampleHold.kt` | Zero-order hold: desacopla sensor (~1 Hz) de disco (~10 Hz) |
| `CsvJournal.kt` | Diario CSV append-only con sync, rollback y snapshot para export |
| `LiveTelemetry.kt` | Frescura, alarma y serialización NDJSON para Bluetooth |
| `BluetoothRelay.kt` | Servidor RFCOMM con canal conflado y watchdog |
| `FloatingMonitor.kt` | Ventana flotante arrastrable y colapsable |
| `MonitorService.kt` | Servicio en primer plano: alarma, BT, pop-up, notificación |
| `MonitorState.kt` | Estado inmutable: `GasSample`, buffer de hasta 3 600 muestras |
| `MonitorViewModel.kt` | ViewModel + `MonitorEngine` (singleton, adquisición y almacenamiento) |
| `MonitorControls.kt` | Controles Compose: pop-up, fondo, detener, umbral, permisos |
| `MainActivity.kt` | Activity landscape con dashboard responsive y tabla de historial |
| `DjiSampleSource.kt` | `SampleSource` DJI: MSDK lifecycle, payload listener, GPS pairing |
| `DjiAircraftPosition.kt` | Polling asíncrono de posición y señal GPS por MSDK |
| `MicroGasDjiApplication.kt` | Entry point DJI: instala dependencias nativas |
| `SourceFactory.kt` (×2) | Factory por flavor: demo devuelve simulado, dji devuelve DJI |

## Tests unitarios

37 tests en 6 clases bajo `app/src/test/`:

| Clase | Tests | Cobertura |
|-------|:-----:|-----------|
| `PayloadProtocolTest` | 4 | Decodificación con fixture C, rechazo corrupto, secuencia, rollover, boot retirement |
| `SampleHoldTest` | 4 | Supresión pre-primer-dato, `newData` único por muestra, retención ante valores inválidos, retención de flag en fallo de escritura |
| `CsvJournalTest` | 12 | Persistencia, locale alemán, truncamiento de filas parciales, fallo I/O, protección de sesión activa, path traversal, columnas GPS, compatibilidad CSV legacy |
| `LiveTelemetryTest` | 5 | Umbral inclusivo, expiración > 5 s, silencio NaN/∞, NDJSON locale, nulabilidad simulado, estructura GPS JSON |
| `MonitorStateTest` | 6 | Ventana visible, offset histórico estacionario, límite 3 600, retención de pico tras evicción, rechazo NaN, progreso vacío |
| `AircraftPositionTest` | 6 | Expiración monotónica, límites de coordenadas, coordenadas cero, filtro de señal, invalidación por reconexión, inmutabilidad de posición en muestra |

No se prueban: renderizado Compose, lifecycle de `MonitorService`, Bluetooth
físico, audio de `ToneGenerator`, conexión real MSDK con Mavic 3T.

## Prueba pendiente: Pilot 2 y MicroGas simultáneos

La duda no es si se puede volar y medir a la vez. Hay que verificar si MicroGas
puede recibir mediante MSDK mientras Pilot 2 utiliza el enlace del dron.
La documentación DJI consultada advierte sobre cerrar Pilot para iniciar apps
MSDK; no se debe asumir convivencia.

Prueba en tierra, motores apagados:

1. Conectar Pi, dron y control usando el montaje UART/E-Port V1.
2. Enviar un valor de prueba con secuencia nueva cada segundo.
3. Con Pilot 2 cerrado, verificar registro MSDK y recepción en MicroGas.
4. Abrir Pilot 2 y comprobar cámara/telemetría con MicroGas en segundo plano.
5. Exportar CSV y verificar secuencias nuevas durante ese intervalo, no solo
   cuando se vuelve a MicroGas.
6. Repetar abriendo primero Pilot 2 y después MicroGas.
7. Detener y reanudar los envíos del Pi para comprobar interrupción y recuperación.

Que crezca el CSV no demuestra conexión: puede repetir la última lectura.
Contrastar secuencia y tiempo del emisor con el log del Pi. Anotar firmware,
versiones y errores. Una prueba favorable no equivale a soporte oficial ni a
validación operacional en vuelo. Ahora existe un servicio en primer plano para
adquisición, CSV, alarma y Bluetooth; la convivencia real con Pilot 2 sigue
pendiente de prueba en el equipo.

## Posible etapa futura

Se añadió el emisor Bluetooth en MicroGas del control. La app Android
receptora está implementada en `../app_receptor/` y pendiente de validación física. USB
queda como alternativa sin evaluar. La convivencia con Pilot 2 y el transporte
Bluetooth requieren validación en el equipo. No se confirmó una salida nativa
del control que reenvíe estos datos al celular sin una app puente.

## Pendientes

1. **App Key DJI:** Configurar `dji.msdk.apiKey` antes de desplegar flavor `dji`.
2. **Convivencia Pilot 2 + MSDK:** Prueba en tierra (ver sección arriba).
3. **App receptora Bluetooth:** Cliente implementado en `../app_receptor/`; validar
   RFCOMM, permisos, reconexiones, gráficas, vibración y SAF en teléfono/control.
4. **Sensores adicionales:** UI y CSV preparados para temperatura, humedad,
   PM1/2.5/4/10, VOC y NOx implementados en apps y demo; integrar el paquete v2
  de 64 bytes en la Pi (ver `integration/SEN66_V2.md`).
5. **Límite de disco:** Borrado manual implementado; falta cuota automática.
6. **Audio y overlay en hardware real:** Verificar volumen del buzzer y
   transparencia de toques junto a Pilot 2 en el RC Pro Enterprise.
7. **Integración emisor C en Pi:** Ejemplo listo, falta integrar al programa real.


## App del celular: MicroGas Receptor (2026-09-25)

Proyecto independiente `../app_receptor/`, package `com.gaslab.microgas.receptor`.
Kotlin/Compose/Material 3, Java 17, compileSdk 35, targetSdk 34, minSdk 26.
Sin DJI SDK, servicio ni base de datos. Soporta portrait/landscape y tema del sistema.

- Lista emparejados y conecta RFCOMM con el UUID existente. NDJSON acotado a
  16 KiB por línea, lectura fragmentada UTF-8 y campos desconocidos ignorados.
- Reintenta tras 5/10/20/30 s, cancela socket bloqueado al desconectar y limita
  cada intento a 15 s. Solo conecta mientras la app está visible.
- MVVM con StateFlow, buffer de 7.200 muestras y detección de huecos/reinicios.
  Reconexiones conservan datos; conexión manual nueva los reemplaza con aviso.
- Monitor con ventana de 1–30 minutos (5 inicial). Histórico con tres gráficas
  Canvas, ventana de 1–60 minutos o Todo, zoom/pan y tabla desplazable.
- Temperatura y humedad opcionales (`temperature_c`, `humidity_pct`, extensión
  propuesta). Actualmente no llegan y se muestran vacías. No incluye SO₂.
- Alerta desde 3.000 ppm configurable/persistida; vibra al activarse y vence
  tras >5 s sin muestra nueva, usando recepción monotónica en el teléfono.
- Exportación SAF de instantánea CSV, UTC con milisegundos y decimales con punto.
  Las gráficas usan memoria; desde 0.2.0 hay respaldo CSV automático persistente
  de cada muestra recibida, independiente del buffer visual.

Detalles de uso, ciclo de vida y pruebas manuales en `../app_receptor/README.md`.


Validación local del receptor 0.2.0 (2026-09-25): 20 pruebas unitarias aprobadas,
`lintDebug` con cero errores y cuatro advertencias (targetSdk 34 solicitado,
reglas modernas de backup, carpeta v26 redundante e icono sin variante monocroma). `assembleDebug` completado y firma del APK verificada.
APK: `../app_receptor/app/build/outputs/apk/debug/app-debug.apk`.
No había dispositivos ADB ni emuladores configurados: interfaz/gestos, Bluetooth
físico, vibración y SAF pendientes de prueba en teléfono/control.


### Respaldo CSV en el celular (receptor 0.2.0)

Se agregó `CsvArchive`: archivo privado por sesión manual, escritura append-only
con `fd.sync()`, rollback ante error y exclusión de filas incompletas al exportar.
Registra cada muestra nueva recibida (~1 Hz), sin filas retenidas artificiales.
Las reconexiones conservan el archivo; una nueva conexión manual crea otro.
CSV sin límite de 7.200 muestras; se conserva tras cerrar o reiniciar la app.
La pantalla muestra filas confirmadas y fallos de almacenamiento. Archivos CSV
lista sesiones anteriores, exporta vía SAF conservando el original y permite
borrar con confirmación. La sesión activa está protegida y borrado/exportación
se excluyen. No hay base de datos, cuota automática ni recepción en segundo plano.
Los archivos del control no se modifican. Las muestras que no llegan por Bluetooth
no pueden respaldarse en el teléfono. Borrar datos/desinstalar elimina CSV privados.


## Nueve variables SEN66 (0.3.0; sustituye las limitaciones anteriores)

Las dos apps se actualizan a versionCode 3. El receptor ahora está en la carpeta
hermana `../app_receptor/`. Se implementaron temperatura, humedad, PM1/2.5/4/10
e índices VOC/NOx junto a CO₂ en modelos, UI, Bluetooth, CSV y simulador.

`PayloadProtocol` acepta v1 de 32 B y v2 de 64 B. V2: `MGAS`, bytes versión/flags
`02 01`, reservados cero, boot/secuencia/uptime conservados, nueve float32 desde
24 hasta 56 y CRC32 de bytes 0–59 en offset 60. Todo little endian. CO₂ válido
obligatorio; opcionales ausentes/inválidos se convierten a null (NaN en binario).
El contrato exacto y serializador C están en `integration/SEN66_V2.md` y
`integration/psdk/microgas_sender.c/.h`. No se modificó el driver I2C ni se desplegó
en la Pi. El usuario aún no ha preparado el emisor; integrará este contrato.

Bluetooth conserva JSON v1 con ocho campos opcionales adicionales. El CSV del
control añade ocho columnas al final (23 total); el teléfono añade seis (15 total,
porque ya tenía temperatura/humedad). CSV antiguos se exportan intactos. La
retención a 10 Hz del control conserva todas las variables de la misma muestra.

MicroGas Simulado produce las nueve variables a ~1 Hz, sin GPS ni metadatos de
payload falsos. Se puede instalar en control/otro Android 10+ y conectarlo al
receptor Android 8+ para probar Bluetooth y los dos respaldos sin dron. Usar el
APK `demoDebug`, no `djiDebug`, para esa prueba. Solo un servidor MicroGas activo
por dispositivo. Alarmas siguen siendo de CO₂; poner 700 ppm temporalmente permite
probarlas con la demo. El receptor tiene nueve gráficas, tabla y valores actuales.

Esta prueba no valida el enlace PSDK, firmware, I2C ni convivencia Pilot 2/MSDK;
la validación en hardware sigue pendiente. No se afirma ausencia de conflictos
operacionales por el solo hecho de que el paquete sea pequeño.


Validación 0.3.0: 43 pruebas aprobadas en Demo, 43 en DJI y 23 en Receptor.
Lint: cero errores; 11 advertencias en Demo, 10 en DJI y 4 en Receptor.
Serializador C compilado con `-Wall -Wextra -Werror`; las referencias binarias y
JSON coinciden entre proyectos. Los tres APK Debug 0.3.0 se generaron y sus firmas
se verificaron. Sin dispositivos ADB conectados; la prueba física queda pendiente.

## Actualización implementada: Receptor 0.4.3 — 2026-09-30

* **Home**: icono verde en la primera posición GPS válida recibida de la sesión; no certifica el punto de despegue. Se conserva aunque la muestra salga del buffer.
* **10 valores más altos**: selector de las nueve variables; mantiene las 10 muestras de mayor valor de toda la sesión de recepción, sin límite de antigüedad ni dependencia de la ventana visible o del buffer de 7.200 muestras. Al llegar datos se actualiza la clasificación; en empates se conserva primero la muestra recibida antes. Los valores ausentes/no finitos se excluyen. Con menos de 10 valores válidos se muestran los disponibles.
* Los máximos con GPS se resaltan en magenta sobre el mapa, incluidos los antiguos. La lista ordenada permite seleccionar cada máximo; los que no tienen GPS conservan su puesto y muestran «sin GPS». La trayectoria y el perfil de altura siguen usando el historial reciente. Home y máximos se reinician al iniciar manualmente otra sesión, no al reconectar automáticamente ni al cambiar la sesión del emisor. No se reconstruyen tras terminar el proceso.
* **Ejes geográficos**: marcas de latitud y longitud sobre la cuadrícula, actualizadas con zoom y paneo; proyección local con norte arriba.
* **CSV**: la lista rápida permite exportar. Para borrar entra en **Archivos CSV → Administrar CSV → Eliminar… → Borrar definitivamente**. Cada archivo requiere confirmación y la sesión activa permanece protegida; desconecta antes de borrarla.
* **Makefile**: `make apks`, `make dji`, `make demo` y `make receptor` compilan y copian los instaladores a `apks/`; `make test` ejecuta pruebas y `make lint` el análisis estático. `make clean` limpia las compilaciones. Las tareas se ejecutan en serie para evitar compilar simultáneamente el mismo proyecto.

DJI y Simulado conservan la versión 0.4.1 (5). Receptor usa 0.4.3 (7). Validación y limitaciones: [informe 0.4.3](../docs/VALIDACION_RECEPTOR_0.4.3.md).


## Próxima versión planificada: Receptor 0.5.0 — mapa OpenStreetMap offline

Contexto: misiones en volcanes, con o sin cobertura celular. Se usa **Osmdroid** (sin clave de API, sin Google Play Services). Si el teléfono tiene internet al momento de usar la app, el mapa muestra teselas OSM en tiempo real. Si no hay internet, el fondo queda gris pero la ruta, el Home y los máximos siguen funcionando normalmente porque dependen de `RouteGeometry`, no del mapa base.

### Decisión de diseño
El mapa va embebido dentro de la pestaña existente **Ruta y altura** (`RouteScreen`), como fondo georreferenciado bajo el Canvas actual de la ruta. El Canvas se conserva para la selección táctil de puntos y el perfil de altura. Solo afecta `app_receptor`; no se modifica `app_dji`.

### Cambios previstos

| Archivo | Cambio |
|---|---|
| `app_receptor/app/build.gradle.kts` | Agregar `implementation("org.osmdroid:osmdroid-android:6.1.20")` |
| `app_receptor/app/src/main/AndroidManifest.xml` | Permisos `INTERNET` y `ACCESS_NETWORK_STATE` |
| `app_receptor/app/src/main/java/.../RouteScreen.kt` | `Box` con `AndroidView { MapView }` como fondo y Canvas encima; el mapa se centra al bounding box de las muestras |
| `app_receptor/app/src/main/java/.../RouteGeometry.kt` | Exponer bounding box de lat/lon para centrar el `MapView` |

### Comportamiento esperado
* **Con internet**: el `MapView` descarga y muestra teselas OSM/Mapnik; Osmdroid las cachea automáticamente en `files/osmdroid/` para usos futuros.
* **Sin internet**: fondo gris. La ruta, el marcador Home, los puntos máximos y la inspección táctil funcionan igual porque son Canvas, no mapa base.
* El zoom/paneo del `MapView` es independiente del zoom del Canvas (georeferencia vs. inspección de detalle temporal).
* Ninguna funcionalidad ya implementada se reemplaza.

### Limitaciones conocidas
* Osmdroid requiere `AndroidView`, patrón estándar para mapas en Compose.
* El caché automático de tiles crece en disco con el uso; sin límite configurado por defecto.
* La precarga explícita de una zona (para garantizar offline antes de salir al campo) queda fuera del alcance de esta versión y se evaluará después.


