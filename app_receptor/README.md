# MicroGas Receptor

Cliente Android independiente para el servidor Bluetooth de MicroGas. Abrir
**esta carpeta `app_receptor/`** como proyecto en Android Studio.
Package `com.gaslab.microgas.receptor`, Android 8+ (API 26), compileSdk 35,
targetSdk 34, Java 17, Kotlin 2.0.21 y Compose Material 3. No requiere clave DJI.

## Resumen de Funciones Implementadas

* **Conexión Bluetooth RFCOMM Automática**: Conecta con el control DJI sin requerir permisos de ubicación. Incluye reconexión con backoff progresivo (5s, 10s, 20s, 30s) y watchdog de 15 segundos.
* **Monitor de CO₂ con Colores de Umbral (`Co2Appearance`)**:
  * **Blanco (`#FFFFFF`)**: Normal ($< 70\%$ del umbral).
  * **Amarillo (`#FFD600`)**: Precaución ($\ge 70\%$ y $\le 90\%$ del umbral).
  * **Rojo (`#FF5252`)**: Crítico ($> 90\%$ del umbral).
  * **Parpadeo Rojo/Blanco (500 ms) + Vibración háptica**: Alarma activa ($\ge 100\%$ del umbral).
  * Configuración de umbral persistente (1 a 100.000 ppm).
* **9 Gráficas Interactivas (Canvas Compose)**:
  * Seguimiento temporal de: CO₂, Temperatura, Humedad, PM1, PM2.5, PM4, PM10, VOC y NOx.
  * **Inspección Táctil de Muestras**: Tocar cualquier punto de la gráfica selecciona y resalta la muestra, desplegando una tarjeta debajo de la gráfica con hora exacta (`dd/MM/yyyy HH:mm:ss.SSS`), valor de la variable y **coordenadas GPS de la aeronave (Latitud y Longitud)** en ese instante.
  * **Gestos Multitáctiles**: Pellizcar para zoom (hasta 60x) y arrastre horizontal con anclaje temporal de vista. Botón *Restablecer* para regresar al seguimiento en vivo.
  * Ventanas seleccionables: 1, 2, 5, 10, 15, 30, 60 minutos o Todo.
* **Tabla Cruda Completa**: Visualización con desplazamiento bidireccional de todas las columnas recibidas en la sesión activa.
* **Respaldo Automático Privado**: Escritura continua e independiente en disco (`files/sessions/`) con `fd.sync()`. Supera el límite de memoria de 7.200 muestras de la UI y no se pierde al cerrar la app.
* **Gestor y Exportador de CSV**: Exportación vía Storage Access Framework (SAF) hacia cualquier carpeta o servicio en la nube.

## Compilar

Configurar SDK Platform 35 y JDK 17. Android Studio genera `local.properties`
con `sdk.dir`; este archivo es local y no debe compartirse.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.
La dependencia `org.json` es solo para pruebas JVM; Android proporciona el parser
usado por la aplicación. Las gráficas usan Canvas de Compose sin librería adicional.

## Conectar y usar

1. Emparejar el teléfono y el control desde los ajustes Bluetooth de Android.
2. Iniciar MicroGas en el control para activar su servidor Bluetooth.
3. Abrir MicroGas Receptor, conceder **Dispositivos cercanos** en Android 12+
   y seleccionar el control en la lista de emparejados.
4. Monitor muestra CO₂, origen, hora y frescura. Seleccionar 1/2/5/10/15/30 min.
   El texto de CO₂ cambia a amarillo al 70% del umbral y a rojo por encima del 90%. Al llegar
   al 100% parpadea en rojo/blanco y activa la vibración.
5. Histórico muestra las nueve variables del SEN66, ventanas hasta 60 min o Todo.
   Pellizcar para zoom y arrastrar horizontalmente; tocar cualquier punto para ver
   su tarjeta de detalle con fecha, valor y latitud/longitud del dron. **Restablecer**
   vuelve a seguir la última lectura. La tabla contiene las muestras retenidas en memoria (máximo 7.200); el respaldo CSV puede contener más.
6. **Umbral** guarda el límite (1–100.000 ppm; inicial 3.000). El banner rojo
   aparece desde el umbral inclusive; el teléfono vibra una vez al entrar en
   alerta. Se desactiva al bajar, perder la conexión, ocultar la app o superar
   cinco segundos sin una muestra nueva aceptada.
