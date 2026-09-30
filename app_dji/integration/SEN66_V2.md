# SEN66: contrato de nueve variables y prueba sin dron

Implementado en las apps 0.3.0. El lector I2C real de la Pi todavía debe integrarse;
`psdk/microgas_sender.c` contiene el serializador de referencia. No se envía un
struct C directamente: se serializan los campos para evitar padding y diferencias
de endianness. No se cambian el canal PSDK, el UUID Bluetooth ni la alarma de CO₂.

## Pi → control: un paquete de 64 bytes

Todos los enteros y float32 IEEE-754 son **little endian**.

| Offset | Tamaño | Campo |
|---|---|---|
| 0 | 4 | ASCII `MGAS` |
| 4 | 1 | Versión `2` |
| 5 | 1 | Flags `1`: CO₂ válido y obligatorio |
| 6 | 2 | Reservado `0` |
| 8 | 4 | `boot_id`, uint32, cambia al reiniciar emisor |
| 12 | 4 | `sequence`, uint32, aumenta por muestra nueva, admite wrap |
| 16 | 8 | `acquired_uptime_ms`, uint64, máximo INT64_MAX |
| 24 | 4 | CO₂ ppm |
| 28 | 4 | Temperatura °C |
| 32 | 4 | Humedad % HR |
| 36 | 4 | PM1 µg/m³ |
| 40 | 4 | PM2.5 µg/m³ |
| 44 | 4 | PM4 µg/m³ |
| 48 | 4 | PM10 µg/m³ |
| 52 | 4 | Índice VOC |
| 56 | 4 | Índice NOx |
| 60 | 4 | CRC32 de bytes **0–59** |

Los bytes 4–5 son `02 01`: se separan **versión** y **flags**, aunque juntos
representen 0x0102 al leerlos como uint16 LE. CRC-32/ISO-HDLC reflejado:
polinomio 0xEDB88320, inicio/final XOR 0xFFFFFFFF, igual a `java.util.zip.CRC32`.

CO₂ debe ser finito y no negativo. Sin CO₂ válido, no se emite una muestra.
Los ocho canales adicionales pueden ser **NaN** para representar “sin dato”.
No enviar lecturas de calentamiento como si fueran mediciones válidas. El
serializador y las apps convierten canales opcionales inválidos a ausentes:
temperatura admite finitos negativos, humedad 0–100, PM finito no negativo,
índices VOC/NOx 1–500. Cero en un índice representa ausencia, no una concentración.
Un canal opcional inválido no elimina el CO₂ ni los demás canales válidos.

Se mantiene soporte para v1: 32 bytes, `01 01`, CO₂ en offset 24 y CRC en 28.
Los campos adicionales de v1 quedan vacíos. Tamaño/versión desconocidos o CRC
incorrecto descartan el paquete sin avanzar la secuencia del decodificador.
No mezclar paquetes distintos con la misma secuencia: cada muestra tiene una
única identidad `(boot_id, sequence)`.

Ejemplo de integración futura tras leer y validar el sensor:

```c
MicroGasMeasurement sample = {
    .co2_ppm = co2,
    .temperature_c = temperature,
    .humidity_pct = humidity,
    .pm1_0 = pm1,
    .pm2_5 = pm25,
    .pm4_0 = pm4,
    .pm10 = pm10,
    .voc_index = voc_ready ? voc_index : NAN,
    .nox_index = nox_ready ? nox_index : NAN,
};
T_DjiReturnCode result = MicroGas_SendMeasurement(boot_id, sequence, uptime_ms, &sample);
```

Incluir `<math.h>` para `NAN`, inicializar el canal PSDK una sola vez y comprobar
`result`. Una llamada por lectura nueva (~1 Hz); no incrementar secuencia para
reenvíos. La adaptación del driver I2C y el despliegue a la Pi quedan pendientes.

## Control → teléfono: extensión compatible de NDJSON v1

Se mantiene `v:1` porque se añaden campos opcionales; la versión del paquete
binario es independiente. Nuevas claves:
`temperature_c`, `humidity_pct`, `pm1_0`, `pm2_5`, `pm4_0`, `pm10`, `voc_index`,
`nox_index`. Valores JSON numéricos con punto o `null`, nunca NaN/Infinity.
Los clientes antiguos pueden ignorar las claves adicionales. Un teléfono nuevo
acepta el emisor antiguo y muestra «—» para los campos ausentes.

`aircraft_position` permanece independiente. La demo tiene origen `simulado`,
sin GPS ni metadatos ficticios de payload. Los índices VOC/NOx no son ppm.

