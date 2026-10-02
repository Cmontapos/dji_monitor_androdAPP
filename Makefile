# Makefile para compilar el proyecto MicroGas y centralizar APKs
# Uso:
#   make apks      -> Compila las 3 variantes y las copia a la carpeta ./apks/
#   make dji       -> Compila solo la app DJI (con MSDK)
#   make demo      -> Compila solo la app Demo (Simulado)
#   make receptor  -> Compila solo la app Receptor
#   make test      -> Ejecuta pruebas unitarias de ambas apps
#   make clean     -> Limpia los directorios temporales de compilación

.PHONY: all apks dji demo receptor test lint clean help mkdir_apks
.NOTPARALLEL:

GRADLE_FLAGS ?= --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx1024m -Dfile.encoding=UTF-8" -Pkotlin.compiler.execution.strategy=in-process

all: apks

help:
	@echo "Comandos disponibles en MicroGas:"
	@echo "  make apks      - Compilar los 3 APKs y colocarlos en ./apks/"
	@echo "  make dji       - Compilar MicroGas-DJI-debug.apk"
	@echo "  make demo      - Compilar MicroGas-Simulado-debug.apk"
	@echo "  make receptor  - Compilar MicroGas-Receptor-debug.apk"
	@echo "  make test      - Ejecutar pruebas unitarias de ambas aplicaciones"
	@echo "  make clean     - Limpiar directorios de compilación Gradle"

# Asegurar que el directorio de destino apks exista
mkdir_apks:
	@mkdir -p apks

# Compilar MicroGas DJI (para control remoto con MSDK v5)
dji: mkdir_apks
	@echo "==> Compilando MicroGas DJI Debug..."
	cd app_dji && ./gradlew $(GRADLE_FLAGS) assembleDjiDebug
	@cp -f app_dji/app/build/outputs/apk/dji/debug/app-dji-debug.apk apks/MicroGas-DJI-debug.apk
	@echo "==> Generado: apks/MicroGas-DJI-debug.apk"

# Compilar MicroGas Simulado / Demo (para pruebas sin dron)
demo: mkdir_apks
	@echo "==> Compilando MicroGas Simulado (Demo Debug)..."
	cd app_dji && ./gradlew $(GRADLE_FLAGS) assembleDemoDebug
	@cp -f app_dji/app/build/outputs/apk/demo/debug/app-demo-debug.apk apks/MicroGas-Simulado-debug.apk
	@echo "==> Generado: apks/MicroGas-Simulado-debug.apk"

# Compilar MicroGas Receptor (para celular o tablet de monitoreo)
receptor: mkdir_apks
	@echo "==> Compilando MicroGas Receptor Debug..."
	cd app_receptor && ./gradlew $(GRADLE_FLAGS) assembleDebug
	@cp -f app_receptor/app/build/outputs/apk/debug/app-debug.apk apks/MicroGas-Receptor-debug.apk
	@echo "==> Generado: apks/MicroGas-Receptor-debug.apk"

# Compilar todas las aplicaciones y copiarlas a apks/
apks: dji demo receptor
	@echo ""
	@echo "=========================================================="
	@echo "  Compilación completada. Archivos disponibles en ./apks/:"
	@echo "=========================================================="
	@ls -lh apks/*.apk

# Ejecutar pruebas unitarias en ambas aplicaciones
test:
	@echo "==> Ejecutando pruebas unitarias en app_dji..."
	cd app_dji && ./gradlew $(GRADLE_FLAGS) testDemoDebugUnitTest testDjiDebugUnitTest
	@echo "==> Ejecutando pruebas unitarias en app_receptor..."
	cd app_receptor && ./gradlew $(GRADLE_FLAGS) testDebugUnitTest
	@echo "==> Todas las pruebas unitarias fueron aprobadas."

# Limpiar archivos de compilación temporales
clean:
	@echo "==> Limpiando Gradle en app_dji..."
	cd app_dji && ./gradlew $(GRADLE_FLAGS) clean
	@echo "==> Limpiando Gradle en app_receptor..."
	cd app_receptor && ./gradlew $(GRADLE_FLAGS) clean
	@echo "==> Limpieza terminada."

lint:
	cd app_dji && ./gradlew $(GRADLE_FLAGS) lintDemoDebug lintDjiDebug
	cd app_receptor && ./gradlew $(GRADLE_FLAGS) lintDebug
