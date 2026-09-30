# MicroGas → app Android receptora

Desde 0.3.0 se transmiten las nueve variables opcionales del SEN66; ver el
[contrato y prueba sin dron](SEN66_V2.md). Se mantiene `v:1` en JSON.

MicroGas actúa como servidor Bluetooth clásico RFCOMM autenticado. La app [MicroGas Receptor](../../app_receptor/README.md) implementa el cliente Android.

1. Emparejar control y teléfono desde los ajustes Bluetooth de Android.
2. Iniciar MicroGas con «Iniciar pop-up» o «Segundo plano» y conceder permisos.
3. El teléfono abre `createRfcommSocketToServiceRecord` con UUID
   `bb239920-bdbc-4d51-8a12-a5351875d891`, conecta y lee el flujo UTF-8.
4. Separar por salto de línea LF: cada línea es un objeto JSON completo.
   Las lecturas de un socket pueden contener líneas parciales o varias juntas.

Nombre SDP: `MicroGas`. Un cliente a la vez. Sin descubrimiento desde MicroGas.
No se envían filas CSV retenidas, únicamente muestras nuevas válidas.

Ejemplo de protocolo v1:

```json
{"v":1,"session":"550e8400-e29b-41d4-a716-446655440000","sequence":15,"received_at_ms":1790000000123,"co2_ppm":3000.500,"origin":"dji","sender_boot":4,"sender_sequence":99,"acquired_uptime_ms":1000}
```

- `session`: UUID del emisor Bluetooth, cambia al reiniciar el servicio.
- `sequence`: secuencia local de muestras; avanza aunque no haya cliente.
- `received_at_ms`: recepción en el control, epoch UTC en milisegundos.
- `origin`: `dji` o `simulado`; la receptora debe mostrarlo claramente.
- Los tres campos `sender_*`/`acquired_uptime_ms` son metadatos del payload;
  son `null` en simulación.

La cola conserva solo la muestra más reciente si el receptor es lento. Se
omiten paquetes que esperaron más de cinco segundos; una escritura bloqueada
se corta cerrando el socket a los tres segundos. No hay recuperación de muestras
perdidas por Bluetooth ni ACK de aplicación: «enviando» significa que la escritura
local terminó, no que la app receptora guardó el dato. El CSV local es independiente.
La receptora debe detectar huecos, cambios de sesión y ausencia de nuevas líneas.
La reconexión la inicia el cliente; MicroGas vuelve a escuchar automáticamente.

## Prueba manual pendiente

- Probar Android 10 del control y permisos Bluetooth de Android 12+ en otro equipo.
- Comprobar que la ventana se arrastra y minimiza, permite usar Pilot 2 fuera de
  sus límites y muestra RX/BT en modo compacto. Probar negar permiso flotante.
- Abrir Pilot 2 y verificar secuencias nuevas y CSV mientras MicroGas está oculta.
- Bajar temporalmente el umbral en demo a 700 ppm para oír pitidos al cruzarlo;
  devolverlo a 3000. Confirmar volumen de alarmas del dispositivo.
- En DJI, enviar 2999, 3000 y 3001 ppm; interrumpir el emisor más de cinco segundos:
  debe indicar datos desactualizados y detener pitidos. Restablecer emisión.
- Con cliente RFCOMM, verificar contenido, modo simulado, desconexión, receptor
  lento y reconexión. Apagar/encender Bluetooth y revocar permiso.
- Detener desde app/notificación: deben cesar pitidos, CSV, adquisición y Bluetooth.
  Iniciar otra vez y comprobar recepción sin dos conexiones MSDK simultáneas.

Referencias: [RFCOMM Android](https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices)
y [servicio connectedDevice](https://developer.android.com/develop/background-work/services/fgs/service-types).

## Extensión opcional v1: posición del dron

Los campos existentes conservan significado. Se agrega `aircraft_position`,
`null` cuando no hay posición válida (siempre `null` en demo). Un receptor debe
aceptar tanto su ausencia en versiones anteriores como `null`, e ignorar campos
adicionales desconocidos. Cuando hay posición:

```json
{"latitude":9.123456,"longitude":-84.987654,"source":"dji_aircraft_msdk","received_at_ms":1790000000000,"age_at_sample_ms":123,"signal_level":4}
```

Es el GPS del dron consultado por MicroGas DJI en el control, no la ubicación
Android del control. La edad se congela al recibir la muestra de CO₂. Las respuestas
GPS de más de cinco segundos se descartan. `received_at_ms` es la recepción local
de la posición y no un tiempo GNSS del sensor. No hay sincronización con la hora
de adquisición de la Pi. Sin GPS se siguen enviando las muestras de CO₂.

## Extensión v0.4.0: ruta y altura

El objeto opcional `aircraft_position` incorpora `altitude_m` (número finito en metros o null). Los receptores aceptan mensajes anteriores sin este campo. DJI conserva `KeyAircraftLocation3D.altitude` sin conversión ni puesta a cero; no se declara datum vertical hasta validarlo. Demo genera altura de 0 a 60 m desde cero y coordenadas sintéticas. `source` vale `simulado` en demo y `dji_aircraft_msdk` en DJI. La altura comparte frescura y asociación de muestra con las coordenadas; no se sustituye un dato ausente por cero. Ambos CSV añaden `altura_m` al final, manteniendo las columnas anteriores.