## CSV e interfaz

El CSV del control conserva sus primeras 15 columnas y agrega:
`temperatura_c,humedad_pct,pm1_0,pm2_5,pm4_0,pm10,voc_index,nox_index` (23 en total).
El zero-order hold a ~10 Hz repite todos los valores de la misma muestra, con
`sen66_new_data` distinguiendo la primera fila de las retenidas.

El teléfono conserva sus primeras 9 columnas y añade:
`pm1_0,pm2_5,pm4_0,pm10,voc_index,nox_index` (15 en total). El mismo formato se usa
para exportación del histórico y respaldo automático de cada muestra recibida.
Los CSV antiguos se exportan sin reescribirlos. Ausencias son celdas vacías.

El control muestra las nueve variables en su tabla desplazable. El teléfono
incluye valores actuales, nueve gráficas en Histórico con zoom/pan y tabla de
nueve variables más hora/GPS. La alarma continúa siendo exclusivamente de CO₂.

## Prueba sin dron con dos dispositivos Android

1. Instalar **MicroGas Simulado** en un Android 10+ (puede ser el control o un
   segundo teléfono). No usar MicroGas DJI como simulador: DJI requiere el dron.
2. Instalar **MicroGas Receptor** en otro teléfono Android 8+.
3. Emparejar los dos dispositivos desde ajustes y conceder permisos Bluetooth.
4. En Simulado, pulsar **Segundo plano** para iniciar adquisición, CSV y servidor
   Bluetooth. No requiere permiso de overlay para ese botón. Dejar solo un
   servidor MicroGas activo en ese dispositivo; detener DJI si estaba funcionando.
5. En Receptor, seleccionar el dispositivo emisor. Comprobar `SIMULADO`, las
   nueve variables y sus curvas cambiantes. GPS queda vacío en ambos.
6. Exportar CSV en ambos equipos. Comparar valores de muestras nuevas; el control
   tiene filas retenidas a ~10 Hz y el teléfono una fila por muestra (~1 Hz), por
   lo que no deben tener la misma cantidad de filas. `origin`/`origen` es simulado.
7. Fijar temporalmente umbral de CO₂ en **700 ppm** en ambos: la demo oscila entre
   aproximadamente 490 y 810 ppm. Comprobar activación/desactivación de alertas y
   restaurar después el umbral deseado.
8. Detener/reiniciar Simulado: verificar reconexión, contador de reinicios del
   emisor, continuidad del CSV del teléfono y ausencia de datos inventados.
9. Cerrar y volver a abrir Receptor: sus CSV anteriores siguen en Archivos CSV,
   aunque el histórico de gráficas en memoria se haya perdido.

Desde `GasLab/MicroGas/app_dji`:

```sh
./gradlew :app:assembleDemoDebug :app:assembleDjiDebug
../app_receptor/gradlew -p ../app_receptor :app:assembleDebug
```

APK emisor simulado: `app/build/outputs/apk/demo/debug/app-demo-debug.apk`.
APK emisor real: `app/build/outputs/apk/dji/debug/app-dji-debug.apk`.
APK receptor: `../app_receptor/app/build/outputs/apk/debug/app-debug.apk`.

La prueba sin dron valida el tramo simulado → Bluetooth → teléfono y los CSV.
No valida I2C, PSDK físico, telemetría real ni coexistencia con Pilot 2. Los 64
bytes son el tamaño de nuestra carga útil; no constituyen una medición del uso
real del enlace ni una garantía de ausencia de interferencia operacional.

## Verificación automatizada del contrato

`integration/psdk/test_sender.c` genera las tramas C v1 y v2 usando un stub PSDK.
La trama v2 coincide con `app/src/test/resources/sen66-v2.hex`. El test Android
la decodifica y compara el NDJSON exacto con `sen66-v2.ndjson`. El receptor tiene
una copia idéntica de esa referencia para probar lectura fragmentada y CSV
persistente. Se prueba también compatibilidad antigua, CRC, nulos, secuencias,
locale, simulación y retención de las variables en el CSV del control.


Validación 0.3.0: 43 pruebas aprobadas en Demo, 43 en DJI y 23 en Receptor.
Lint: cero errores; 11 advertencias en Demo, 10 en DJI y 4 en Receptor.
Serializador C compilado con `-Wall -Wextra -Werror`; las referencias binarias y
JSON coinciden entre proyectos. Los tres APK Debug 0.3.0 se generaron y sus firmas
se verificaron. Sin dispositivos ADB conectados; la prueba física queda pendiente.
