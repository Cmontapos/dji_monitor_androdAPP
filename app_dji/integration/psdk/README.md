# Enlace MicroGas Pi → control

**Actualización 0.3.0:** para nueve variables usar `MicroGas_SendMeasurement` y
el [contrato v2 de 64 bytes](../SEN66_V2.md). `MicroGas_SendCo2` y el formato v1
descritos a continuación se conservan para compatibilidad. El test C imprime
primero la referencia v1 y después la v2.

## Lo encontrado en el repositorio proporcionado

`module_sample/widget/test_widget.c`, función `DjiTest_WidgetTask`, construye
un texto con hora del sistema y cuatro mensajes de log, y llama a
`DjiWidgetFloatingWindow_ShowMessage()` cada 200 ms. Esa API presenta texto en
Pilot 2; no define nuestro mensaje de mediciones para `PayloadDataListener`.
No se encontró el código específico SEN66/CO₂ en `widget` ni `flight_control`.
`module_sample/data_transmission/test_data_transmission.c` sí muestra el canal
que necesitamos: `DjiLowSpeedDataChannel_SendData()` hacia
`DJI_CHANNEL_ADDRESS_MASTER_RC_APP`.

Este directorio contiene un emisor nuevo, aún no conectado al lector real de
sensores. No se modificó el repositorio externo Payload-SDK.

## Integración del emisor

1. Añadir `microgas_sender.c/.h` al proyecto de la Pi. Requiere float IEEE-754 de
   32 bits (como la Pi), C11 y las cabeceras/biblioteca PSDK de ese proyecto.
2. Tras `DjiCore_Init`, inicializar `DjiLowSpeedDataChannel_Init` una sola vez.
   Si el módulo de transmisión ya lo hace, reutilizar esa inicialización.
3. Generar `boot_id` aleatorio de 32 bits al iniciar el emisor y mantenerlo fijo
   durante esa ejecución. No usar una constante ni regenerarlo en cada lectura.
4. Después de cada nueva lectura SEN66 completamente válida, incrementar
   `sequence` y llamar:

```c
T_DjiReturnCode result = MicroGas_SendCo2(boot_id, sequence, sample_uptime_ms, co2_ppm);
```

`sample_uptime_ms` es tiempo monotónico de adquisición desde el arranque. No es UTC.
La secuencia aumenta por muestra física, aunque el CO₂ sea idéntico. Un reenvío
conserva secuencia, tiempo y valor. Si no hay dato nuevo o falla CRC del sensor,
no se envía otra medición. Comprobar el resultado del envío y el estado de canal;
no hacer reintentos ilimitados ni bloquear callbacks de PSDK. El envío no garantiza
que el control haya recibido la muestra: este protocolo no incluye ACK/retransmisión.
El registro local del Pi sigue siendo necesario para recuperar datos que no llegaron.

Se puede mantener la ventana flotante de Pilot 2 como otra salida, pero no usarla
como sustituto del envío de paquetes. Evitar mezclar mensajes de prueba DJI en el
mismo canal: MicroGas los rechaza.

## Mensaje binario v1 — 32 bytes, little endian

| Offset | Tipo | Contenido |
|---|---|---|
| 0 | 4 bytes | ASCII `MGAS` |
| 4 | uint8 | versión = 1 |
| 5 | uint8 | flags = 1 (CO₂ válido) |
| 6 | uint16 | reservado = 0 |
| 8 | uint32 | boot_id |
| 12 | uint32 | secuencia |
| 16 | uint64 | tiempo de adquisición monotónico en ms (máximo INT64_MAX) |
| 24 | float32 | CO₂ ppm, finito y no negativo |
| 28 | uint32 | CRC-32/ISO-HDLC de bytes 0–27 |

CRC reflejado: polinomio 0xEDB88320, inicio/final XOR 0xFFFFFFFF, compatible con
`java.util.zip.CRC32`. Este CRC es del paquete; no reemplaza el CRC I2C del SEN66.
Un envío contiene exactamente un paquete de 32 bytes. No requiere fragmentación
(el encabezado PSDK revisado indica fragmentación sobre 128 bytes). MicroGas
rechaza tamaños distintos, versiones desconocidas, CRC incorrectos, duplicados
y secuencias antiguas. Admite wrap de uint32 y reinicio con boot_id distinto.

El receptor usa `PayloadCenter` → puerto `UP` → `addPayloadDataListener`, siguiendo
el puerto predeterminado del ejemplo DJI. Confirmar ese puerto con el payload real.
No interpreta texto libre ni los antiguos 12 bytes propuestos en contexto_app.md.
No se ha probado aún el enlace físico ni la recepción con este firmware.

## Comprobación local del serializador C

Ejecuta desde `GasLab/MicroGas/app_dji/`. Define `PSDK_DIR` con la ruta de tu copia externa de Payload-SDK, por ejemplo `export PSDK_DIR="/ruta/a/Payload-SDK"`. Ese SDK no está incluido en esta carpeta; confirma que existe `psdk_lib/include/dji_low_speed_data_channel.h`.

```sh
cc -std=c11 -Wall -Wextra -Werror \
  -I "$PSDK_DIR/psdk_lib/include" \
  integration/psdk/microgas_sender.c integration/psdk/test_sender.c \
  -o /tmp/microgas-test-sender
/tmp/microgas-test-sender
```

La función de envío está sustituida por un stub en esta prueba: no transmite al
dron. Produce el mismo paquete de referencia que valida `PayloadProtocolTest`:

```
4d474153010100002a0000000700000040e201000000000000a0224419286167
```

## Qué falta para replicar el hardware

El serializador no es un firmware completo ni un driver SEN66. El integrador debe aportar el lector I2C, validar sus lecturas y CRC, configurar el proyecto PSDK para su adaptador y arrancar el envío en la Pi. Una trama válida en la prueba local no demuestra que llegue al control. Registra por separado lectura del sensor, resultado del envío y aceptación en Android.

Para v2 utiliza `MicroGas_SendMeasurement`; el ejemplo `MicroGas_SendCo2` anterior transmite solo el contrato legado v1. Mantén el mismo `boot_id` durante la ejecución e incrementa la secuencia solamente por una lectura nueva. El CRC del paquete y el CRC del sensor verifican tramos distintos.

Consulta el [manual de replicación](../../../MANUAL_REPLICACION_DESDE_CERO.md) para la secuencia de pruebas. No deduzcas tensiones, pinout o alimentación de este serializador: esas decisiones requieren documentación del sensor, la placa adaptadora y el equipo real.
