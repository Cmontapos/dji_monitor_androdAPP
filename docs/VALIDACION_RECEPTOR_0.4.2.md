# Receptor 0.4.2 — 2026-09-29

versionCode 6. DJI y Demo permanecen en 0.4.1 (5).

ReceiverEngine conserva sesión, histórico y archivo CSV independientemente de la actividad. ReceiverService mantiene recepción/reconexión mediante servicio foreground connectedDevice, con notificación y acción Desconectar. La visibilidad solo controla alertas y actualización de listas: ocultar, rotar o abrir SAF no reinicia la conexión. La destrucción del ViewModel no cierra Bluetooth. El servicio no se reinicia automáticamente después de la muerte del proceso; los CSV escritos se conservan.

Validación: testDebugUnitTest (31 pruebas, 0 fallos), assembleDebug y lintDebug completados offline; firma verificada con apksigner. Las pruebas existentes cubren contratos y almacenamiento, no el ciclo de vida Android. No había dispositivos adb conectados: quedan pendientes pruebas físicas de pantalla bloqueada, regreso a la app, recreación de actividad, reconexión, denegación de permisos y desconexión desde notificación. Guía en el manual principal.

Referencia Android: https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device

No se agregó recuperación de paquetes ni confirmaciones; no cambia el tráfico Bluetooth del protocolo.

SHA-256 de MicroGas-Receptor-debug.apk: `90292223a0884ae6ee91f7d804bd32139b2a294b6aa282548945ad520ba558ca`