7. **Exportar CSV** abre el selector de Android; elegir carpeta y nombre.
   Se exporta la instantánea de todas las muestras retenidas al pulsar el botón.
   Cancelar no elimina datos. Un error se informa y permite reintentar.
8. **Bluetooth → Desconectar** cancela conexión y reintentos, conservando datos.

## Sesión, reconexión y memoria

- RFCOMM seguro mediante `createRfcommSocketToServiceRecord`, UUID
  `bb239920-bdbc-4d51-8a12-a5351875d891`. Sin descubrimiento ni permiso de ubicación.
- Reintentos automáticos tras 5, 10, 20 y 30 segundos (máximo 30). El backoff
  se reinicia después de recibir un paquete válido. Cada intento de conexión
  tiene un watchdog de 15 segundos. Desconectar cierra el socket y cancela todo.
- Desde 0.4.2, un servicio en primer plano mantiene Bluetooth, reconexión y CSV al ocultar la app, abrir SAF o bloquear la pantalla. La notificación **MicroGas Receptor activo** permite desconectar. Android 13+ solicita permiso para mostrarla; denegarlo no impide la recepción.
- Volver a la pantalla o recrearla reutiliza la misma sesión mientras vive el proceso. Las alertas hápticas siguen limitadas a la interfaz visible. Desconectar detiene el servicio; forzar cierre o terminar el proceso interrumpe la recepción y conserva los CSV ya guardados. No hay recuperación de paquetes perdidos.
- Los reintentos y cambios de `session` del emisor conservan el histórico.
  Una conexión manual inicia una sesión nueva, con confirmación si hay datos.
- Máximo 7.200 muestras: se descartan las más antiguas y se informa el contador.
  Las secuencias duplicadas, regresivas y de sesiones retiradas no se agregan.
  Huecos de secuencia y cambios de sesión se muestran en Histórico.
- La terminación del proceso pierde las gráficas en memoria, pero conserva los
  CSV automáticos en el teléfono. No hay base de datos. Los archivos se pueden
  exportar tras volver a abrir la app desde **Archivos CSV**.
- Frescura medida desde la recepción en el teléfono con reloj monotónico.
  `received_at_ms` del control se conserva para gráfica/CSV; no se comparan
  relojes entre dispositivos, ya que pueden estar desincronizados. La frescura
  no mide el retardo total desde el sensor hasta el teléfono.

## Compatibilidad del protocolo

NDJSON UTF-8, versión `v:1`. Se aceptan lecturas fragmentadas, varias líneas por
lectura y CRLF. Una línea de más de 16 KiB se descarta hasta el siguiente salto
de línea. Los JSON inválidos o versiones no soportadas se ignoran. No se emiten
valores ficticios. Las coordenadas son las del dron incluidas por el control.

Desde 0.3.0 se reciben `temperature_c`, `humidity_pct`, `pm1_0`, `pm2_5`,
`pm4_0`, `pm10`, `voc_index` y `nox_index`, opcionales y nulos. Los campos ausentes
siguen en «—»/«Sin datos» y los desconocidos se ignoran. Hay nueve gráficas y todos
los valores en la tabla y el monitor. VOC/NOx son índices, no concentraciones.
Ver el [contrato y la prueba sin dron](../app_dji/integration/SEN66_V2.md).

CSV UTF-8: `fecha_hora_utc,co2_ppm,temperatura_c,humedad_pct,latitud,longitud,origin,session,sequence,pm1_0,pm2_5,pm4_0,pm10,voc_index,nox_index`.
UTC ISO-8601 con tres decimales de milisegundos; números con punto y campos
opcionales vacíos. No modifica la app ni el protocolo del control.

## Prueba manual en teléfono y control

- Android 8–11: conectar sin permiso de ubicación. Android 12+: conceder,
  denegar y revocar Dispositivos cercanos; volver desde ajustes y actualizar.
- Probar ambos sentidos de rotación, tema oscuro y fuente grande. Recibir
  muestras y verificar gráficas, zoom/pan, tabla horizontal y placeholders.
- Apagar Bluetooth/control: observar reintentos y reconectar. Desconectar
  manualmente durante conexión/reintento y confirmar que no vuelve a conectar.
- Reiniciar emisor: contador de reinicios aumenta, CO₂ continúa. Probar huecos
  y duplicados. Superar 7.200 muestras y comprobar el límite.
