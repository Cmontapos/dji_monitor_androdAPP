# MicroGas DJI & Demo

## Antes de compilar DJI: dónde colocar la App Key (APP_ID)

Este proyecto Android usa la **App Key de DJI Mobile SDK (MSDK)**, no un campo llamado `APP_ID`. Si tienes un App ID del payload/PSDK, no lo pegues como clave MSDK. El identificador Android del proyecto es `com.gaslab.microgas`.

Edita **`app_dji/local.properties`**, junto a `gradlew` y `settings.gradle.kts`, y añade esta línea conservando el `sdk.dir` existente:

```properties
dji.msdk.apiKey=TU_APP_KEY_DJI_MSDK
```

No añadas comillas ni pegues el identificador de la app en lugar de la clave. `local.properties` está excluido de Git; configúralo en cada equipo de desarrollo. No necesitas editar el manifiesto ni el código Kotlin.

Como alternativa, define `DJI_MSDK_API_KEY` en el entorno del proceso que ejecuta Gradle. Esa variable tiene prioridad sobre `dji.msdk.apiKey`, incluso si está definida vacía. Gradle coloca el valor en `com.dji.sdk.API_KEY` del manifiesto de la variante `dji` mediante el placeholder `DJI_API_KEY`.

Desde la raíz `MicroGas`, ejecuta `make dji` para reconstruir `apks/MicroGas-DJI-debug.apk`. En Android Studio abre `app_dji` y selecciona `djiDebug`. La variante Simulado (`demoDebug`) y Receptor no requieren esta clave. Compilar DJI sin clave no demuestra que el SDK pueda registrarse o recibir datos reales.

Aplicación para el control remoto **DJI RC Pro Enterprise** (Android 10+, API 29+). Integra la captura de gases del multisensor **Sensirion SEN66**, coordenadas GNSS de la aeronave mediante **DJI Mobile SDK v5**, visualización sobre **DJI Pilot 2**, retransmisión **Bluetooth RFCOMM** a estaciones terrestres y registro seguro en archivos **CSV**.

## Resumen de Funciones Implementadas

* **Lectura Multisensor SEN66**: Recepción por `PayloadDataListener` de MSDK del protocolo MicroGas v2 de 64 bytes con validación CRC32 (CO₂, Temperatura, Humedad, PM1.0, PM2.5, PM4.0, PM10, VOC y NOx).
* **Telemetría GNSS del Dron**: Consulta asíncrona por hardware (`KeyAircraftLocation3D` y `KeyGPSSignalLevel`) de MSDK v5, asociando latitud, longitud y calidad de señal a cada muestra de gas con filtro de frescura (≤ 5 s).
* **Ventana Flotante (Pop-up Overlay)**: Opera en primer plano sobre DJI Pilot 2; arrastrable, minimizable y con indicadores de CO₂, enlace RX y Bluetooth.
* **Control de Umbral y Código de Colores (`Co2Appearance`)**:
  * Configuración de umbral de alarma persistente (1 a 100.000 ppm, por defecto 3.000 ppm).
  * **Blanco (`#FFFFFF`)**: Normal ($< 70\%$ del umbral).
  * **Amarillo (`#FFD600`)**: Precaución ($\ge 70\%$ y $\le 90\%$ del umbral).
  * **Rojo (`#FF5252`)**: Crítico ($> 90\%$ del umbral).
  * **Alarma**: Parpadeo continuo Rojo/Blanco (500 ms) y pitidos audibles periódicos ($\ge 100\%$ del umbral, silenciándose si el dato supera 5 s sin renovarse).
  * Los colores se reflejan tanto en la pantalla principal como en el widget flotante.
* **Servidor Bluetooth RFCOMM**: Difusión continua en formato NDJSON v1 a ~1 Hz con métricas ambientales completas y posición de la aeronave.
* **Registro CSV Seguro a Disco**: Grabación a ~10 Hz (hold de muestras) con llamada forzada `FileDescriptor.sync()` para reducir datos pendientes de escritura; no garantiza recuperación ante una falla física, con exportación SAF hacia memorias USB.
* **Variante Simulador (Demo)**: Permite evaluar toda la UI y la retransmisión Bluetooth con las nueve variables simuladas a ~1 Hz, con ruta y altura sintéticas, sin hardware DJI conectado.

