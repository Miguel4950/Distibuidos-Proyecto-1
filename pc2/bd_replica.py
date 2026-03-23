"""
bd_replica.py - Base de Datos Réplica - PC2

Este servicio hace dos cosas:
  1. Recibe datos procesados de la analítica (PULL) y los guarda en SQLite
  2. Responde consultas del monitoreo (REP) cuando el PC3 está caído

La BD réplica es idéntica a la BD principal. Si el PC3 falla,
el monitoreo se conecta aquí automáticamente (enmascaramiento de fallos).

Autores: Grupo X - Sistemas Distribuidos 2026-10
"""

import zmq
import json
import sqlite3
import threading
import time
from datetime import datetime, timezone

# ============================================================
# CONFIGURACIÓN DE RED
# ============================================================
REPLICA_IP = "192.168.1.101"   # PC2 - esta máquina
PUERTO_PULL = 5562             # Puerto para recibir datos de analítica
PUERTO_REP = 5564              # Puerto para consultas del monitoreo (failover)

BD_ARCHIVO = "replica.db"      # Nombre del archivo SQLite

# TODO: Implementar CurveZMQ para la entrega final


def timestamp_ahora():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def crear_tablas():
    """
    Creo las tablas en SQLite si no existen.
    Es el mismo esquema que la BD principal para mantener consistencia.
    """
    conn = sqlite3.connect(BD_ARCHIVO)
    cursor = conn.cursor()

    # Tabla para guardar los eventos de tráfico procesados
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS eventos_trafico (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            interseccion TEXT,
            tipo_sensor TEXT,
            datos_sensor TEXT,
            estado_trafico TEXT,
            Q REAL,
            Vp REAL,
            D REAL,
            timestamp_procesado TEXT,
            timestamp_insercion TEXT
        )
    """)

    # Tabla para guardar el historial de estados de semáforos
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS estados_semaforo (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            interseccion TEXT,
            estado_anterior TEXT,
            estado_nuevo TEXT,
            duracion_verde INTEGER,
            motivo TEXT,
            timestamp TEXT
        )
    """)

    # Tabla para guardar acciones de control
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS acciones_control (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            interseccion TEXT,
            tipo_accion TEXT,
            detalles TEXT,
            timestamp TEXT
        )
    """)

    conn.commit()
    conn.close()
    print(f"[BD-RÉPLICA] Base de datos '{BD_ARCHIVO}' lista")


def guardar_evento(registro):
    """
    Guarda un evento procesado en la tabla eventos_trafico.
    Uso SQL crudo con cursor.execute() directamente.
    """
    conn = sqlite3.connect(BD_ARCHIVO)
    cursor = conn.cursor()

    cursor.execute("""
        INSERT INTO eventos_trafico 
        (interseccion, tipo_sensor, datos_sensor, estado_trafico, Q, Vp, D,
         timestamp_procesado, timestamp_insercion)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    """, (
        registro.get("interseccion", ""),
        registro.get("tipo_sensor", ""),
        json.dumps(registro.get("datos_sensor", {})),
        registro.get("estado_trafico", ""),
        registro.get("Q", 0),
        registro.get("Vp", 0),
        registro.get("D", 0),
        registro.get("timestamp_procesado", ""),
        timestamp_ahora()
    ))

    conn.commit()
    total = cursor.execute("SELECT COUNT(*) FROM eventos_trafico").fetchone()[0]
    conn.close()

    return total


def hilo_recibir_datos(contexto):
    """
    Hilo que recibe datos procesados de la analítica (PULL).
    Cada dato que llega lo guardo en la BD SQLite.
    """
    socket = contexto.socket(zmq.PULL)
    socket.bind(f"tcp://{REPLICA_IP}:{PUERTO_PULL}")

    # Uso Poller para no quedarme bloqueado
    poller = zmq.Poller()
    poller.register(socket, zmq.POLLIN)

    print(f"[BD-RÉPLICA] PULL esperando datos en tcp://{REPLICA_IP}:{PUERTO_PULL}")

    while True:
        eventos = dict(poller.poll(2000))

        if socket not in eventos:
            continue

        # Recibo el registro (ya viene como diccionario gracias a recv_json)
        registro = socket.recv_json()

        # Lo guardo en la BD
        total = guardar_evento(registro)

        inter = registro.get("interseccion", "?")
        estado = registro.get("estado_trafico", "?")
        print(f"[BD-RÉPLICA] Guardado: {inter} | {estado} | Total: {total}")


def hilo_consultas(contexto):
    """
    Hilo REP que responde consultas del monitoreo cuando el PC3 está caído.
    El monitoreo se conecta aquí automáticamente si el PC3 no responde.
    
    Tipos de consulta:
      - CONSULTA_HISTORICA: eventos entre dos fechas
      - CONSULTA_INTERSECCION: datos de una intersección
      - CONSULTA_ESTADOS: resumen de cuántos eventos por estado
      - CONSULTA_THROUGHPUT: total de registros en la BD
    """
    socket = contexto.socket(zmq.REP)
    socket.bind(f"tcp://{REPLICA_IP}:{PUERTO_REP}")

    poller = zmq.Poller()
    poller.register(socket, zmq.POLLIN)

    print(f"[BD-RÉPLICA] REP esperando consultas en tcp://{REPLICA_IP}:{PUERTO_REP}")

    while True:
        eventos = dict(poller.poll(2000))

        if socket not in eventos:
            continue

        consulta = socket.recv_json()
        tipo = consulta.get("tipo", "")

        print(f"[BD-RÉPLICA] Consulta recibida: {tipo}")

        conn = sqlite3.connect(BD_ARCHIVO)
        cursor = conn.cursor()

        if tipo == "CONSULTA_HISTORICA":
            # Busco eventos entre dos fechas
            inicio = consulta.get("fecha_inicio", "")
            fin = consulta.get("fecha_fin", "")

            cursor.execute("""
                SELECT interseccion, tipo_sensor, estado_trafico, Q, Vp, D,
                       timestamp_procesado
                FROM eventos_trafico
                WHERE timestamp_procesado BETWEEN ? AND ?
                ORDER BY timestamp_procesado DESC
                LIMIT 100
            """, (inicio, fin))

            resultados = []
            for fila in cursor.fetchall():
                resultados.append({
                    "interseccion": fila[0], "tipo_sensor": fila[1],
                    "estado_trafico": fila[2], "Q": fila[3],
                    "Vp": fila[4], "D": fila[5], "timestamp": fila[6]
                })

            respuesta = {
                "status": "OK", "fuente": "BD_REPLICA",
                "total": len(resultados), "resultados": resultados
            }

        elif tipo == "CONSULTA_INTERSECCION":
            # Busco los últimos datos de una intersección
            inter = consulta.get("interseccion", "")

            cursor.execute("""
                SELECT tipo_sensor, estado_trafico, Q, Vp, D, timestamp_procesado
                FROM eventos_trafico
                WHERE interseccion = ?
                ORDER BY timestamp_procesado DESC
                LIMIT 10
            """, (inter,))

            resultados = []
            for fila in cursor.fetchall():
                resultados.append({
                    "tipo_sensor": fila[0], "estado_trafico": fila[1],
                    "Q": fila[2], "Vp": fila[3], "D": fila[4],
                    "timestamp": fila[5]
                })

            respuesta = {
                "status": "OK", "fuente": "BD_REPLICA",
                "interseccion": inter,
                "total": len(resultados), "resultados": resultados
            }

        elif tipo == "CONSULTA_ESTADOS":
            cursor.execute("""
                SELECT estado_trafico, COUNT(*) FROM eventos_trafico
                GROUP BY estado_trafico ORDER BY COUNT(*) DESC
            """)
            resumen = {}
            for fila in cursor.fetchall():
                resumen[fila[0]] = fila[1]

            respuesta = {
                "status": "OK", "fuente": "BD_REPLICA",
                "resumen_estados": resumen
            }

        elif tipo == "CONSULTA_THROUGHPUT":
            total = cursor.execute("SELECT COUNT(*) FROM eventos_trafico").fetchone()[0]
            respuesta = {
                "status": "OK", "fuente": "BD_REPLICA",
                "total_registros": total
            }

        else:
            respuesta = {"status": "ERROR", "mensaje": f"No entiendo: {tipo}"}

        conn.close()
        socket.send_json(respuesta)
        print(f"[BD-RÉPLICA] Respuesta enviada")


def main():
    print("=" * 60)
    print("  BASE DE DATOS RÉPLICA - PC2")
    print("=" * 60)
    print(f"  PULL datos:    tcp://{REPLICA_IP}:{PUERTO_PULL}")
    print(f"  REP consultas: tcp://{REPLICA_IP}:{PUERTO_REP}")
    print(f"  Archivo BD:    {BD_ARCHIVO}")
    print("=" * 60)

    # Creo las tablas si no existen
    crear_tablas()

    contexto = zmq.Context()

    # Hilo para recibir datos de la analítica
    t1 = threading.Thread(target=hilo_recibir_datos, args=(contexto,), daemon=True)
    t1.start()

    # Hilo para atender consultas del monitoreo (failover)
    t2 = threading.Thread(target=hilo_consultas, args=(contexto,), daemon=True)
    t2.start()

    print("[BD-RÉPLICA] Servicio corriendo. Ctrl+C para detener.\n")

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\n[BD-RÉPLICA] Cerrando...")
        print("[BD-RÉPLICA] Listo.")


if __name__ == "__main__":
    main()
