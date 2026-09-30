# Plataforma MicroGas

Proyecto de monitoreo ambiental orientado al conjunto Mavic 3 Enterprise/Thermal y RC Pro Enterprise. El código de integración DJI está implementado, pero la compatibilidad de cada combinación de aeronave, control, firmware y payload debe comprobarse en equipo; este README no certifica otros modelos.

MicroGas recibe paquetes del payload por DJI MSDK, agrega la posición del dron, guarda CSV y transmite muestras por Bluetooth a un teléfono Android. El análisis posterior se realiza con `graficacionCompleta`, una herramienta Python independiente.

**Empieza aquí:** [manual de incorporación y replicación](MANUAL_REPLICACION_DESDE_CERO.md). Incluye preparación del entorno, compilación de los tres APK, prueba con dos teléfonos, integración del payload, análisis y diagnóstico. La estructura del espacio de trabajo se describe en el [README de GasLab](../README.md).

**Estado al 2026-09-29:** Receptor declara `versionName = "0.4.2"` y `versionCode = 6`; DJI y Simulado siguen en 0.4.1 (5). Se agregó ruta simulada, altura y la tercera pestaña del receptor. La validación física se documenta por separado de las pruebas automatizadas.

---

## Componentes del Ecosistema

```text
SEN66 → lector de la Pi/payload (integración pendiente)
      → emisor C → canal PSDK del dron → MSDK en el control
      → MicroGas DJI → CSV local (~10 filas/s con retención)
                    → Bluetooth (~1 muestra nueva/s)
                    → MicroGas Receptor → gráficas y CSV
CSV exportado → graficacionCompleta → PNG/PDF/SVG y mapa HTML

MicroGas Simulado sustituye al origen DJI para probar sin dron.
```

La app Android usa `PayloadDataListener`, no un lector USB CDC directo del SEN66. La conexión eléctrica del sensor y el enlace físico del payload deben verificarse con el hardware correspondiente.

1. **[`app_dji/`](app_dji/README.md)**: Aplicación para el control remoto DJI RC Pro Enterprise.
   * Decodifica paquetes MicroGas v2 de 64 bytes (nueve variables) y v1 de 32 bytes (CO₂).
   * Consulta las coordenadas GPS del dron mediante DJI Mobile SDK v5.
   * Registra los datos sincronizados en memoria y en almacenamiento interno (CSV con `fsync`).
   * Despliega una interfaz horizontal completa y una **ventana flotante (overlay)** que opera sobre DJI Pilot 2.
   * Emite alertas visuales y sonoras configurables por umbral de CO₂.
   * Actúa como servidor Bluetooth RFCOMM retransmitiendo en streaming JSON.
   * Incluye variante **Demo** con simulación de las nueve variables, coordenadas sintéticas y altura base cero, para pruebas sin hardware físico.
2. **[`app_receptor/`](app_receptor/README.md)**: Aplicación para teléfonos/tablets Android de operadores en tierra.
   * Se conecta al servidor Bluetooth del control DJI sin requerir permisos de ubicación.
   * Reconexión automática con backoff exponencial en caso de pérdida de enlace.
   * Monitor en vivo de CO₂ con alertas por código de color y vibración.
   * **9 gráficas interactivas (Canvas Compose)** para todas las variables del SEN66 con zoom multitáctil y paneo.
   * **Inspección táctil de puntos**: Tocar cualquier punto de la gráfica muestra fecha/hora exacta, valor y coordenadas GPS (Latitud y Longitud) del dron.
   * Tabla completa de datos crudos de la sesión.
   * Respaldo automático e independiente de todas las muestras en archivos CSV locales privados.
3. **[`microgas_desktop/`](app_dji/microgas_desktop)**: Módulos Python de protocolo y transporte. No incluyen una aplicación gráfica ejecutable completa; para analizar CSV usar [graficacionCompleta](../graficacionCompleta/README.md).
4. **[`apks/`](apks/)**: Instaladores APK compilados y listos para despliegue:
   * `MicroGas-DJI-debug.apk`: Versión con integración DJI MSDK para el control remoto.
   * `MicroGas-Simulado-debug.apk`: Versión Demo con nueve variables, coordenadas y altura simuladas.
   * `MicroGas-Receptor-debug.apk`: Versión para teléfonos y tablets Android de tierra.

---

## Resumen de Funcionalidades Implementadas