Prototipo Android horizontal para DJI RC Pro Enterprise. Kotlin y Jetpack Compose.
La variante Demo muestra datos simulados a ~1 Hz; DJI muestra los recibidos del payload. Incluye máximo observado, barra relativa, pausa y últimas
10 muestras por defecto, con selección de cantidad y retroceso. Las columnas sin datos aparecen como «—». DJI consulta el GPS del dron mediante MSDK;
la demo genera coordenadas sintéticas en órbita de ≈100 m alrededor de 10.197183°, -84.232373° y altura de 0–60 m.
El indicador de simulación permanece visible. Las barras no son umbrales de alarma.

## Abrir y ejecutar

1. En Android Studio, selecciona **Open** y abre esta carpeta `app_dji`.
2. Configura **Gradle JDK 17** en los ajustes de Gradle de Android Studio.
3. Instala **Android SDK Platform 35** y **Build Tools 34.0.0** desde SDK Manager.
4. Sincroniza Gradle; la primera sincronización descarga las dependencias.
5. Ejecuta `app` en un emulador Android 10 o posterior, en horizontal. Luego prueba
   en el control cuando esté disponible como dispositivo de desarrollo.

La pantalla del control es de 1920 × 1080 píxeles. El diseño usa `dp`/`sp`, por lo que
la densidad y el tamaño de fuente reales requieren revisión en el dispositivo.
`MainActivity.kt` incluye una vista previa Compose de 960 × 540 dp; esa densidad
es una referencia visual, no una medición del control. Ambos paneles permiten
desplazamiento vertical si las 10 filas o los botones no caben.

Con JDK 17 y Android SDK configurados también puedes ejecutar:

```sh
./gradlew :app:testDemoDebugUnitTest :app:lintDemoDebug :app:assembleDemoDebug
```

APK esperado: `app/build/outputs/apk/demo/debug/app-demo-debug.apk`.
En Windows usa `gradlew.bat`. Android Studio puede generar `local.properties`
con la ruta de tu SDK; ese archivo no se comparte.

## Estructura

- `MonitorState.kt`: lecturas válidas, máximo, escala y ventana de 10 muestras.
- `SampleSource.kt`: interfaz de entrada y simulador.
- `SampleHold.kt`: último valor válido, secuencia y distinción entre muestra y fila CSV.
- `CsvJournal.kt`: escritura sincronizada y exportación del CSV.
- `MonitorViewModel.kt`: colección y estado de la pantalla.
- `MainActivity.kt`: interfaz y vista previa.
- `MonitorStateTest.kt`: casos de historial, valores inválidos, reinicio y ceros.

## Registro automático y exportación

Un ciclo de almacenamiento independiente agrega una fila aproximadamente cada
100 ms a un CSV privado en `files/sessions/`, forzando escritura con
`FileDescriptor.sync()` en un hilo de IO. El simulador entrega CO₂ nuevo a ~1 Hz;
las filas intermedias conservan el último valor válido. No se escriben filas
hasta recibir la primera muestra válida. Una demora de disco puede reducir la
frecuencia: no se crean filas retroactivas para rellenar el tiempo perdido.
El contador muestra filas guardadas, no mediciones físicas. Los fallos de almacenamiento
aparecen en pantalla junto al total de filas que no se pudieron guardar;
se reintenta en el siguiente ciclo, sin prometer recuperar filas fallidas.

Se crea un archivo por ciclo de adquisición, al recibir su primera muestra.
Al detener el monitor se cierra esa sesión; al iniciar de nuevo se crea otra. Los archivos anteriores sobreviven
al cierre y reinicio de la app; la pantalla conserva hasta 3.600 lecturas nuevas en memoria, mostrando 10 por defecto. Pausar vista no pausa el registro. Abrir el selector de exportación o
rotar el dispositivo tampoco corta la adquisición. Hay un servicio en primer plano mientras el monitor está iniciado. Un cierre
forzado, apagado o terminación del proceso interrumpe la adquisición.
Los datos ya escritos se conservan; desinstalar o borrar datos de la app los elimina.

Para copiar a una llave USB:

1. Conecta la llave al control y pulsa **Archivos CSV**.
2. Busca la sesión actual o una anterior y pulsa **Exportar**.
3. En el selector de Android elige la llave USB, si aparece, y pulsa **Guardar**.