- Usar valores alrededor de 3.000 ppm; detener envíos durante más de 5 s.
  Comprobar banner/vibración y persistencia del umbral tras reiniciar la app.
- Exportar, cancelar, cambiar de orientación con SAF abierto y probar un
  destino que falle. Revisar CSV UTC, decimales y coordenadas.
- Pulsar Inicio/bloquear: no debe quedar adquisición ni vibración activa.
  Volver: reconectar y conservar histórico mientras el proceso siga vivo.

## Respaldo automático en el teléfono (0.2.0)

Cada muestra nueva aceptada se agrega al CSV privado `files/sessions/`, con
`fd.sync()` por escritura y reversión de una escritura fallida. Se crea un archivo
por conexión manual, al recibir la primera muestra. Reconexiones automáticas,
rotaciones, pausa al ocultar la app y reinicios del emisor conservan ese archivo
mientras siga viva la sesión del receptor. No se duplican filas retenidas a 10 Hz:
el teléfono registra las muestras nuevas que efectivamente recibe (~1 Hz).

El CSV no tiene el límite de 7.200 muestras del histórico visual y permanece tras
cerrar el proceso o reiniciar el teléfono. **Archivos CSV** permite listar,
exportar y borrar con confirmación. Para borrar la sesión activa, desconecta
primero. No hay borrado automático. Desinstalar o borrar los datos de la aplicación
elimina estos archivos privados; las copias exportadas permanecen.

**Guardadas** cuenta las filas confirmadas de la sesión actual. Si hay un fallo de
disco se muestra el error y el número de muestras no guardadas; se vuelve a
intentar con la siguiente muestra, sin prometer recuperar las fallidas.
Exportar un archivo conserva el original y excluye filas incompletas y muestras
posteriores al inicio de la copia. Un destino lento no bloquea nuevas escrituras.
La exportación del histórico en memoria sigue disponible como operación aparte.

Este respaldo solo contiene datos recibidos por Bluetooth. No recupera muestras
perdidas durante una desconexión ni mientras el receptor está oculto. El registro
del control continúa siendo independiente.

La validación física de RFCOMM, vibración, SAF y gestos requiere un teléfono
emparejado con el control.


Validación local del receptor 0.2.0 (2026-09-25): 20 pruebas unitarias aprobadas,
`lintDebug` con cero errores y cuatro advertencias (targetSdk 34 solicitado,
reglas modernas de backup, carpeta v26 redundante e icono sin variante monocroma). `assembleDebug` completado y firma del APK verificada.
APK: `app/build/outputs/apk/debug/app-debug.apk`.
No había dispositivos ADB ni emuladores configurados: interfaz/gestos, Bluetooth
físico, vibración y SAF pendientes de prueba en teléfono/control.

Pruebas manuales del respaldo: recibir muestras, cerrar y reabrir la app y
exportar el archivo anterior; conectar de nuevo y comprobar que aparecen dos
archivos; cancelar y confirmar borrado de la sesión antigua; comprobar que la
activa está protegida hasta desconectar. Cancelar/fallar una exportación debe
conservar el original. Validar que un error de almacenamiento se muestre y que
los archivos sigan creciendo aunque el buffer gráfico alcance 7.200 muestras.


Validación 0.3.0: 43 pruebas aprobadas en Demo, 43 en DJI y 23 en Receptor.
Lint: cero errores; 11 advertencias en Demo, 10 en DJI y 4 en Receptor.
Serializador C compilado con `-Wall -Wextra -Werror`; las referencias binarias y
JSON coinciden entre proyectos. Los tres APK Debug 0.3.0 se generaron y sus firmas
se verificaron. Sin dispositivos ADB conectados; la prueba física queda pendiente.

## Historial de Actualizaciones (Updates)

