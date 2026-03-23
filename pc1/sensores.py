# sensores.py - simulación de sensores de tráfico - pc1
# 
# este script simula los 3 tipos de sensores de tráfico que hay 
# en la ciudad:
#   1. cámaras de tráfico -> miden longitud de cola (q) y velocidad (vp)
#   2. espiras inductivas -> cuentan vehículos que pasan (cv)
#   3. sensores gps -> miden densidad (d) y velocidad promedio (vp)
# 
# la ciudad es una cuadrícula de 5x5 (filas a-e, columnas 1-5).
# cada sensor genera un evento json cada cierto tiempo y lo publica
# al broker usando pub/sub de zeromq.
# 
# autores: miguel angel acuña, juan david acuña, y samuel felipe manrique - sistemas distribuidos 2026-10

import zmq
import json
import time
import random
import threading
from datetime import datetime, timezone

# ============================================================
# CONFIGURACIÓN - Cambiar la IP del broker (PC1)
# ============================================================
BROKER_IP = "10.43.98.198"
PUERTO_BROKER = 5555  # Puerto XSUB del broker

INTERVALO = 10  # Segundos entre cada evento generado

# Cuadrícula de la ciudad 5x5
FILAS = ["A", "B", "C", "D", "E"]
COLUMNAS = [1, 2, 3, 4, 5]

# Intersecciones que vamos a usar para la demo
INTERSECCIONES = [("A", 1), ("B", 3), ("C", 5), ("D", 2), ("E", 4)]

# TODO: Implementar CurveZMQ para la entrega final


def timestamp_ahora():
    # devuelve la fecha y hora actual en formato iso 8601.
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def generar_evento_camara(fila, col):
    # genera un evento de cámara de tráfico.
    # mide la longitud de cola (q) y velocidad promedio (vp).
    evento = {
        "sensor_id": f"CAM-{fila}{col}",
        "tipo_sensor": "camara",
        "interseccion": f"INT-{fila}{col}",
        "volumen": random.randint(0, 30),          # Q - vehículos en cola
        "velocidad_promedio": round(random.uniform(0, 50), 1),  # Vp - km/h
        "timestamp": timestamp_ahora()
    }
    return evento


def generar_evento_espira(fila, col):
    # genera un evento de espira inductiva.
    # cuenta cuántos vehículos pasaron en un intervalo de 
    # 30 segundos.
    evento = {
        "sensor_id": f"ESP-{fila}{col}",
        "tipo_sensor": "espira_inductiva",
        "interseccion": f"INT-{fila}{col}",
        "vehiculos_contados": random.randint(0, 40),
        "intervalo_segundos": 30,
        "timestamp_inicio": timestamp_ahora(),
        "timestamp_fin": timestamp_ahora()
    }
    return evento


def generar_evento_gps(fila, col):
    # genera un evento del sensor gps.
    # mide velocidad promedio (vp) y densidad de tráfico (d).
    # el nivel de congestión se determina por la velocidad.
    velocidad = round(random.uniform(0, 60), 1)

    # Determino el nivel de congestión según la velocidad
    if velocidad < 10:
        nivel = "ALTA"
        densidad = random.randint(40, 80)
    elif velocidad <= 40:
        nivel = "NORMAL"
        densidad = random.randint(15, 45)
    else:
        nivel = "BAJA"
        densidad = random.randint(1, 20)

    evento = {
        "sensor_id": f"GPS-{fila}{col}",
        "tipo_sensor": "gps",
        "interseccion": f"INT-{fila}{col}",
        "nivel_congestion": nivel,
        "velocidad_promedio": velocidad,
        "densidad": densidad,
        "timestamp": timestamp_ahora()
    }
    return evento


def ejecutar_sensor(tipo, fila, col, contexto):
    # función que ejecuta un sensor individual en un hilo.
    # cada sensor genera eventos periódicamente y los publica al broker.
    # 
    # el mensaje se envía como: "topico {json}"
    # donde topico es: camara, espira, o gps
    # Creo el socket PUB y me conecto al broker
    socket = contexto.socket(zmq.PUB)
    socket.connect(f"tcp://{BROKER_IP}:{PUERTO_BROKER}")

    nombre = f"{tipo.upper()[:3]}-{fila}{col}"
    print(f"[SENSOR] {nombre} conectado al broker")

    # Espero un poco para que la conexión se estabilice
    time.sleep(1)

    while True:
        # Genero el evento según el tipo de sensor
        if tipo == "camara":
            evento = generar_evento_camara(fila, col)
            topico = "camara"
        elif tipo == "espira":
            evento = generar_evento_espira(fila, col)
            topico = "espira"
        else:
            evento = generar_evento_gps(fila, col)
            topico = "gps"

        # Marshalling: convierto el diccionario a JSON (representación externa)
        mensaje_json = json.dumps(evento)

        # Envío el mensaje al broker: "topico JSON"
        socket.send_string(f"{topico} {mensaje_json}")

        print(f"[SENSOR] {nombre} -> {topico} | {evento['interseccion']}")

        # Espero el intervalo antes de generar otro evento
        time.sleep(INTERVALO)


def main():
    print("=" * 60)
    print("  SENSORES DE TRÁFICO - PC1")
    print("=" * 60)
    print(f"  Broker: tcp://{BROKER_IP}:{PUERTO_BROKER}")
    print(f"  Intervalo: {INTERVALO} segundos")
    print(f"  Intersecciones: {len(INTERSECCIONES)}")
    print("=" * 60)

    contexto = zmq.Context()
    hilos = []

    # Creo un hilo por cada sensor en cada intersección
    for fila, col in INTERSECCIONES:
        for tipo in ["camara", "espira", "gps"]:
            t = threading.Thread(
                target=ejecutar_sensor,
                args=(tipo, fila, col, contexto),
                daemon=True
            )
            t.start()
            hilos.append(t)

    total = len(INTERSECCIONES) * 3
    print(f"\n[SENSORES] {total} sensores iniciados")
    print("[SENSORES] Presione Ctrl+C para detener\n")

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\n[SENSORES] Cerrando sensores...")
        print("[SENSORES] Listo.")


if __name__ == "__main__":
    main()
