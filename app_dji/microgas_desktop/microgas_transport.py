"""Serial worker thread and asynchronous Qt RFCOMM adapter.

The Android MicroGas UUID is custom, so a Windows SPP COM port is NOT assumed.
"""
from __future__ import annotations

import threading
import time
import uuid

import serial
from PyQt6 import QtCore
from microgas_core import NdjsonFramer, SERVICE_UUID, demo_frame


class SerialWorker(QtCore.QThread):
    data = QtCore.pyqtSignal(bytes, float)
    status = QtCore.pyqtSignal(str, str)

    def __init__(self, port, demo=False, serial_factory=serial.Serial, parent=None):
        super().__init__(parent)
        self.port, self.demo, self.factory = port, demo, serial_factory
        self.stop_event = threading.Event()

    def stop(self):
        self.stop_event.set()  # read timeout is 250 ms; never terminate a QThread forcibly.

    def run(self):
        if self.demo:
            session = str(uuid.uuid4())
            self.status.emit("Conectado", "SIMULACIÓN LOCAL · no usa Bluetooth")
            step = 0
            while not self.stop_event.is_set():
                self.data.emit(demo_frame(step, session, int(time.time() * 1000)), time.monotonic())
                step += 1
                self.stop_event.wait(1)
            return
        attempt = 0
        while not self.stop_event.is_set():
            self.status.emit("Conectando", f"Abriendo {self.port}")
            try:
                with self.factory(port=self.port, baudrate=115200, timeout=.25, write_timeout=1) as connection:
                    if self.stop_event.is_set():
                        break
                    # Notify the UI to reset its framing after every new connection.
                    self.status.emit("Conectado", self.port)
                    while not self.stop_event.is_set():
                        data = connection.read(min(max(connection.in_waiting, 1), 4096))
                        if data:
                            attempt = 0
                            self.data.emit(data, time.monotonic())
            except (serial.SerialException, OSError, ValueError) as error:
                if self.stop_event.is_set():
                    break
                delay = (5, 10, 20, 30)[min(attempt, 3)]
                attempt += 1
                self.status.emit("Reconectando", f"{error} · nuevo intento en {delay} s")
                self.stop_event.wait(delay)


class BluetoothClient(QtCore.QObject):
    data = QtCore.pyqtSignal(bytes, float)
    status = QtCore.pyqtSignal(str, str)

    def __init__(self, address, parent=None):
        super().__init__(parent)
        self.address, self.socket = address, None
        self.stopped, self.attempt = False, 0
        self.retry = QtCore.QTimer(self)
        self.retry.setSingleShot(True)
        self.retry.timeout.connect(self.start)
        self.watchdog = QtCore.QTimer(self)
        self.watchdog.setSingleShot(True)
        self.watchdog.timeout.connect(lambda: self.failed("Tiempo de conexión agotado"))

    def start(self):
        if self.stopped:
            return
        from PyQt6.QtBluetooth import QBluetoothSocket, QBluetoothAddress, QBluetoothUuid, QBluetoothServiceInfo
        self.drop_socket()
        self.socket = QBluetoothSocket(QBluetoothServiceInfo.Protocol.RfcommProtocol, self)
        self.socket.connected.connect(self.connected)
        self.socket.readyRead.connect(self.read)
        self.socket.disconnected.connect(lambda: self.failed("El emisor desconectó"))
        self.socket.errorOccurred.connect(lambda _error: self.failed(self.socket.errorString() if self.socket else "Error Bluetooth"))
        self.status.emit("Conectando", "Buscando el servicio MicroGas…")
        self.watchdog.start(15000)
        self.socket.connectToService(QBluetoothAddress(self.address), QBluetoothUuid(SERVICE_UUID))

    def connected(self):
        self.watchdog.stop()
        self.status.emit("Conectado", f"MicroGas · {self.address}")

    def read(self):
        if self.socket:
            self.attempt = 0
            # Qt readyRead is asynchronous: no blocking reads on the GUI thread.
            while self.socket and self.socket.bytesAvailable():
                self.data.emit(bytes(self.socket.read(4096)), time.monotonic())

    def failed(self, message):
        if self.stopped or self.retry.isActive():
            return
        self.watchdog.stop()
        self.drop_socket()
        delay = (5, 10, 20, 30)[min(self.attempt, 3)]
        self.attempt += 1
        self.status.emit("Reconectando", f"{message} · nuevo intento en {delay} s")
        self.retry.start(delay * 1000)

    def drop_socket(self):
        if self.socket:
            self.socket.blockSignals(True)
            self.socket.abort()
            self.socket.deleteLater()
            self.socket = None

    def stop(self):
        self.stopped = True
        self.watchdog.stop()
        self.retry.stop()
        self.drop_socket()