### 1. Variables Ambientales (SEN66 Completo)
* **CO₂ (Dióxido de Carbono)**: Concentración en partes por millón (ppm).
* **Temperatura**: Grados Celsius (°C).
* **Humedad Relativa**: Porcentaje (% HR).
* **Material Particulado (PM)**: PM1.0, PM2.5, PM4.0 y PM10 (µg/m³).
* **Gases y Calidad de Aire**: VOC (Índice de Compuestos Orgánicos Volátiles, 1–500) y NOx (Índice de Óxidos de Nitrógeno, 1–500).

### 2. Detección de Umbrales y Sistema de Colores (*Threshold*)
Tanto en el transmisor (control DJI) como en el receptor de tierra, las lecturas de CO₂ reaccionan al umbral de alarma fijado por el usuario (1 a 100.000 ppm, por defecto 3.000 ppm):
* **Blanco (`#FFFFFF`) — Nivel Normal**: Concentración por debajo del 70% del umbral fijado.
* **Amarillo (`#FFD600`) — Nivel Precaución**: Concentración entre el 70% y el 90% del umbral fijado.
* **Rojo (`#FF5252`) — Nivel Crítico**: Concentración superior al 90% del umbral.
* **Parpadeo Rojo/Blanco (500 ms) + Alarma**: Se activa al alcanzar o superar el 100% del umbral (con datos frescos $\le 5$ s).
  * En DJI: Emite pitidos audibles periódicos vía altavoz del control.
  * En Receptor: Emite vibración háptica al teléfono y muestra banner superior de alarma.

### 3. Visualización Gráfica e Inspección de Coordenadas (app Receptor)
* **Gráficas Nativas**: 9 gráficas en Canvas de Jetpack Compose optimizadas para alto rendimiento.
* **Inspección de Puntos**: Al hacer clic o tocar cualquier muestra en las gráficas, se resalta el punto y se despliega una tarjeta debajo de la gráfica con:
  * Fecha y hora exacta con milisegundos (`dd/MM/yyyy HH:mm:ss.SSS`).
  * Valor de la métrica seleccionada y lectura simultánea de CO₂.
  * **Latitud y Longitud** asociadas a la aeronave mostradas con 6 decimales (formato de visualización, no garantía de precisión).
* **Navegación**: Soporte para gestos de pinza (zoom de 1x a 60x) y arrastre horizontal (pan) con anclaje temporal y botón para restablecer a la vista en vivo.

### 4. Coexistencia con DJI Pilot 2 (Widget Flotante)
* La app DJI puede ejecutarse en segundo plano mientras se opera DJI Pilot 2.
* Un **widget flotante (overlay)** arrastrable y minimizable permanece visible en pantalla mostrando el nivel de CO₂, frescura, indicadores de estado de hardware/Bluetooth y el color según umbral.

### 5. Registro Robusto y Exportación CSV
* **Persistencia local**: escrituras sincronizadas con `FileDescriptor.sync()`. Reduce el riesgo de datos pendientes, pero no garantiza recuperar muestras perdidas ni resistir una falla del almacenamiento.
* **Exportación SAF**: Integración con Storage Access Framework de Android para copiar sesiones a unidades USB o almacenamiento en la nube sin requerir permisos invasivos.

---

## Historial de Actualizaciones (Updates)

### [Receptor v0.4.2] - 2026-09-29
* Recepción Bluetooth, reconexión y CSV continúan al ocultar la app o bloquear la pantalla mediante un servicio `connectedDevice` con notificación y acción Desconectar.
* Volver a abrir la interfaz conserva la sesión en el mismo proceso; no reinicia Bluetooth ni crea otro CSV. Desconectar detiene el servicio. Forzar cierre o finalizar el proceso interrumpe la recepción; los CSV ya escritos permanecen.
* Las alarmas del receptor siguen limitadas a la interfaz visible. No se agregó recuperación de paquetes de intervalos desconectados.

### [v0.4.1] - 2026-09-29
* Emisor DJI y Simulado: **Ocultar app** ocupa la posición y estilo de **Detener**; Detener pasa al último lugar. Ocultar conserva la adquisición activa.
* Receptor: las coordenadas del detalle de una gráfica abren **Ruta y altura** con esa muestra seleccionada.
* Mapa y perfil de altura comparten selección por sesión y secuencia, con halo de resaltado en ambos sentidos. Si el punto está fuera del encuadre por zoom/paneo, la vista se restablece para mostrarlo. No se interpola altura cuando falta.

