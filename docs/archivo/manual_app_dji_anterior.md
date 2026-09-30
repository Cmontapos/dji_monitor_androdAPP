> ARCHIVO HISTÓRICO: contiene instrucciones desactualizadas. No usar para montar o compilar. Consulte [el manual vigente](../../MANUAL_REPLICACION_DESDE_CERO.md).

# Manual Completo de Replicación: Sistema MicroGas (Control DJI + App Celular)

**GasLab — Monitoreo de Gases Atmosféricos con Dron DJI Mavic 3T**  
*Versión 0.3.0 — Sistema Integral con Soporte para las 9 Variables del SEN66*

---

## Índice

1. [Visión General del Sistema y Arquitectura de Hardware](#1-visión-general-del-sistema-y-arquitectura-de-hardware)
2. [Variables Físicas del Sensor SEN66 y Exclusión de SO₂](#2-variables-físicas-del-sensor-sen66-y-exclusión-de-so₂)
3. [Protocolos de Comunicación y Estructura de Datos](#3-protocolos-de-comunicación-y-estructura-de-datos)
   - 3.1. Pi 5 → Mavic 3T → RC Pro (PSDK v1 de 32 B y v2 de 64 B)
   - 3.2. RC Pro → Celular (Bluetooth Clásico RFCOMM / NDJSON v1)
   - 3.3. Estructura de Archivos CSV (23 Columnas en Control, 15 Columnas en Celular)
   - 3.4. Análisis de Viabilidad Técnica y Ausencia de Conflictos
4. [App 1: MicroGas para DJI RC Pro Enterprise (`app_dji`)](#4-app-1-microgas-para-dji-rc-pro-enterprise-app_dji)
   - 4.1. Requisitos y Herramientas de Desarrollo
   - 4.2. Configuración de Gradle, Dependencias y Flavors (`demo` y `dji`)
   - 4.3. AndroidManifest y Permisos Específicos
   - 4.4. Componentes Clave y Arquitectura de Software
   - 4.5. Pasos de Compilación, Instalación y Variantes de APK
5. [App 2: MicroGas Receptor para Celular Android (`app_receptor`)](#5-app-2-microgas-receptor-para-celular-android-app_receptor)
   - 5.1. Requisitos y Herramientas de Desarrollo
   - 5.2. Configuración de Gradle y Dependencias (Sin SDK DJI)
   - 5.3. AndroidManifest y Permisos de Bluetooth
   - 5.4. Componentes Clave y Arquitectura de Software
   - 5.5. Interfaz de Usuario: Monitor e Histórico (Control de Zoom en Eje X)
   - 5.6. Pasos de Compilación, Ubicación de APKs (Debug vs Release)
6. [Guía de Integración y Prueba Extremo a Extremo](#6-guía-de-integración-y-prueba-extremo-a-extremo)
   - 6.1. Prueba Local sin Hardware (Modo Simulado / Demo)
   - 6.2. Prueba en Banco de Trabajo con Hardware Real
   - 6.3. Convivencia con DJI Pilot 2 en Vuelo
7. [Resolución de Problemas Frecuentes (Troubleshooting)](#7-resolución-de-problemas-frecuentes-troubleshooting)

---

## 1. Visión General del Sistema y Arquitectura de Hardware

El sistema **MicroGas** adquiere, visualiza, georreferencia y registra concentraciones de gases y aerosoles en tiempo real mediante una carga útil instalada en un dron DJI Mavic 3 Enterprise / Thermal (Mavic 3T).

El ecosistema está compuesto por dos aplicaciones sincronizadas:
1. **Control DJI RC Pro Enterprise:** corre la app `MicroGas`, la cual procesa los paquetes nativos de la Raspberry Pi 5 vía DJI Mobile SDK V5 (MSDK), añade las coordenadas GNSS de alta precisión del dron, registra un archivo CSV sincronizado a 10 Hz (Zero-Order Hold), proporciona una ventana flotante (*overlay*) sobre DJI Pilot 2 y retransmite los datos vía **Bluetooth RFCOMM como servidor**.
2. **Celular Android (Estación de Monitoreo Terrestre):** corre la app `MicroGas Receptor`, la cual se conecta como **cliente Bluetooth clásico RFCOMM** al control, grafica en tiempo real las concentraciones, almacena el histórico multivariable (con zoom y desplazamiento horizontal), permite exportar CSV mediante Storage Access Framework (SAF) y emite alertas sonoras/vibratorias por superación de umbrales.

### Diagrama de Enlace Físico

```text
┌────────────────────────────────────────────────────────┐
│             Sensor Sensirion SEN66                    │
│   (CO₂, Temp, Humedad, PM1/2.5/4/10, VOC, NOx)         │
└───────────────────────────┬────────────────────────────┘
                            │ I2C (~1 Hz)
                            ▼
┌────────────────────────────────────────────────────────┐
│             Raspberry Pi 5                             │
│   (Empaqueta trama binaria C/PSDK)                     │
└───────────────────────────┬────────────────────────────┘
                            │ UART / Conector E-Port V1
                            ▼
┌────────────────────────────────────────────────────────┐
│             DJI Mavic 3T                               │
└───────────────────────────┬────────────────────────────┘
                            │ Enlace O3 Enterprise (Inalámbrico)
                            ▼
┌────────────────────────────────────────────────────────┐
│             DJI RC Pro Enterprise (Android 10)         │
│  - DJI Pilot 2 (Navegación y cámaras térmica/RGB)      │
│  - App MicroGas:                                       │
│      * MSDK V5 Listener + Polling GPS Dron             │
│      * CsvJournal local (23 columnas con ZOH a 10 Hz)  │
│      * FloatingMonitor (Overlay arrastrable)           │
│      * Servidor Bluetooth RFCOMM (bb239920-...)        │
└───────────────────────────┬────────────────────────────┘
                            │ Bluetooth Clásico RFCOMM (SPP)
                            │ NDJSON UTF-8 (~1 Hz)
                            ▼
┌────────────────────────────────────────────────────────┐
│             Celular Android (Android 8.0+)             │
│  - App MicroGas Receptor:                              │
│      * Cliente RFCOMM con reconexión automática        │
│      * Pestaña 1: Monitor CO₂ en tiempo real           │
│      * Pestaña 2: Histórico (CO₂, Temp, Hum, PM...)    │
│      * Respaldo local y Exportación CSV (15 columnas)  │
└────────────────────────────────────────────────────────┘
```

---

## 2. Variables Físicas del Sensor SEN66 y Exclusión de SO₂

> [!IMPORTANT]
> **El sensor físico instalado es un Sensirion SEN66.**
> El SEN66 mide exactamente **9 variables ambientales**:
> 1. Concentración de dióxido de carbono: **CO₂** (ppm)
> 2. **Temperatura ambiente** (°C)
> 3. **Humedad relativa** (% HR)
> 4. Material particulado fino: **PM1.0** ($\mu\text{g/m}^3$)
> 5. Material particulado: **PM2.5** ($\mu\text{g/m}^3$)
> 6. Material particulado: **PM4.0** ($\mu\text{g/m}^3$)
> 7. Material particulado grueso: **PM10** ($\mu\text{g/m}^3$)
> 8. Índice de compuestos orgánicos volátiles: **VOC Index** (escala 1–500)
> 9. Índice de óxidos de nitrógeno: **NOx Index** (escala 1–500)
> 
> **El SEN66 NO mide dióxido de azufre (SO₂).**  
> Por este motivo, ni la aplicación del control ni la del celular incluyen SO₂ en interfaces, modelos, bases de datos ni archivos CSV.

---

## 3. Protocolos de Comunicación y Estructura de Datos

### 3.1. Pi 5 → Mavic 3T → RC Pro (PSDK v1 y v2)

La app del control soporta de manera transparente ambas versiones del protocolo binario PSDK recibido en `PayloadIndexType.UP`.

#### Protocolo v2 de 64 Bytes (Versión 0.3.0 — Nueve Variables)
Todos los campos enteros y float32 IEEE-754 usan orden **Little-Endian**:

| Offset | Tamaño | Tipo | Campo | Descripción |
|:------:|:------:|:----:|:------|:------------|
| 0–3 | 4 B | ASCII | `magic` | Valor constante `MGAS` (`0x4D, 0x47, 0x41, 0x53`) |
| 4 | 1 B | uint8 | `version` | Versión mayor del protocolo: `2` (`0x02`) |
| 5 | 1 B | uint8 | `flags` | Flags: `1` (CO₂ obligatorio y válido) |
| 6–7 | 2 B | uint16 | `reserved`| Relleno reservado: `0x0000` |
| 8–11 | 4 B | uint32 | `boot_id` | Identificador único generado en cada reinicio del emisor |
| 12–15 | 4 B | uint32 | `sequence`| Contador monotónico de muestras (admite rollover) |
| 16–23 | 8 B | int64 | `uptime` | Milisegundos monotónicos de funcionamiento de la Pi |
| 24–27 | 4 B | float32 | `co2_ppm` | Concentración de CO₂ (ppm) |
| 28–31 | 4 B | float32 | `temperature_c` | Temperatura ambiente (°C) o `NaN` si no está disponible |
| 32–35 | 4 B | float32 | `humidity_pct` | Humedad relativa (% HR, 0–100) o `NaN` |
| 36–39 | 4 B | float32 | `pm1_0` | Masa PM1.0 ($\mu\text{g/m}^3$) o `NaN` |
| 40–43 | 4 B | float32 | `pm2_5` | Masa PM2.5 ($\mu\text{g/m}^3$) o `NaN` |
| 44–47 | 4 B | float32 | `pm4_0` | Masa PM4.0 ($\mu\text{g/m}^3$) o `NaN` |
| 48–51 | 4 B | float32 | `pm10` | Masa PM10 ($\mu\text{g/m}^3$) o `NaN` |
| 52–55 | 4 B | float32 | `voc_index` | Índice VOC (1–500) o `NaN` |
| 56–59 | 4 B | float32 | `nox_index` | Índice NOx (1–500) o `NaN` |
| 60–63 | 4 B | uint32 | `crc32` | CRC32 estándar (polinomio 0xEDB88320) sobre bytes **0–59** |

#### Protocolo Legado v1 de 32 Bytes
- Se conserva retrocompatibilidad total: si el paquete entrante mide 32 bytes y su versión es `1` (`bytes[4] == 1`), se extrae `co2_ppm` en el offset 24 y el CRC32 en el offset 28. Los 8 canales opcionales se completan automáticamente como `null`.

---

### 3.2. RC Pro → Celular (Bluetooth Clásico RFCOMM / NDJSON v1)

El control ejecuta un servidor RFCOMM con Serial Port Profile (SPP):
- **UUID:** `bb239920-bdbc-4d51-8a12-a5351875d891`
- **Nombre de Servicio:** `MicroGas`
- **Formato:** NDJSON (línea JSON UTF-8 terminada en `\n` por cada muestra física nueva a ~1 Hz).
- **Control de Congestión:** Canal conflado (`Channel<Pair<Long, ByteArray>>(CONFLATED)`). Si el celular es lento procesando, se descartan muestras intermedias en la cola para no generar latencia. Muestras en cola > 5 s se descartan automáticamente.

#### Ejemplo de Paquete JSON Transmitido
```json
{
  "v": 1,
  "session": "550e8400-e29b-41d4-a716-446655440000",
  "sequence": 15,
  "received_at_ms": 1790000000123,
  "co2_ppm": 650.250,
  "temperature_c": 24.310,
  "humidity_pct": 58.400,
  "pm1_0": 4.200,
  "pm2_5": 7.150,
  "pm4_0": 9.800,
  "pm10": 11.300,
  "voc_index": 115.000,
  "nox_index": 1.000,
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

---

### 3.3. Estructura de Archivos CSV

#### CSV del Control DJI (`app_dji` — 23 Columnas)
El control registra en `files/sessions/` con frecuencia desacoplada de 10 Hz (Zero-Order Hold):
```csv
fecha_hora_utc,co2_ppm,sen66_new_data,sen66_muestra_utc,sen66_secuencia,latitud,longitud,origen,payload_boot,payload_secuencia,payload_uptime_ms,gps_origen,gps_recepcion_utc,gps_edad_al_recibir_co2_ms,gps_nivel_senal,temperatura_c,humedad_pct,pm1_0,pm2_5,pm4_0,pm10,voc_index,nox_index
```
- `sen66_new_data`: `1` si es una muestra nueva que acaba de llegar, `0` si es una fila retenida por el Zero-Order Hold.

#### CSV del Celular Receptor (`app_receptor` — 15 Columnas)
El celular guarda cada muestra física recibida (~1 Hz) y permite exportar mediante Storage Access Framework:
```csv
fecha_hora_utc,co2_ppm,temperatura_c,humedad_pct,pm1_0,pm2_5,pm4_0,pm10,voc_index,nox_index,latitud,longitud,origin,session,sequence
```
- Marcas de tiempo en ISO-8601 UTC con milisegundos (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`).
- Punto decimal forzado con `Locale.US`.

---

### 3.4. Análisis de Viabilidad Técnica y Ausencia de Conflictos

Al transmitir las 9 variables completas del SEN66:
1. **Ancho de banda PSDK / Enlace DJI:** Pasar de 32 a 64 bytes a 1 Hz implica un tráfico de **$0.064\text{ KB/s}$**. El enlace O3 Enterprise del Mavic 3T maneja decenas de megabits por segundo para video en vivo. Es completamente imperceptible y no compite con la cámara térmica ni la telemetría de Pilot 2.
2. **Bluetooth RFCOMM:** El JSON de 9 canales ocupa ~450 bytes. A 1 Hz, consume $0.45\text{ KB/s}$, frente a los más de $200\text{ KB/s}$ que soporta RFCOMM Bluetooth clásico. Cero congestión.
3. **Escritura en Flash:** Escribir 23 columnas a 10 Hz representa menos de $1.5\text{ KB/s}$ en la memoria interna del RC Pro, sin impacto en el rendimiento.
4. **Memoria RAM:** Almacenar 7.200 muestras completas con 9 flotantes ocupa menos de 1 MB de memoria RAM en el celular.

---

## 4. App 1: MicroGas para DJI RC Pro Enterprise (`app_dji`)

### 4.1. Requisitos y Herramientas de Desarrollo

- **IDE:** Android Studio Ladybug (2024.2+) o superior.
- **JDK:** Java 17 (configurado como Gradle JDK).
- **Android SDK:** `compileSdk 35`, `minSdk 29` (Android 10), `targetSdk 34`.
- **Arquitectura NDK:** `arm64-v8a`.
- **Dependencias:** DJI MSDK V5 Aircraft `5.18.0`, Compose BOM `2024.12.01`, Kotlin `2.0.21`.

### 4.2. Configuración de Flavors

El proyecto define dos *Product Flavors*:
- **`demo` (`com.gaslab.microgas.demo`):** Genera las 9 variables de forma sintética (onda sinusoidal a ~1 Hz). No requiere hardware DJI, ni permisos especiales de vuelo, ni App Key. Ideal para pruebas de interfaz y Bluetooth sin encender el dron.
- **`dji` (`com.gaslab.microgas`):** Enlaza con las librerías nativas de DJI MSDK V5, escucha el canal PSDK y lee la posición del dron por telemetría. Requiere App Key en `local.properties`:
  ```properties
  dji.msdk.apiKey=TU_CLAVE_DJI_MSDK_AQUI
  ```

### 4.3. AndroidManifest y Permisos
- `SYSTEM_ALERT_WINDOW`: Para la ventana flotante sobre DJI Pilot 2.
- `FOREGROUND_SERVICE` y `FOREGROUND_SERVICE_CONNECTED_DEVICE`: Adquisición continua en segundo plano.
- `BLUETOOTH_CONNECT`, `BLUETOOTH`, `BLUETOOTH_ADMIN`: Servidor Bluetooth RFCOMM.
- `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`: Consulta de posición GNSS del dron.

### 4.4. Componentes Clave
- `PayloadProtocol.kt`: Decodificador con soporte dual para 32 B (v1) y 64 B (v2) con validación CRC32 y retiro de boots anteriores.
- `SampleHold.kt`: Zero-Order Hold que retiene las 9 variables simultáneamente a 10 Hz.
- `CsvJournal.kt`: Registro append-only de 23 columnas con `RandomAccessFile` y `fd.sync()`.
- `FloatingMonitor.kt`: Ventana flotante compacta arrastrable (`TYPE_APPLICATION_OVERLAY`) sobre DJI Pilot 2 con indicadores `RX` y `BT`.
- `BluetoothRelay.kt`: Servidor RFCOMM con canal conflado y watchdog de socket (3 s).

### 4.5. Pasos de Compilación y Variantes de APK

En Android Studio puedes seleccionar la variante en la pestaña **Build Variants** (abajo a la izquierda):
* **`demoDebug`:** Genera `app/build/outputs/apk/demo/debug/app-demo-debug.apk` (para pruebas en mesa).
* **`djiDebug`:** Genera `app/build/outputs/apk/dji/debug/app-dji-debug.apk` (para vuelo real con el RC Pro).

Desde la terminal:
```bash
cd ~/Documents/GasLab/app_dji

# Compilar simulador demo
./gradlew :app:assembleDemoDebug

# Compilar versión de vuelo real DJI
./gradlew :app:assembleDjiDebug

# Instalar en el control conectado por USB
adb install -r app/build/outputs/apk/demo/debug/app-demo-debug.apk
```

---

## 5. App 2: MicroGas Receptor para Celular Android (`app_receptor`)

### 5.1. Requisitos y Herramientas de Desarrollo

- **Compatibilidad:** Android 8.0 Oreo (API 26) hasta Android 15 (API 35).
- **Cero dependencias DJI:** Proyecto 100% estándar de Android con Jetpack Compose y Material 3.

### 5.2. Componentes Clave
- `BluetoothClient.kt`: Cliente RFCOMM con reconexión progresiva con backoff (5, 10, 20, 30 s), watchdog de 15 s por intento y descarte seguro.
- `NdjsonFramer.kt`: Buffer de tramas UTF-8 con límite de 16 KiB que soporta fragmentación de paquetes en la red.
- `MeasurementParser.kt`: Analizador tolerante a fallos que extrae las 9 variables físicas y las coordenadas GPS.
- `MeasurementRepository.kt`: Buffer en RAM de hasta **7.200 muestras** (~2 horas a 1 Hz), métricas de enlace (pérdida de secuencias, reinicios de sesión) y respaldo automático.
- `AlertManager.kt`: Vibración y notificación visual al cruzar el umbral configurable de CO₂ (ej. 3.000 ppm) con datos frescos (< 5 s).
- `Co2Chart.kt`: Gráfica interactiva de alto rendimiento implementada en Canvas nativo de Compose.

### 5.3. Interfaz de Usuario y Control de Zoom
1. **Pestaña 1 (Monitor en tiempo real):**
   - Tarjeta principal con el valor actual de CO₂ en gran formato, indicador de frescura y origen (`dji` o `SIMULADO`).
   - Selector de ventana de tiempo visible (1, 2, 5, 10, 15, 30 min).
   - Gráfica en vivo de CO₂ vs tiempo.
   - Tarjetas secundarias con Temperatura (°C) y Humedad (% HR).
2. **Pestaña 2 (Histórico):**
   - Selector de ventana de tiempo (1, 2, 5, 10, 15, 30, 60 min o Todo).
   - Gráficas apiladas continuas (CO₂, Temperatura, Humedad).
   - **Control de Zoom en el Eje X:** El zoom táctil (*pinch-to-zoom*) y el desplazamiento (*pan*) se restringen al **eje temporal (X)** entre 1x y 10x con `.coerceIn()`, manteniendo la escala de concentración (Y) en autoajuste automático según los datos visibles.
   - Tabla de datos crudos desplazable horizontal y verticalmente.
   - Botón de exportación a CSV mediante Storage Access Framework (SAF).

### 5.4. Pasos de Compilación y Ubicación de APKs

#### ¿Dónde se encuentra el APK generado?
* **APK Debug:** `app_receptor/app/build/outputs/apk/debug/app-debug.apk`
* **APK Release (firmado):** `app_receptor/app/build/outputs/apk/release/app-release.apk`

Desde la terminal:
```bash
cd ~/Documents/GasLab/app_receptor

# Compilar APK Debug
./gradlew assembleDebug

# Ver el archivo generado
ls -lh app/build/outputs/apk/debug/app-debug.apk

# Instalar en el celular conectado por USB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 6. Guía de Integración y Prueba Extremo a Extremo

### 6.1. Prueba Local sin Hardware (Modo Simulado con 9 Variables)

Permite validar la sincronización completa entre dos dispositivos Android sin dron ni sensores:
1. Instalar `app-demo-debug.apk` en el dispositivo A (o emulador).
2. Instalar `app-debug.apk` en el celular B.
3. Emparejar ambos dispositivos por Bluetooth en los Ajustes del sistema.
4. En el dispositivo A, abrir MicroGas y pulsar **Iniciar pop-up** o **Segundo plano**.
5. En el celular B, abrir MicroGas Receptor y seleccionar el dispositivo A.
6. **Comprobación:**
   - El estado pasa a "Conectado".
   - En la pestaña Monitor se grafica el CO₂ en tiempo real.
   - En la pestaña Histórico, las curvas de CO₂, Temperatura y Humedad se llenan dinámicamente y la tabla registra las 9 variables.
   - Probar exportar el archivo CSV y verificar que contiene las 15 columnas con datos válidos.

---

### 6.2. Prueba en Banco de Trabajo con Hardware Real

1. Conectar el SEN66 a la Raspberry Pi 5 por I2C (pines SDA/SCL, 3.3V y GND).
2. Conectar la Pi 5 al conector E-Port V1 del Mavic 3T mediante UART.
3. Ejecutar en la Pi 5 el programa de adquisición que envía el paquete v2 de 64 bytes por PSDK.
4. En el control DJI RC Pro Enterprise, abrir `MicroGas DJI`. Comprobar la recepción de paquetes `MGAS` y la validez del CRC32.
5. Conectar el celular por Bluetooth al control y confirmar que la telemetría GPS del dron se sincroniza con los gases.

---

### 6.3. Convivencia con DJI Pilot 2 en Vuelo

1. En el RC Pro Enterprise, iniciar MicroGas y pulsar **Iniciar pop-up**.
2. Conceder el permiso "Mostrar sobre otras aplicaciones".
3. Pulsar **Ocultar app** para enviar la aplicación al fondo sin cancelar la adquisición.
4. Abrir **DJI Pilot 2**:
   - La ventana flotante (`FloatingMonitor`) se muestra en una esquina sin bloquear controles de vuelo ni la vista térmica.
   - Los indicadores `RX ✓` y `BT ✓` confirman la llegada de datos de la carga útil y la transmisión hacia el celular.
   - Si la concentración supera el umbral configurado, el fondo del recuadro cambia a rojo y se emiten los pitidos de advertencia.

---

## 7. Resolución de Problemas Frecuentes (Troubleshooting)

### A. Desconexión al apagar la pantalla del celular
- **Causa:** El sistema operativo cierra sockets en segundo plano para ahorrar energía.
- **Solución:** Configurar la app MicroGas Receptor con uso de batería **Sin restricciones** en los Ajustes de Android. La app reconectará automáticamente de forma progresiva (5, 10, 20, 30 s) en cuanto se encienda la pantalla.

### B. Valores opcionales en blanco ("—") o null
- **Causa:** El paquete PSDK recibido es v1 (32 bytes) o el sensor se encuentra en ciclo inicial de calentamiento (*warm-up*).
- **Comportamiento:** La app sigue operando normalmente y grafica el CO₂ sin interrupción.

### C. Error de CRC32 en la recepción
- **Causa:** Desajuste en el algoritmo de cálculo de CRC o en el endianness entre el código C de la Raspberry Pi y Java/Kotlin.
- **Solución:** Usar el polinomio estándar reflected `0xEDB88320` con valor inicial y XOR final `0xFFFFFFFF` (`CRC-32/ISO-HDLC`), idéntico al implementado por `java.util.zip.CRC32`.

---
*GasLab — Guía oficial de integración y documentación técnica.*
