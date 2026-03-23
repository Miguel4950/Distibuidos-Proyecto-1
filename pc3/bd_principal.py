# bd_principal.py - base de datos principal - pc3 (persistencia)
#
# este servicio es la bd principal del sistema. hace dos cosas:
#   1. recibe datos procesados de la analítica (pull) y los guarda en sqlite
#   2. responde consultas del servicio de monitoreo (rep)
#
# si este servicio se cae, la analítica activa el enmascaramiento de fallos
# y el monitoreo se conecta automáticamente a la bd réplica en pc2.
#
# autores: miguel angel acuña, juan david acuña, y samuel felipe manrique - sistemas distribuidos 2026-10

import zmq
import json
import sqlite3
import threading
import time
from datetime import datetime, timezone

# ============================================================
# CONFIGURACIÓN DE RED
# ============================================================
BD_IP = "10.43.99.183"   # PC3 - esta máquina
PUERTO_PULL = 5570        # Puerto para recibir datos de analítica
PUERTO_REP = 5571         # Puerto para consultas del monitoreo

BD_ARCHIVO = "trafico.db"   # Nombre del archivo SQLite

# TODO: Implementar CurveZMQ para la entrega final


def timestamp_ahora():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def crear_tablas():
    # creo las tablas de la bd si no existen.
    # son las mismas tablas que en la bd réplica.
    conn = sqlite3.connect(BD_ARCHIVO)
    cursor = conn.cursor()

    # Tabla principal de eventos de tráfico
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

    # Tabla de historial de semáforos
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

    # Tabla de acciones de control
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
    print(f"[BD-PRINCIPAL] Base de datos '{BD_ARCHIVO}' lista")


def guardar_evento(registro):
    # guarda un evento procesado en la bd usando sql crudo.
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
    # este hilo recibe datos procesados de la analítica (pull).
    # cada dato que llega lo guardo en la bd sqlite.
    socket = contexto.socket(zmq.PULL)
    socket.bind(f"tcp://{BD_IP}:{PUERTO_PULL}")

    poller = zmq.Poller()
    poller.register(socket, zmq.POLLIN)

    print(f"[BD-PRINCIPAL] PULL esperando datos en tcp://{BD_IP}:{PUERTO_PULL}")

    while True:
        # Espero hasta 2 segundos
        eventos = dict(poller.poll(2000))

        if socket not in eventos:
            continue

        # Recibo el registro como diccionario
        registro = socket.recv_json()

        # Lo guardo en la BD
        total = guardar_evento(registro)

        inter = registro.get("interseccion", "?")
        estado = registro.get("estado_trafico", "?")
        tipo = registro.get("tipo_sensor", "?")
        print(f"[BD-PRINCIPAL] Guardado: {inter} | {tipo} | {estado} | Total: {total}")


def hilo_consultas(contexto):
    # este hilo responde consultas del servicio de monitoreo (rep).
    # hace selects directos a la bd sqlite y devuelve los resultados.
    socket = contexto.socket(zmq.REP)
    socket.bind(f"tcp://{BD_IP}:{PUERTO_REP}")

    poller = zmq.Poller()
    poller.register(socket, zmq.POLLIN)

    print(f"[BD-PRINCIPAL] REP esperando consultas en tcp://{BD_IP}:{PUERTO_REP}")

    while True:
        eventos = dict(poller.poll(2000))

        if socket not in eventos:
            continue

        # Recibo la consulta del monitoreo
        consulta = socket.recv_json()
        tipo = consulta.get("tipo", "")

        print(f"[BD-PRINCIPAL] Consulta: {tipo}")

        conn = sqlite3.connect(BD_ARCHIVO)
        cursor = conn.cursor()

        if tipo == "CONSULTA_HISTORICA":
            # Busco eventos entre dos fechas (útil para horas pico)
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
                "status": "OK", "fuente": "BD_PRINCIPAL",
                "total": len(resultados), "resultados": resultados
            }

        elif tipo == "CONSULTA_INTERSECCION":
            # Busco los últimos datos de una intersección específica
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
                "status": "OK", "fuente": "BD_PRINCIPAL",
                "interseccion": inter,
                "total": len(resultados), "resultados": resultados
            }

        elif tipo == "CONSULTA_ESTADOS":
            # Resumen: cuántos eventos hay por cada estado de tráfico
            cursor.execute("""
                SELECT estado_trafico, COUNT(*) FROM eventos_trafico
                GROUP BY estado_trafico ORDER BY COUNT(*) DESC
            """)
            resumen = {}
            for fila in cursor.fetchall():
                resumen[fila[0]] = fila[1]

            respuesta = {
                "status": "OK", "fuente": "BD_PRINCIPAL",
                "resumen_estados": resumen
            }

        elif tipo == "CONSULTA_THROUGHPUT":
            # Total de registros en la BD (para medir rendimiento)
            total = cursor.execute("SELECT COUNT(*) FROM eventos_trafico").fetchone()[0]
            respuesta = {
                "status": "OK", "fuente": "BD_PRINCIPAL",
                "total_registros": total
            }

        else:
            respuesta = {"status": "ERROR", "mensaje": f"No entiendo: {tipo}"}

        conn.close()
        socket.send_json(respuesta)
        print(f"[BD-PRINCIPAL] Respuesta enviada")


def main():
    print("=" * 60)
    print("  BASE DE DATOS PRINCIPAL - PC3 (Persistencia)")
    print("=" * 60)
    print(f"  PULL datos:    tcp://{BD_IP}:{PUERTO_PULL}")
    print(f"  REP consultas: tcp://{BD_IP}:{PUERTO_REP}")
    print(f"  Archivo BD:    {BD_ARCHIVO}")
    print("=" * 60)

    # Creo las tablas si no existen
    crear_tablas()

    contexto = zmq.Context()

    # Hilo para recibir datos de la analítica
    t1 = threading.Thread(target=hilo_recibir_datos, args=(contexto,), daemon=True)
    t1.start()

    # Hilo para atender consultas del monitoreo
    t2 = threading.Thread(target=hilo_consultas, args=(contexto,), daemon=True)
    t2.start()

    print("[BD-PRINCIPAL] Servicio corriendo. Ctrl+C para detener.\n")

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\n[BD-PRINCIPAL] Cerrando...")
        print("[BD-PRINCIPAL] Listo.")


if __name__ == "__main__":
    main()
