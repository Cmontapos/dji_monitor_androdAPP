> ARCHIVO HISTÓRICO: contiene instrucciones desactualizadas. No usar para montar o compilar. Consulte [el manual vigente](../../MANUAL_REPLICACION_DESDE_CERO.md).

# Manual Completo de Replicación: Sistema MicroGas (Control DJI + App Celular)

**GasLab — Monitoreo de Gases Atmosféricos con Dron DJI Mavic 3T**  
*Documento de Ingeniería y Guía de Construcción Paso a Paso desde Cero*

---

## Índice

1. [Visión General del Sistema y Arquitectura de Hardware](#1-visión-general-del-sistema-y-arquitectura-de-hardware)
2. [Protocolos de Comunicación y Formatos de Datos](#2-protocolos-de-comunicación-y-formatos-de-datos)
   - 2.1. Pi 5 → Mavic 3T → RC Pro (Protocolo Binario PSDK v1)
   - 2.2. RC Pro → Celular (Protocolo Bluetooth Clásico RFCOMM / NDJSON v1)
   - 2.3. Formato de Archivo CSV (15 columnas en control, 9 en celular)
   - 2.4. Aclaración de Sensores: CO₂, Temperatura y Humedad (Sin SO₂)
3. [App 1: MicroGas para DJI RC Pro Enterprise (`app_dji`)](#3-app-1-microgas-para-dji-rc-pro-enterprise-app_dji)
   - 3.1. Requisitos y Herramientas de Desarrollo
   - 3.2. Configuración de Gradle, Dependencias y Flavors (`demo` y `dji`)
   - 3.3. AndroidManifest y Permisos Específicos
   - 3.4. Componentes Clave y Arquitectura de Software
   - 3.5. Pasos de Compilación, Instalación y Ejecución
4. [App 2: MicroGas Receptor para Celular Android (`app_receptor`)](#4-app-2-microgas-receptor-para-celular-android-app_receptor)
   - 4.1. Requisitos y Herramientas de Desarrollo
   - 4.2. Configuración de Gradle y Dependencias (Sin SDK DJI)
   - 4.3. AndroidManifest y Permisos de Bluetooth
   - 4.4. Componentes Clave y Arquitectura de Software
   - 4.5. Interfaz de Usuario: Pestañas de Monitor e Histórico con Canvas
   - 4.6. Pasos de Compilación, Instalación y Ejecución
5. [Guía de Integración y Prueba Extremo a Extremo](#5-guía-de-integración-y-prueba-extremo-a-extremo)
   - 5.1. Prueba Local sin Hardware (Modo Simulado / Demo)
   - 5.2. Prueba en Banco de Trabajo con Hardware Real
   - 5.3. Convivencia con DJI Pilot 2 en Vuelo
6. [Resolución de Problemas Frecuentes (Troubleshooting)](#6-resolución-de-problemas-frecuentes-troubleshooting)

---

## 1. Visión General del Sistema y Arquitectura de Hardware

El sistema **MicroGas** permite adquirir, visualizar, georreferenciar y registrar concentraciones de gas carbónico (CO₂) desde una carga útil acoplada a un dron DJI Mavic 3 Enterprise / Thermal (Mavic 3T), enviando la información a dos terminales:
1. **Control DJI RC Pro Enterprise:** corre la app `MicroGas`, que procesa los paquetes nativos PSDK vía DJI Mobile SDK V5 (MSDK), añade la posición GPS del dron, registra en CSV de alta fidelidad, ofrece una ventana flotante sobre DJI Pilot 2 y actúa como **servidor Bluetooth**.
2. **Celular Android (Estación de Monitoreo Remoto):** corre la app `MicroGas Receptor`, que se conecta como **cliente Bluetooth clásico RFCOMM** al control, grafica CO₂ en tiempo real, almacena el histórico con zoom/pan, permite exportar CSV y emite alertas por vibración.

### Diagrama de Enlace Físico

```text
┌─────────────────────────┐
│     Sensor SEN66        │ (Mide CO₂, Temp, Humedad, PM1/2.5/4/10)
└───────────┬─────────────┘
            │ I2C (~1 Hz)
            ▼
┌─────────────────────────┐
│   Raspberry Pi 5        │ (Corre DJI PSDK en C/C++)
└───────────┬─────────────┘
            │ UART / Conector E-Port V1
            ▼
┌─────────────────────────┐
│    DJI Mavic 3T         │
└───────────┬─────────────┘
            │ Enlace Inalámbrico Propietario DJI (O3 Enterprise)
            ▼
┌─────────────────────────────────────────────────────────────┐
│              DJI RC Pro Enterprise (Android 10)             │
│  - DJI Pilot 2 (Cámara térmica, telemetría y vuelo)        │
│  - App MicroGas (MSDK V5, CSV local, Overlay flotante,     │
│                  Servidor Bluetooth RFCOMM en segundo plano)│
└─────────────────────────────┬───────────────────────────────┘
                              │ Bluetooth Clásico RFCOMM (SPP)
                              │ UUID: bb239920-bdbc-4d51-8a12-a5351875d891
                              ▼
┌─────────────────────────────────────────────────────────────┐
│               Celular Android (Android 8.0+)                │
│  - App MicroGas Receptor (Jetpack Compose, MVVM)           │
│  - Pestaña 1: Monitor en tiempo real (CO₂ vs tiempo)       │
│  - Pestaña 2: Histórico (CO₂, Temp, Hum; selector minutos)  │
│  - Exportación CSV vía Storage Access Framework             │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. Protocolos de Comunicación y Formatos de Datos

### 2.1. Pi 5 → Mavic 3T → RC Pro (Protocolo Binario PSDK v1)

La Raspberry Pi 5 envía tramas binarias compactas de **32 bytes** mediante la función de transmisión PSDK hacia la dirección `DJI_CHANNEL_ADDRESS_MASTER_RC_APP`. En el control, MicroGas las recibe a través del `PayloadDataListener` de DJI MSDK V5 en el canal `PayloadIndexType.UP`.

#### Estructura del Paquete (Little-Endian)

| Offset | Tamaño | Tipo | Campo | Descripción |
|:------:|:------:|:----:|:------|:------------|
| 0–3 | 4 B | ASCII | `magic` | Valor constante ASCII: `MGAS` (`0x4D, 0x47, 0x41, 0x53`) |
| 4–5 | 2 B | uint16 | `version` | Versión del protocolo: `0x0101` (v1.1) |
| 6–7 | 2 B | uint16 | `reserved`| Relleno reservado: `0x0000` |
| 8–11 | 4 B | uint32 | `boot` | Identificador aleatorio único por cada arranque del Pi |
| 12–15 | 4 B | uint32 | `sequence`| Contador monotónico de muestras (0, 1, 2, ... con rollover) |
| 16–23 | 8 B | int64 | `uptime` | Milisegundos monotónicos de funcionamiento de la Pi |
| 24–27 | 4 B | float32 | `co2` | Concentración de CO₂ en ppm (IEEE-754 simple precisión) |
| 28–31 | 4 B | uint32 | `crc32` | Suma de verificación CRC32 estándar (IEEE 802.3) de bytes 0 a 27 |

*Reglas de Validación en `PayloadProtocol.kt`:*
- Tamaño exacto: 32 bytes.
- Validación de Magic y Versión.
- Coincidencia de CRC32.
- Monotonicidad de secuencia por cada `boot`: secuencias viejas o duplicadas se descartan; si un `boot` finaliza y aparece otro posterior, el `boot` anterior se agrega a `retiredBoots` y se bloquea si reaparece.

---

### 2.2. RC Pro → Celular (Protocolo Bluetooth Clásico RFCOMM / NDJSON v1)

MicroGas en el control actúa como servidor Bluetooth RFCOMM (Serial Port Profile).

- **Nombre SDP:** `MicroGas`
- **UUID de Servicio:** `bb239920-bdbc-4d51-8a12-a5351875d891`
- **Formato de Transmisión:** NDJSON (Newline Delimited JSON). Cada muestra es un objeto JSON en una sola línea terminada en `\n` (`0x0A`).
- **Frecuencia:** ~1 Hz (únicamente muestras nuevas físicas; no se envían filas retenidas del Zero-Order Hold).
- **Control de Congestión en Servidor:** Canal conflado (`Channel<Pair<Long, ByteArray>>(CONFLATED)`). Si el celular es lento procesando, no hay acumulación en RAM: se conserva únicamente la muestra más reciente. Las muestras retenidas por más de 5 segundos se descartan (`staleness`). Timeout de socket bloqueado: 3 segundos.

#### Ejemplo de Paquete JSON v1 Emitido

```json
{
  "v": 1,
  "session": "550e8400-e29b-41d4-a716-446655440000",
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

*Detalles de campos:*
- `v`: 1 (versión del esquema).
- `session`: UUID generado al iniciar la adquisición en el control.
- `sequence`: Contador local de muestras emitidas por el control.
- `received_at_ms`: Epoch UTC en milisegundos cuando el control recibió el paquete.
- `aircraft_position`: Objeto con la posición del dron consultada por MSDK (`KeyAircraftLocation3D` y `KeyGPSSignalLevel`). Si el dron no tiene fix GPS o la muestra proviene de la simulación demo, este campo es `null`.
- **Extensión futura de temperatura y humedad:** El analizador del celular (`MeasurementParser.kt`) está preparado para leer `temperature_c` y `humidity_pct` si están presentes, o dejarlos en `null` si no vienen en la trama.

---

### 2.3. Formato de Archivo CSV

#### Archivo CSV en el Control (`MicroGas` — 15 Columnas)
El control escribe a ~10 Hz (desacoplado por Zero-Order Hold) en `files/sessions/`:
```csv
fecha_hora_utc,co2_ppm,sen66_new_data,sen66_muestra_utc,sen66_secuencia,latitud,longitud,origen,payload_boot,payload_secuencia,payload_uptime_ms,gps_origen,gps_recepcion_utc,gps_edad_al_recibir_co2_ms,gps_nivel_senal
```
- `sen66_new_data`: `1` si es una muestra nueva que acaba de llegar, `0` si es una fila retenida por el Zero-Order Hold.

#### Archivo CSV en el Celular (`MicroGas Receptor` — 9 Columnas)
El celular guarda e importa mediante Storage Access Framework (SAF):
```csv
fecha_hora_utc,co2_ppm,temperatura_c,humedad_pct,latitud,longitud,origin,session,sequence
```
- Formato UTC: ISO-8601 con milisegundos (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`).
- Punto decimal forzado con `Locale.US`.

---

### 2.4. Aclaración de Sensores: CO₂, Temperatura y Humedad (Sin SO₂)

> [!IMPORTANT]
> **El sensor físico instalado es un Sensirion SEN66.**
> El SEN66 mide exclusivamente:
> 1. Concentración de **CO₂** (NDIR).
> 2. **Temperatura** (°C).
> 3. **Humedad relativa** (% HR).
> 4. Material particulado (**PM1.0, PM2.5, PM4.0, PM10**).
> 
> **El SEN66 NO mide dióxido de azufre (SO₂).**
> Por lo tanto, ninguna de las dos aplicaciones incluye SO₂ en su interfaz, gráficas, cálculos ni registros. Los gráficos de la app del celular están dedicados exclusivamente a **CO₂**, **Temperatura** y **Humedad**.

---

## 3. App 1: MicroGas para DJI RC Pro Enterprise (`app_dji`)

### 3.1. Requisitos y Herramientas de Desarrollo

- **IDE:** Android Studio Ladybug (2024.2+) o Hedgehog+.
- **JDK:** Java Development Kit 17 (configurado como Gradle JDK).
- **Android SDK:**
  - `compileSdk`: 35
  - `minSdk`: 29 (Android 10, sistema operativo nativo del DJI RC Pro)
  - `targetSdk`: 34
  - `ndk`: arm64-v8a
- **Librerías principales:**
  - Kotlin: 2.0.21
  - Jetpack Compose BOM: 2024.12.01 (Material 3)
  - DJI MSDK V5 Aircraft: `5.18.0` (solo para flavor `dji`)

---

### 3.2. Configuración de Gradle, Dependencias y Flavors

El proyecto utiliza dos *Product Flavors*:
1. **`demo` (`com.gaslab.microgas.demo`):** Genera una onda sinusoidal de CO₂ (650 ± 160 ppm a 1 Hz). Permite probar toda la app (gráficas, CSV, Bluetooth, overlay) en cualquier teléfono, tablet o emulador sin hardware DJI ni App Key.
2. **`dji` (`com.gaslab.microgas`):** Inicializa DJI MSDK V5, carga bibliotecas nativas C++ para arm64-v8a, escucha el puerto E-Port V1 y consulta el GPS del dron.

#### `build.gradle.kts` (Nivel Raíz)
```kotlin
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
```

#### `app/build.gradle.kts`
```kotlin
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localConfig = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val djiKey = providers.environmentVariable("DJI_MSDK_API_KEY").orNull
    ?: localConfig.getProperty("dji.msdk.apiKey", "")

android {
    namespace = "com.gaslab.microgas"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gaslab.microgas"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
    }

    flavorDimensions += "source"
    productFlavors {
        create("demo") {
            dimension = "source"
            applicationIdSuffix = ".demo"
            resValue("string", "microgas_app_name", "MicroGas Simulado")
            buildConfigField("String", "DATA_ORIGIN", "\"simulado\"")
        }
        create("dji") {
            dimension = "source"
            resValue("string", "microgas_app_name", "MicroGas DJI")
            buildConfigField("String", "DATA_ORIGIN", "\"dji\"")
            manifestPlaceholders["DJI_API_KEY"] = djiKey
            ndk { abiFilters += "arm64-v8a" }
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so"
            pickFirsts += "**/libc++_shared.so"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    "djiImplementation"("com.dji:dji-sdk-v5-aircraft:5.18.0")
    "djiCompileOnly"("com.dji:dji-sdk-v5-aircraft-provided:5.18.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
```

---

### 3.3. AndroidManifest y Permisos Específicos

En `app/src/main/AndroidManifest.xml`:
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- Overlay sobre DJI Pilot 2 -->
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    
    <!-- Servicio en primer plano para adquisición ininterrumpida -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />

    <!-- Bluetooth clásico para actuar como servidor hacia el celular -->
    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

    <!-- Ubicación y GPS del dron -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

    <application
        android:label="@string/microgas_app_name"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">
        
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="landscape"
            android:configChanges="orientation|screenSize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".MonitorService"
            android:exported="false"
            android:foregroundServiceType="connectedDevice" />
    </application>
</manifest>
```

---

### 3.4. Componentes Clave y Arquitectura de Software

La app sigue el patrón MVVM con flujo unidireccional reactivo:

```text
SampleSource (Flow<GasSample>)
         │
         ▼
   MonitorEngine (Singleton)
         │
    ┌────┼────────────────────────┐
    ▼    ▼                        ▼
SampleHold   MonitorState   MonitorService Callbacks
 (ZOH ~10Hz)  (UI Buffer)     - Alarma (ToneGenerator)
    │              │          - Servidor Bluetooth (BluetoothRelay)
    ▼              ▼          - Ventana flotante (FloatingMonitor)
CsvJournal   MonitorViewModel
(Filesystem)       │
                   ▼
              Compose UI
```

1. **`PayloadProtocol.kt`:** Desempaqueta y valida tramas de 32 B mediante CRC32. Administra secuencias y retira boots anteriores.
2. **`SampleHold.kt` (Zero-Order Hold):** Desacopla la tasa de llegada física del sensor (~1 Hz) de la tasa de grabación en disco (~10 Hz). Entrega muestras retenidas marcando `sen66_new_data = 0`.
3. **`CsvJournal.kt`:** Maneja el log en `files/sessions/` con `RandomAccessFile` y `fd.sync()`. Emplea un algoritmo de escaneo inverso para recortar líneas incompletas ante cortes repentinos de energía.
4. **`FloatingMonitor.kt`:** Crea una vista flotante compacta usando `WindowManager` con `TYPE_APPLICATION_OVERLAY` y `FLAG_NOT_FOCUSABLE`, permitiendo al piloto controlar el Mavic 3T en Pilot 2 mientras observa el CO₂ y el estado RX/BT.
5. **`BluetoothRelay.kt`:** Servidor RFCOMM escuchando en el UUID `bb239920-bdbc-4d51-8a12-a5351875d891`.
6. **`DjiAircraftPosition.kt`:** Realiza polling asíncrono con timeout de 2 s de la posición del dron usando `KeyManager.getInstance().getValue()`.

---

### 3.5. Pasos de Compilación, Instalación y Ejecución

#### A. Compilar y Ejecutar Flavor `demo` (Para pruebas sin control)
```bash
cd /home/cristopher/Documents/GasLab/app_dji
./gradlew :app:assembleDemoDebug
# Instalar en dispositivo conectado vía adb:
adb install app/build/outputs/apk/demo/debug/app-demo-debug.apk
```

#### B. Compilar y Desplegar Flavor `dji` (En el DJI RC Pro Enterprise)
1. Conectar el RC Pro Enterprise por cable USB-C a la PC.
2. Activar **Depuración por USB** en el control (Ajustes → Opciones de desarrollador).
3. Asegurarse de tener configurada la API Key de DJI en `local.properties`:
   ```properties
   dji.msdk.apiKey=TU_CLAVE_DJI_MSDK_AQUI
   ```
4. Compilar e instalar:
   ```bash
   ./gradlew :app:assembleDjiDebug
   adb install -r app/build/outputs/apk/dji/debug/app-dji-debug.apk
   ```

---

## 4. App 2: MicroGas Receptor para Celular Android (`app_receptor`)

### 4.1. Requisitos y Herramientas de Desarrollo

- **Ubicación del Proyecto:** `/home/cristopher/Documents/GasLab/app_dji/app_receptor`
- **IDE:** Android Studio (abrir directamente la carpeta `app_receptor`).
- **JDK:** Java 17.
- **Android SDK:**
  - `compileSdk`: 35
  - `minSdk`: 26 (Android 8.0 Oreo — cubre la gran mayoría de celulares comerciales)
  - `targetSdk`: 34
- **Cero dependencias DJI:** No requiere claves, ni librerías nativas pesadas, ni NDK.

---

### 4.2. Configuración de Gradle y Dependencias

#### `app_receptor/build.gradle.kts` (Nivel Raíz)
```kotlin
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
```

#### `app_receptor/app/build.gradle.kts`
```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.gaslab.microgas.receptor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gaslab.microgas.receptor"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
```

---

### 4.3. AndroidManifest y Permisos de Bluetooth

En `app_receptor/app/src/main/AndroidManifest.xml`:
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- Permisos Bluetooth para Android 11 y anteriores -->
    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />

    <!-- Permiso Bluetooth moderno para Android 12+ (API 31+) -->
    <!-- neverForLocation indica que no se usa Bluetooth para calcular posición del usuario -->
    <uses-permission
        android:name="android.permission.BLUETOOTH_CONNECT"
        android:usesPermissionFlags="neverForLocation" />

    <!-- Vibración para alerta por umbral de CO₂ -->
    <uses-permission android:name="android.permission.VIBRATE" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="MicroGas Receptor"
        android:roundIcon="@mipmap/ic_launcher"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

---

### 4.4. Componentes Clave y Arquitectura de Software

```text
┌────────────────────────────────────────────────────────┐
│                   BluetoothClient                      │
│ - Conecta por RFCOMM (UUID bb239920-...)               │
│ - Watchdog de 15s + Reintento exponencial (5,10,20,30s)│
└───────────┬────────────────────────────────────────────┘
            │ Byte stream
            ▼
┌────────────────────────────────────────────────────────┐
│         NdjsonFramer + MeasurementParser               │
│ - Ensambla líneas UTF-8 y extrae JSON v1               │
│ - Soporta campos nulos y extensiones futuras           │
└───────────┬────────────────────────────────────────────┘
            │ Objeto Measurement
            ▼
┌────────────────────────────────────────────────────────┐
│               MeasurementRepository                    │
│ - Buffer circular en RAM: 7.200 muestras (~2 horas)    │
│ - Registro automático CSV local + Exportación SAF      │
│ - Detección de huecos de secuencia y cambio de sesión │
└───────────┬────────────────────────────────────────────┘
            │ StateFlow<MonitorUiState>
            ▼
┌────────────────────────────────────────────────────────┐
│                  MonitorViewModel                      │
│ - Alerta por umbral (AlertManager con vibración)       │
│ - Gestión de ventana de tiempo y selección de UI       │
└───────────┬────────────────────────────────────────────┘
            │
      ┌─────┴────────────────┐
      ▼                      ▼
MonitorScreen (Tab 1)   HistoryScreen (Tab 2)
- Canvas Gráfica CO₂    - 3 Gráficas apiladas (Canvas)
- CO₂ gigante actual      (CO₂, Temperatura, Humedad)
- Selector 1–30 min     - Zoom pinch + Pan horizontal
                        - Selector minutos / Todo
                        - Tabla de datos crudos
```

1. **`Measurement.kt`:** Define la entidad de datos inmutable y el parser JSON tolerante a errores:
   ```kotlin
   data class Measurement(
       val receivedAtMs: Long,
       val co2Ppm: Float,
       val temperatureC: Float? = null,
       val humidityPct: Float? = null,
       val latitude: Double? = null,
       val longitude: Double? = null,
       val origin: String,
       val session: String,
       val sequence: Long,
       val senderBoot: Long? = null,
       val senderSequence: Long? = null,
       val acquiredUptimeMs: Long? = null,
       val signalLevel: Int? = null,
   )
   ```
2. **`NdjsonFramer`:** Buffer de bytes con límite de 16 KiB que divide los flujos entrantes por `\n`, previniendo que caracteres UTF-8 multibyte se corten entre lecturas de red.
3. **`BluetoothClient.kt`:** Administra el socket RFCOMM cliente, gestiona cancelaciones limpias, watchdogs de conexión y reintento progresivo con backoff (5, 10, 20, 30 segundos).
4. **`MeasurementRepository.kt`:**
   - Retiene hasta **7.200 muestras** en memoria RAM.
   - Detecta duplicados y pérdidas de secuencia (`missingSequences`).
   - Escribe un CSV de respaldo en `files/sessions/` y genera snapshots para exportación con SAF.
5. **`AlertManager.kt`:** Al superar el umbral de CO₂ configurado (ej. 3.000 ppm) con datos frescos (edad < 5 s), ejecuta una vibración distintiva de advertencia (`Vibrator`).

---

### 4.5. Interfaz de Usuario: Pestañas de Monitor e Histórico con Canvas

Las gráficas se crearon a la medida en **Canvas de Compose (`Co2Chart.kt`)** para no depender de bibliotecas externas pesadas y garantizar 60 fps en cualquier teléfono:

#### Pestaña 1: Monitor en Tiempo Real (`MonitorScreen.kt`)
- **Tarjeta de CO₂ principal:** Número grande con la última lectura, estado de frescura ("Datos actuales" o "Sin datos nuevos") y origen (`dji` o `SIMULADO`).
- **Selector de Ventana:** Botones tipo chip para seleccionar los últimos **1, 2, 5, 10, 15 o 30 minutos**.
- **Gráfica de CO₂ vs Tiempo:** Trazo continuo con gradiente inferior, autoescala en el eje Y y etiquetas de tiempo en formato `HH:mm:ss`.
- **Indicadores secundarios:** Tarjetas para **Temperatura** y **Humedad**, listas para mostrar datos cuando el protocolo se actualice (muestran "—" mientras tanto). **Sin SO₂.**

#### Pestaña 2: Histórico (`HistoryScreen.kt`)
- **Selector de Ventana en Minutos:** Permite elegir **1, 2, 5, 10, 15, 30, 60 minutos o "Todo"**.
- **3 Gráficas Apiladas con Desplazamiento Vertical:**
  1. `CO₂ (ppm)` vs Tiempo.
  2. `Temperatura (°C)` vs Tiempo (muestra "Sin datos registrados" hasta recibir mediciones).
  3. `Humedad (% HR)` vs Tiempo (muestra "Sin datos registrados" hasta recibir mediciones).
- **Interactividad Táctil:** Soporta gesto de pellizco (**pinch-to-zoom**) para ampliar y arrastre horizontal (**pan**) para recorrer el tiempo hacia atrás.
- **Tabla de Datos Crudos:** Tabla desplazable horizontalmente con columnas: `Hora`, `CO₂ (ppm)`, `Temp. (°C)`, `Hum. (% HR)`, `Latitud`, `Longitud`.

---

### 4.6. Pasos de Compilación, Instalación y Ejecución

```bash
cd /home/cristopher/Documents/GasLab/app_dji/app_receptor

# Ejecutar pruebas unitarias de parseo y repositorio
./gradlew testDebugUnitTest

# Compilar APK de depuración
./gradlew assembleDebug

# Instalar en el celular conectado por USB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 5. Guía de Integración y Prueba Extremo a Extremo

### 5.1. Prueba Local sin Hardware (Modo Simulado / Demo)

Esta prueba permite validar el flujo completo sin necesidad de encender el dron ni la Raspberry Pi:

1. **En el Control o en un teléfono A:**
   - Instalar `app-demo-debug.apk` de `app_dji`.
   - Abrir la app (aparecerá como "MicroGas Simulado").
   - Pulsar **Iniciar pop-up** o **Segundo plano**.
   - Conceder el permiso de superposición si se solicita.
   - El servidor Bluetooth RFCOMM quedará activo emitiendo datos simulados (~1 Hz).
2. **En el Celular B (Estación de monitoreo):**
   - Ir a Ajustes de Android → Bluetooth y **emparejar** el Celular B con el dispositivo A.
   - Abrir la app `MicroGas Receptor`.
   - Conceder permiso de "Dispositivos cercanos" (Android 12+).
   - En la lista de dispositivos emparejados, seleccionar el dispositivo A.
   - Verificar:
     - El estado cambia a **Conectado**.
     - En **Monitor**, el CO₂ oscila suavemente en torno a 650 ppm y la gráfica avanza.
     - En **Histórico**, se pueden cambiar las ventanas (1, 5, 10 min), hacer zoom en la gráfica de CO₂ y revisar la tabla de datos crudos.
     - Pulsar **Exportar CSV** y guardar el archivo en la carpeta "Descargas".

---

### 5.2. Prueba en Banco de Trabajo con Hardware Real

1. Conectar el sensor SEN66 a la Raspberry Pi 5 por I2C.
2. Conectar la Pi 5 al puerto E-Port V1 del Mavic 3T mediante el cable serial UART.
3. Encender el Mavic 3T y el control DJI RC Pro Enterprise.
4. En el Pi 5, ejecutar el programa transmisor PSDK que empaqueta las lecturas del SEN66 en los 32 bytes del protocolo `MGAS`.
5. En el control DJI RC Pro:
   - Abrir `MicroGas DJI`.
   - Verificar que el estado cambie a "Recepción activa" y muestre el CO₂ real medido por el SEN66.
   - Si el dron tiene satélites, comprobar que las coordenadas de latitud/longitud aparezcan en pantalla.
6. En el celular:
   - Conectar por Bluetooth al control.
   - Comprobar que el origen marque `dji` y las coordenadas GPS coincidan con las del dron.

---

### 5.3. Convivencia con DJI Pilot 2 en Vuelo

1. En el control RC Pro Enterprise, pulsar **Iniciar pop-up** en MicroGas.
2. Pulsar el botón **Ocultar app** (esto ejecuta `moveTaskToBack(true)`).
3. Abrir **DJI Pilot 2** para la operación de vuelo.
4. La ventana flotante (`FloatingMonitor`) permanecerá visible sobre la cámara de Pilot 2, mostrando:
   - Concentración actual de CO₂ (con fondo rojo si cruza el umbral de alarma).
   - Indicador `RX ✓` (datos frescos del sensor).
   - Indicador `BT ✓` (transmisión activa hacia el celular).
5. Mientras el piloto opera el dron en Pilot 2, el operador secundario o investigador en tierra monitorea las curvas de gas en el celular en tiempo real.

---

## 6. Resolución de Problemas Frecuentes (Troubleshooting)

### A. "Error de conexión Bluetooth / Socket failed"
- **Causa:** El control no tiene el servidor iniciado o los dispositivos no están emparejados a nivel del sistema operativo.
- **Solución:** Primero emparejar ambos equipos desde el menú Ajustes de Android. Asegurarse de que en el control se haya pulsado "Iniciar pop-up" o "Segundo plano" en MicroGas antes de intentar conectar desde el celular.

### B. "Bluetooth disconnects al bloquear el celular"
- **Causa:** Optimización de batería del fabricante del celular.
- **Solución:** En el celular, ir a Ajustes → Aplicaciones → MicroGas Receptor → Batería → Seleccionar **Sin restricciones**.

### C. "Coordenadas GPS vacías (—)"
- **Causa:** El dron se encuentra en interiores o aún no adquiere fix GNSS con calidad suficiente (`signalLevel >= 3`).
- **Solución:** Probar en exteriores con línea de vista al cielo hasta que Pilot 2 reporte satélites en verde.

### D. "Las columnas de Temperatura y Humedad muestran '—'"
- **Causa:** Comportamiento esperado en la versión v1 del protocolo.
- **Explicación:** El paquete PSDK de 32 bytes actual solo emite CO₂. La interfaz y el parser ya están listos para mostrarlas en cuanto se amplíe la estructura del paquete en el Pi y en el control.

---

*Manual elaborado para el Laboratorio de Monitoreo de Gases Atmosféricos (GasLab).*