### v0.4.0 (2026-09-29)
Ruta y altura implementadas: consultar el [historial principal](../README.md#historial-de-actualizaciones-updates).
Demo genera latitud, longitud y altura desde cero; DJI conserva la altura cruda del SDK (referencia pendiente). Bluetooth añade `aircraft_position.altitude_m` y los CSV agregan `altura_m` al final. El receptor ofrece la pestaña **Ruta y altura** con zoom, paneo, recentrado, inspección de muestras y perfil de altura. No se inventan coordenadas en el receptor si faltan en el mensaje.

### v0.3.1 (2026-09-28)
* **Inspección Táctil de Muestras en Gráficas**: Integración de selección interactiva de puntos (`nearestChartPoint` y `detectTapGestures` en `Co2Chart`). Tocar cualquier punto abre una tarjeta emergente con fecha/hora milimétrica (`dd/MM/yyyy HH:mm:ss.SSS`), valor de la métrica y coordenadas GPS (**Latitud y Longitud**) de la aeronave.
* **Código de Colores de Umbral (`Co2Appearance`)**: La lectura principal de CO₂ ahora se colorea dinámicamente según proximidad al umbral (blanco normal, amarillo al 70%, rojo por encima del 90% y parpadeo intermitente rojo/blanco durante alarma activa).
* **Compilación y APK**: Generación del instalador `MicroGas-Receptor-debug.apk` con validación completa de pruebas unitarias.

### v0.3.0 (2026-09-25)
* **Recepción del Multisensor SEN66**: Soporte completo para 9 variables (CO₂, temperatura, humedad, PM1, PM2.5, PM4, PM10, VOC, NOx).
* **9 Gráficas Canvas Nativas**: Visualización individual con Compose Canvas para cada una de las variables ambientales.
* **Tabla Cruda Expandida**: Incorporación de todas las columnas de la sesión activa con scroll horizontal.

### v0.2.0 (2026-09-25)
* **Respaldo Automático Privado en Disco**: Escritura atómica con `fd.sync()` en almacenamiento de la app (`files/sessions/`) para no perder datos ante cierres inesperados.
* **Gestor de Archivos CSV**: Interfaz para listar, exportar vía SAF y limpiar sesiones locales.

### v0.1.0 (2026-09-23)
* **Cliente Bluetooth RFCOMM Inicial**: Conexión al servidor del control DJI, monitor de CO₂, alertas por vibración y exportación de memoria.


## Guía para quien empieza a trabajar

La carpeta es `GasLab/MicroGas/app_receptor/`. El [manual principal](../MANUAL_REPLICACION_DESDE_CERO.md) explica cómo preparar el SDK, compilar e instalar y probar con dos dispositivos sin dron. Para esa prueba instala **Simulado** en el emisor, no la variante DJI. La compilación y `--offline` requieren que las dependencias estén disponibles previamente.

Para modificar esta app, comienza por:

| Trabajo | Archivo bajo `app/src/main/java/com/gaslab/microgas/receptor/` |
|---|---|
| Decodificar JSON o cambiar variables | `Measurement.kt` (también contiene `MeasurementParser` y `NdjsonFramer`) |
| Cambiar conexión y reconexión | `BluetoothClient.kt`, `MonitorViewModel.kt` |
| Ajustar memoria y CSV | `MeasurementRepository.kt`, `CsvArchive.kt` |
| Modificar gráficas o toque de puntos | `Co2Chart.kt`, `ChartSelection.kt` |
| Cambiar alarma y colores | `AlertManager.kt`, `Co2Appearance.kt`, `MonitorScreen.kt` |

La selección funciona en el monitor y en las nueve gráficas del histórico. El zoom/paneo se habilita en el histórico (hasta 60x). El toque elige la muestra cercana en pantalla, muestra la hora de recepción del control con milisegundos y las coordenadas que acompañaban a esa muestra. Si no existen, muestra **GPS no disponible**. Seis decimales en pantalla no prueban precisión geográfica. Puedes cerrar el detalle; Restablecer también quita la selección del histórico.

Hay dos exportaciones distintas: **Exportar CSV** del histórico copia el buffer en memoria; **Archivos CSV → Exportar** copia un respaldo persistente. Para entregar sesiones largas usa el segundo. Ocultar Receptor suspende su conexión por diseño; desactivar restricciones de batería no cambia este comportamiento. El emisor guarda de manera independiente.

El umbral se configura en cada dispositivo: no se sincroniza por Bluetooth. Para comparar alertas establece el mismo valor en ambos. La alarma del teléfono vibra al entrar y muestra el banner; no implementa los pitidos del servicio DJI.

Los conteos de pruebas anteriores son históricos. Repite los comandos de la sección Compilar para una nueva entrega. `versionName` es 0.4.2 y `versionCode` es 6.

Análisis posterior: [graficacionCompleta](../../graficacionCompleta/README.md). El teléfono exporta 16 columnas, conserva campos vacíos para datos ausentes y no inventa GPS de simulación.