### [v0.4.0] - 2026-09-29
* Demo: ruta sintética cerca de 9.93°, -84.08°, altura periódica de 0 a 60 m desde cero; no usa ubicación del teléfono.
* DJI: extrae `altitude` de la misma respuesta `KeyAircraftLocation3D` usada para latitud/longitud. Conserva el valor del SDK en metros, incluidos valores negativos, sin offset ni conversión vertical; referencia pendiente de definir y validar en equipo.
* Bluetooth: campo opcional `aircraft_position.altitude_m`; `source` distingue `simulado` de `dji_aircraft_msdk`.
* CSV de ambas apps: nueva columna final `altura_m`; valores ausentes permanecen vacíos.
* Receptor: tercera pestaña **Ruta y altura**, recorrido local con norte arriba, zoom, paneo, recentrado y detalle táctil de hora, CO₂, coordenadas y altura; perfil temporal de altura interactivo.
* El receptor muestra únicamente los datos recibidos; no inventa posiciones cuando falta GPS. La ruta usa las muestras en memoria (hasta 7.200), separa sesiones y huecos de secuencia y no requiere mapas en línea.
* Pendiente: prueba visual y Bluetooth en dos dispositivos y validación de altura con dron/Pilot 2.

### [v0.3.1] - 2026-09-28
* **Nivel de Advertencia por Umbral de CO₂**: Implementación del sistema de colores predictivo (`Co2Appearance`) en ambas aplicaciones:
  * Detección al 70% (Amarillo) y superior al 90% (Rojo) del umbral configurado.
  * Alarma parpadeante (Rojo/Blanco) a partir del 100% del umbral con verificación de frescura.
* **Inspección Táctil de Coordenadas en Gráficas**:
  * Adición del detector de toques (`detectTapGestures` y `nearestChartPoint`) en `Co2Chart`.
  * Visualización en tarjeta debajo de la gráfica de fecha/hora con milisegundos y coordenadas GPS (Latitud y Longitud) de la muestra seleccionada.
* **Actualización del Widget Flotante**: Sincronización del color del texto de CO₂ en el overlay de DJI con el nuevo esquema de proximidad al umbral.
* **Compilación y generación de APKs**: Generación y verificación de APKs finales para DJI (`MicroGas-DJI-debug.apk`), Demo (`MicroGas-Simulado-debug.apk`) y Receptor (`MicroGas-Receptor-debug.apk`).

### [v0.3.0] - 2026-09-25
* **Integración del Multisensor SEN66**:
  * Protocolo binario de 64 bytes vía USB Serial (CRC32, secuencia y timestamps).
  * Soporte completo para 9 variables: CO₂, Temperatura, Humedad, PM1.0, PM2.5, PM4.0, PM10, VOC y NOx.
  * Incorporación de las 9 variables en la retransmisión Bluetooth (NDJSON v1) y en los esquemas de exportación CSV.
  * Creación de las 9 gráficas individuales en la app receptora.
* **Respaldo Automático en App Receptor (0.2.0 Receptor)**:
  * Grabación independiente de cada muestra recibida en almacenamiento privado (`files/sessions/`) resistente al límite de muestras en memoria de la UI.
  * Gestor de sesiones para exportar o limpiar archivos CSV anteriores.

### [v0.2.0] - 2026-09-23
* **Integración GPS con DJI Mobile SDK v5**:
  * Consulta asíncrona de coordenadas por hardware (`KeyAircraftLocation3D` y `KeyGPSSignalLevel`).
  * Asociación de latitud, longitud y calidad de señal a las lecturas de gas con control de antigüedad máxima (5 segundos).
* **Overlay Flotante**: Creación del servicio de superposición `MonitorService` para visualización sobre DJI Pilot 2.
* **Servidor Bluetooth RFCOMM**: Implementación del servidor en el control DJI y retransmisión continua NDJSON.

### [v0.1.0] - 2026-09-18
* **Prototipo Base**:
  * Interfaz gráfica en Jetpack Compose horizontal para DJI RC Pro Enterprise.
  * Motor de simulación de CO₂ a 1 Hz (`SampleSource`).
  * Registro continuo de datos en CSV (`CsvJournal`) a 10 Hz con retención de muestras.
  * Control de umbral básico y sistema de alarmas audibles.

## Análisis posterior y documentación de trabajo

[graficacionCompleta](../graficacionCompleta/README.md) abre los CSV de ambas apps y permite elegir variables y eje temporal, exportar gráficas y mostrar la ruta sobre un fondo satelital. Funciona independientemente de Bluetooth y DJI. La demo Android genera coordenadas sintéticas y altura base cero; `ejemplo.csv` del graficador sí contiene coordenadas sintéticas para comprobar los mapas.

Para contribuir, sigue el mapa de archivos y las comprobaciones del manual. Cambiar una variable puede afectar el emisor C, decodificador Kotlin, Bluetooth, receptor, CSV y pruebas de contrato; no basta con añadirla a la pantalla.
