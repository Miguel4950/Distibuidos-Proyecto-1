"""
control_semaforos.py - Control de Semáforos - PC2

Este servicio recibe comandos de la analítica (PULL) y cambia el estado
de los semáforos en cada intersección.

Los semáforos alternan entre VERDE y ROJO:
  - Ciclo normal: 15 segundos verde, 15 segundos rojo
  - Congestión: 30 segundos verde
  - Priorización (ambulancia): 45 segundos verde

Autores: Grupo X - Sistemas Distribuidos 2026-10
"""

import zmq
import json
import time
import threading
from datetime import datetime, timezone

# ============================================================
# CONFIGURACIÓN DE RED
# ============================================================
ANALITICA_IP = "192.168.1.101"  # PC2 - esta máquina

# TODO: Implementar CurveZMQ para la entrega final

# Diccionario con el estado de cada semáforo
# {"INT-A1": {"luz": "ROJO", "verde_seg": 15, "rojo_seg": 15, "ultimo_cambio": timestamp}}
semaforos = {}
lock = threading.Lock()


def timestamp_ahora():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def hilo_ciclo_automatico():
    """
    Este hilo cambia automáticamente los semáforos de VERDE a ROJO
    y viceversa cuando se cumple el tiempo configurado.
    """
    while True:
        time.sleep(1)  # Reviso cada segundo

        lock.acquire()
        try:
            for inter, datos in semaforos.items():
                tiempo = time.time() - datos["ultimo_cambio"]

                if datos["luz"] == "VERDE" and tiempo >= datos["verde_seg"]:
                    # Se acabó el verde, cambio a rojo
                    datos["luz"] = "ROJO"
                    datos["ultimo_cambio"] = time.time()
                    print(f"[SEMÁFORO] {inter}: VERDE -> ROJO (automático, {datos['verde_seg']}s)")

                elif datos["luz"] == "ROJO" and tiempo >= datos["rojo_seg"]:
                    # Se acabó el rojo, cambio a verde
                    datos["luz"] = "VERDE"
                    datos["ultimo_cambio"] = time.time()
                    print(f"[SEMÁFORO] {inter}: ROJO -> VERDE (automático, {datos['rojo_seg']}s)")
        finally:
            lock.release()


def hilo_recibir_comandos(contexto):
    """
    Este hilo recibe comandos de la analítica usando PULL.
    Los comandos pueden ser:
      - CICLO_NORMAL: volver al ciclo de 15s
      - EXTENDER_VERDE: poner verde por 30s (congestión)
      - OLA_VERDE: verde inmediato por 45s (ambulancia)
    """
    # Socket PULL para recibir comandos
    socket = contexto.socket(zmq.PULL)
    socket.connect(f"tcp://{ANALITICA_IP}:5563")

    # Uso un Poller para no quedarme bloqueado
    poller = zmq.Poller()
    poller.register(socket, zmq.POLLIN)

    print(f"[SEMÁFOROS] Esperando comandos en tcp://{ANALITICA_IP}:5563")

    while True:
        # Espero hasta 2 segundos
        eventos = dict(poller.poll(2000))

        if socket not in eventos:
            continue

        # Recibo el comando
        comando = socket.recv_json()

        interseccion = comando.get("interseccion", "?")
        accion = comando.get("accion", "")
        verde_seg = comando.get("duracion_verde", 15)
        motivo = comando.get("motivo", "")

        print(f"\n[SEMÁFOROS] Comando recibido: {accion} para {interseccion}")

        lock.acquire()
        try:
            # Si es la primera vez que veo esta intersección, la creo
            if interseccion not in semaforos:
                semaforos[interseccion] = {
                    "luz": "ROJO",
                    "verde_seg": 15,
                    "rojo_seg": 15,
                    "ultimo_cambio": time.time()
                }

            sem = semaforos[interseccion]

            if accion == "OLA_VERDE":
                # Priorización: verde inmediato con duración extendida
                sem["luz"] = "VERDE"
                sem["verde_seg"] = verde_seg
                sem["ultimo_cambio"] = time.time()
                print(f"[SEMÁFOROS] {interseccion}: OLA VERDE activada ({verde_seg}s)")
                print(f"[SEMÁFOROS] Motivo: {motivo}")

            elif accion == "EXTENDER_VERDE":
                # Congestión: extiendo el verde
                sem["luz"] = "VERDE"
                sem["verde_seg"] = verde_seg
                sem["ultimo_cambio"] = time.time()
                print(f"[SEMÁFOROS] {interseccion}: Verde extendido a {verde_seg}s")

            elif accion == "CICLO_NORMAL":
                # Vuelvo al ciclo normal
                sem["verde_seg"] = 15
                sem["rojo_seg"] = 15
                print(f"[SEMÁFOROS] {interseccion}: Ciclo normal restaurado (15s)")

            else:
                print(f"[SEMÁFOROS] Acción desconocida: {accion}")
        finally:
            lock.release()


def main():
    print("=" * 60)
    print("  CONTROL DE SEMÁFOROS - PC2")
    print("=" * 60)
    print(f"  PULL desde analítica: tcp://{ANALITICA_IP}:5563")
    print("  Ciclos: Normal=15s | Congestión=30s | Emergencia=45s")
    print("=" * 60)

    contexto = zmq.Context()

    # Hilo que recibe los comandos de la analítica
    t1 = threading.Thread(target=hilo_recibir_comandos, args=(contexto,), daemon=True)
    t1.start()

    # Hilo que hace el ciclo automático de los semáforos
    t2 = threading.Thread(target=hilo_ciclo_automatico, daemon=True)
    t2.start()

    print("[SEMÁFOROS] Servicio corriendo. Ctrl+C para detener.\n")

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\n[SEMÁFOROS] Cerrando...")
        print("[SEMÁFOROS] Listo.")


if __name__ == "__main__":
    main()