También puedes elegir otra carpeta o proveedor que Android ofrezca. No se necesitan
permisos generales de almacenamiento. La copia toma las filas completas existentes
al iniciar la copia después de elegir destino; las muestras posteriores continúan
solo en el original. Cancelar o fallar la exportación conserva el archivo local.
Si se retira la llave durante la copia, el destino puede quedar incompleto; vuelve
a exportar. Una exportación exitosa significa que el proveedor aceptó y cerró la
escritura, no una garantía frente a una falla física de la unidad.

El encabezado actual tiene **24 columnas**. Consulta su orden completo y significado en el [manual](../MANUAL_REPLICACION_DESDE_CERO.md#10-comprender-el-registro-antes-de-analizarlo). Las primeras ocho se conservan por compatibilidad:
`fecha_hora_utc,co2_ppm,sen66_new_data,sen66_muestra_utc,sen66_secuencia,latitud,longitud,origen`.

- `fecha_hora_utc`: hora de la fila CSV, con resolución de milisegundos cuando corresponda.
- `sen66_muestra_utc`: hora de recepción de la lectura retenida; todavía no es un reloj del sensor.
- `sen66_secuencia`: contador local de muestras válidas; avanza aunque el valor de CO₂ no cambie.
- `sen66_new_data`: 1 si la fila contiene una muestra más reciente que la última fila
  guardada correctamente; 0 si repite esa muestra. Un fallo de escritura no consume la bandera.
- Las fechas usan UTC ISO 8601 y los decimales punto. En demo el GPS permanece vacío y el origen es `simulado`; en DJI se guarda
  la posición del dron si hay una consulta reciente y señal suficiente.

Si dejan de llegar datos se sigue guardando el último valor con su timestamp original
y bandera 0. Esto permite reconocer su antigüedad; no significa que siga conectado
el sensor. Los CSV antiguos siguen disponibles y se exportan sin cambiar su formato.
La fuente DJI ya identifica el origen y valida paquetes; debe emitir desde `SampleSource` solo mediciones nuevas y validadas. La lectura I2C, Data Ready y CRC del sensor deben integrarse en el lado de adquisición de la Pi.

En **Ver datos** puedes elegir la columna CO₂, cantidad de lecturas (1–3600) y
retroceso X (0 = recientes). Con 40 lecturas, cantidad 10 y retroceso 20 muestran
las lecturas 11–20. La ventana histórica se mantiene mientras llegan datos nuevos,
hasta que las lecturas más antiguas salen del límite de memoria. **Volver a recientes**
retoma el seguimiento. Se cuentan muestras a ~1 Hz, no filas CSV a ~10 Hz.
La tarjeta CO₂ conserva su lectura actual y su escala. Pausar vista congela también
el historial de pantalla; el almacenamiento CSV continúa. El selector no abre CSV
anteriores para visualizarlos: esos archivos siguen disponibles para exportación.

Prueba manual en emulador/control: esperar 12 muestras, pausar la vista y comprobar
que el contador CSV sigue aumentando; exportar y comprobar encabezado y filas;
cerrar y abrir la app para exportar la sesión anterior. Repetir cancelando el
selector y retirando la USB durante una copia. La detección USB depende del control.


## Integración pendiente

La variante demo no necesita credenciales. La variante DJI incorpora MSDK 5.18
y requiere App Key para `com.gaslab.microgas`. Su recepción real está pendiente
de validación; el GPS del dron está integrado por MSDK y la recepción física de las nueve variables sigue pendiente de integrar/probar en la Pi.

El SEN66 no mide SO₂. No se generan valores ficticios para esa tarjeta.
El paquete de 12 bytes de `contexto_app.md` es una propuesta histórica y no es el contrato implementado. Usa v1 (32 bytes) o v2 (64 bytes).
El `applicationId` provisional es `com.gaslab.microgas`; antes de integrar DJI
debe coincidir con el registrado para la App Key. No agregar claves al repositorio.

## Estado de verificación

Los conteos siguientes corresponden a comprobaciones históricas, no a una ejecución nueva ni a una certificación en hardware. Ejecuta las tareas de Gradle sobre la copia que vayas a entregar. `versionName` es 0.4.1 y `versionCode` es 5.

Validación anterior de frecuencias e historial: 15 pruebas unitarias aprobadas.
La ampliación de pop-up/Bluetooth incluye pruebas adicionales de umbral, frescura
y formato del protocolo; ver el resultado actualizado al final de este archivo.
Se verifican retención de todas las muestras, reapertura de sesiones, decimales
independientes del idioma, exportación limitada a una instantánea, filas truncadas
y fallos de almacenamiento/destino. La validación visual y la exportación a USB
en emulador/control siguen pendientes.

Versiones fijadas: AGP 8.7.3, Gradle 8.9, Kotlin 2.0.21, Compose BOM 2024.12.01.
La combinación Gradle/JDK se basa en las [notas de AGP 8.7](https://developer.android.com/build/releases/agp-8-7-0-release-notes).
Hardware: [manual DJI](https://dl.djicdn.com/downloads/DJI_Mavic_3_Enterprise/20250916/DJI_Mavic_3_Enterprise_Series_User_Manual_EN.pdf)
y [ficha SEN66](https://sensirion.com/products/catalog/SEN66).

## Verificación de pop-up, alarma y Bluetooth (2026-09-23)

23 pruebas unitarias aprobadas. `lintDemoDebug` y `lintDjiDebug`: cero errores
y nueve advertencias cada uno (compatibilidad, recursos de texto y configuración).
APK demo y DJI generados con Gradle offline:

- `app/build/outputs/apk/demo/debug/app-demo-debug.apk`
- `app/build/outputs/apk/dji/debug/app-dji-debug.apk`

No se probó en hardware: `adb devices` no mostró dispositivos conectados.
La App Key DJI debe estar configurada antes de usar recepción real.

## Ícono de MicroGas

Ambas variantes usan el logo de fondo blanco de `assets/logos/` como ícono
adaptativo, con margen para máscaras circulares. Los dos originales se conservan.

Para probar solo la interfaz en otro control Android, instala la variante
**MicroGas Simulado**: requiere Android 10 (API 29) o superior y que el firmware
permita instalar APK externos. No requiere dron ni App Key DJI. El pop-up necesita
además permiso para mostrar sobre otras apps. Esta prueba no valida recepción del
payload ni compatibilidad de MSDK con el dron de ese otro control.

## Borrar sesiones CSV

En **Archivos CSV**, pulsa **Borrar** junto a la sesión y confirma con
**Borrar definitivamente**. Cancelar conserva el archivo. La lista se actualiza
después del borrado; un fallo se muestra en el diálogo. Se elimina únicamente
el CSV interno seleccionado; las copias exportadas y el historial visible
en memoria se conservan.

La sesión activa aparece como **Grabando · protegida**. Pulsa **Detener** en la
app para cerrar el archivo antes de borrarlo. Pausar la vista no cierra la sesión.
Puedes borrar sesiones anteriores mientras otra se graba. Exportación y borrado
no se ejecutan simultáneamente. No hay borrado masivo ni automático.

Prueba manual: crear dos sesiones iniciando y deteniendo la adquisición; comprobar
que Borrar esté deshabilitado para la activa; cancelar y confirmar el borrado de
la anterior; comprobar que desaparezca de la lista y que una copia exportada siga
disponible. Detener, borrar la última sesión y volver a iniciar la adquisición.

La interfaz sigue en horizontal, pero ahora se puede desplazar completa. Con
menos de 720 dp de ancho apila CO₂ e historial; con más ancho usa dos columnas.
Los controles saltan de línea si no caben. La tarjeta de CO₂ usa texto de 28 sp
y barra de 6 dp; el historial reserva 260 dp para las filas bajo un encabezado
fijo, sin depender del espacio que sobre en pantalla.

El nombre visible usa el recurso propio `microgas_app_name` para evitar que las
traducciones incluidas en MSDK lo sustituyan por «DJI Pilot 2».

## Ocultar el pop-up sin detener el monitor

El botón **Segundo plano** del pop-up retira la ventana flotante y conserva
adquisición, CSV, alarma y Bluetooth. La notificación permanece activa. Para
recuperar el pop-up, toca la notificación y pulsa **Iniciar pop-up** en la app.
**Minimizar** sigue dejando el indicador compacto visible.

El historial distingue monitor detenido, espera de muestras y vista pausada.
Si desactivaste CO₂ en «Ver datos», ofrece **Mostrar columna CO₂**. En pantallas
bajas, desplaza la pantalla para llegar al historial; sus filas se desplazan
independientemente dentro de la tarjeta.

Prueba manual pendiente: comprobar las filas de la demo en teléfono y control,
desplazar con fuente grande y pasar pop-up → Segundo plano → Iniciar pop-up,
verificando que el contador CSV continúe y no se cree otra sesión al ocultarlo.

## Columnas del historial

Orden: CO₂ (ppm), temperatura (°C), humedad (% HR), latitud (°), longitud (°),
hora del día local de recepción, PM1, PM2.5, PM4 y PM10 (µg/m³), VOC y NOx (índices).
Desliza horizontalmente para consultar las columnas de la derecha; encabezados y
filas comparten el desplazamiento. Las filas se desplazan verticalmente bajo el
encabezado. Las columnas sin datos muestran «—», nunca ceros ficticios.

El protocolo MicroGas v1 entrega solo CO₂; v2 y el simulador entregan las nueve variables. En DJI, latitud y
longitud se añaden consultando el dron por MSDK desde el control, sin cambiar el
paquete de la Pi. Temperatura, humedad, partículas e índices se decodifican del
paquete v2 y se simulan en demo; falta integrar ese paquete en la Pi real.
La hora procede de la recepción local; no es una hora GPS del dron ni el timestamp
monotónico del payload.

El SEN66 ofrece PM1, PM2.5, PM4 y PM10 según su
[ficha oficial](https://sensirion.com/products/catalog/SEN66).

## GPS del dron consultado desde MicroGas DJI

La variante DJI consulta `FlightControllerKey.KeyAircraftLocation3D` y
`KeyGPSSignalLevel` mediante `KeyManager.getValue(key, callback)`, la API
asíncrona de consulta al hardware. No utiliza ubicación Android del control,
punto de origen ni lecturas periódicas de la caché síncrona.

Se consulta mientras MSDK está registrado y el dron conectado, con pausa de un
segundo entre consultas y timeout de dos segundos para cada par de respuestas.
Se admiten coordenadas finitas dentro de [-90,90]/[-180,180] y nivel de señal
3, 4, 5 o 10. El nivel 10 no se interpreta como garantía de precisión RTK.
Ceros válidos no se descartan. Una consulta fallida, señal débil, desconexión o
cambio de dron invalida la posición; respuestas de conexiones anteriores no se usan.

Al recibir CO₂ se toma una instantánea de la última posición, solo si fue recibida
hace cinco segundos o menos según el reloj monotónico. No se espera GPS para
aceptar CO₂. Si no hay GPS válido, la tabla muestra «—», CSV deja campos vacíos y
Bluetooth transmite `aircraft_position: null`. El estado de la fuente muestra
«GPS dron» y la causa de indisponibilidad. La demo genera coordenadas sintéticas identificadas como `simulado` y altura desde cero.

La asociación corresponde a la **recepción del CO₂ en el control**, no garantiza
la posición exacta al adquirir CO₂ en la Pi: no hay sincronización de sus relojes
ni compensación del retardo del enlace. El tiempo GPS guardado es la recepción
local de la respuesta de MSDK, no una hora GNSS del satélite.

Los CSV nuevos conservan sus columnas anteriores, rellenan `latitud`/`longitud`
y añaden al final:

- `gps_origen`: `dji_aircraft_msdk`.
- `gps_recepcion_utc`: recepción local de la posición, ISO UTC.
- `gps_edad_al_recibir_co2_ms`: antigüedad al asociarla a esa muestra.
- `gps_nivel_senal`: nivel reportado por DJI.

Las filas retenidas (~10 Hz) conservan el GPS y metadatos de su muestra original;
no se combina CO₂ antiguo con nuevas coordenadas. Los CSV anteriores se exportan
sin modificar encabezados ni contenido. Bluetooth incorpora el objeto opcional
`aircraft_position` descrito en `integration/BLUETOOTH.md`.

Prueba en equipo pendiente: contrastar coordenadas con Pilot 2; verificar posición
estacionaria, pérdida de señal, desconexión/reconexión, continuidad de CO₂ sin GPS,
CSV con y sin coordenadas, y funcionamiento con MicroGas en segundo plano.

Referencias DJI: [posición y señal](https://developer.dji.com/api-reference-v5/Components/IKeyManager/Key_FlightController_FlightControllerKey.html),
[consulta asíncrona frente a caché](https://developer.dji.com/api-reference-v5/android-api/Components/IKeyManager/IKeyManager.html).

Validación de la integración GPS: 37 pruebas unitarias aprobadas; lint de demo
y DJI sin errores (11 y 10 advertencias respectivamente). APK DJI Debug y Release
generados y firmas verificadas. Release usa la firma de desarrollo según la
configuración actual del proyecto. Pendiente validar telemetría real con el dron
y convivencia con Pilot 2; no se instaló una actualización en el dispositivo.

## Historial de Actualizaciones (Updates)

### v0.4.0 (2026-09-29)
Ruta y altura implementadas: consultar el [historial principal](../README.md#historial-de-actualizaciones-updates).
Demo genera latitud, longitud y altura desde cero; DJI conserva la altura cruda del SDK (referencia pendiente). Bluetooth añade `aircraft_position.altitude_m` y los CSV agregan `altura_m` al final. El receptor ofrece la pestaña **Ruta y altura** con zoom, paneo, recentrado, inspección de muestras y perfil de altura. No se inventan coordenadas en el receptor si faltan en el mensaje.

### v0.3.1 (2026-09-28)
* **Código de colores por umbral en monitor y overlay**: Integración de `Co2Appearance` para pintar el texto de lectura según cercanía al umbral (blanco normal, amarillo al 70%, rojo por encima del 90% y parpadeo en alarma al 100%).
* **Sincronización del Widget Flotante**: Actualización del texto del overlay para adoptar de inmediato el color de proximidad al umbral de CO₂.
* **Persistencia del Umbral**: Ajustes en `SharedPreferences` para actualización reactiva en UI y servicio en primer plano.

### v0.3.0 (2026-09-25)
* **Soporte Multisensor SEN66**: Integración del protocolo de 64 bytes para leer CO₂, temperatura, humedad, 4 fracciones de material particulado (PM1, PM2.5, PM4, PM10) y 2 índices de gases (VOC y NOx).
* **Streaming Bluetooth con SEN66**: Expansión del payload NDJSON v1 emitido vía Bluetooth RFCOMM con todos los nuevos sensores ambientales.
* **Esquema CSV Expandido**: Incorporación de las columnas de SEN66 manteniendo compatibilidad con lectores anteriores.

### v0.2.0 (2026-09-23)
* **Integración GNSS con DJI Mobile SDK v5**: Conexión por hardware a `KeyAircraftLocation3D` y `KeyGPSSignalLevel` con asociación a muestras de gas.
* **Ventana Flotante sobre DJI Pilot 2**: Servicio en primer plano `MonitorService` con vista minimizable/arrastrable.
* **Servidor Bluetooth RFCOMM**: Emisión inalámbrica con UUID fijo para consumo por estaciones en tierra.

### v0.1.0 (2026-09-18)
* **Lanzamiento Base**: Monitor horizontal en Compose, simulación de CO₂, persistencia CSV con `fsync` y alarmas sonoras de umbral.


## Incorporación y compilación de ambas variantes

Ubicación actual: `GasLab/MicroGas/app_dji/`. El receptor es su hermano `../app_receptor/`, no una subcarpeta de este proyecto. Abre cada proyecto Gradle por separado.

Desde esta carpeta, con JDK 17 y SDK Platform 35:

```bash
./gradlew :app:testDemoDebugUnitTest :app:testDjiDebugUnitTest
./gradlew :app:assembleDemoDebug :app:assembleDjiDebug
```

La primera ejecución necesita descargar dependencias; usa `--offline` únicamente después de completar esa descarga. Si aparecen referencias no resueltas a funciones que sí existen después de mover el proyecto, vuelve a sincronizar y prueba con `-Pkotlin.incremental=false`.

Para DJI, configura `dji.msdk.apiKey` en `local.properties` o `DJI_MSDK_API_KEY` en el entorno; la variable de entorno tiene prioridad. La clave debe corresponder a `com.gaslab.microgas`. La compilación puede terminar sin clave, pero la recepción real no se inicia. Demo no necesita clave. No distribuyas `local.properties` como configuración portable.

`assembleDjiDebug` produce `app/build/outputs/apk/dji/debug/app-dji-debug.apk`; `assembleDemoDebug`, `app/build/outputs/apk/demo/debug/app-demo-debug.apk`. En este proyecto Release usa actualmente la firma de depuración; no equivale a una entrega de producción.

La pantalla DJI y Demo contiene una **tabla**, no las nueve gráficas táctiles del receptor. La barra de CO₂ es relativa al máximo observado; su tamaño no representa el porcentaje del umbral. Pausar vista conserva adquisición, pitidos, Bluetooth y CSV; el texto congelado no parpadea como lectura en vivo.

Sigue la [prueba guiada y el mapa de archivos del manual](../MANUAL_REPLICACION_DESDE_CERO.md). Para analizar los CSV exportados utiliza [el graficador Python](../../graficacionCompleta/README.md).
