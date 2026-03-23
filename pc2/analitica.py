"""
analitica.py - Servicio de Analítica de Tráfico - PC2 (Cerebro)

Este es el componente principal de procesamiento. Hace lo siguiente:
  1. Se suscribe al broker y recibe los eventos de los sensores (SUB)
  2. Evalúa las reglas de tráfico con las variables D, Vp, Q
  3. Envía los datos procesados a la BD principal (PUSH) y a la réplica (PUSH)
  4. Envía comandos al control de semáforos (PUSH)
  5. Atiende comandos del monitoreo como priorización de ambulancias (REP)

Si la BD principal (PC3) no responde, se activa el enmascaramiento de fallos
y los datos solo se guardan en la BD réplica (PC2).

Autores: Grupo X - Sistemas Distribuidos 2026-10
"""

import zmq
import json
import time
import threading
from datetime import datetime, timezone

# ============================================================
# CONFIGURACIÓN DE RED - Cambiar según las IPs de cada PC
# ============================================================
BROKER_IP = "192.168.1.100"         # PC1 - donde está el broker
ANALITICA_IP = "192.168.1.101"      # PC2 - esta máquina
BD_PRINCIPAL_IP = "192.168.1.102"   # PC3 - BD principal

# TODO: Implementar CurveZMQ para la entrega final

# Guardo el estado de cada intersección aquí
# Es un diccionario: {"INT-A1": {"Q": 0, "Vp": 50, "D": 0, "estado": "NORMAL"}, ...}
datos_intersecciones = {}

# Esta variable me dice si el PC3 está funcionando o no
pc3_esta_vivo = True

# Lock para que los hilos no se pisen al modificar datos_intersecciones
lock = threading.Lock()


def timestamp_ahora():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def evaluar_trafico(Q, Vp, D):
    """
    Evalúa el estado del tráfico usando las 3 variables:
      Q  = Longitud de cola (vehículos en espera) - viene de la cámara
      Vp = Velocidad promedio (km/h) - viene de cámara y GPS
      D  = Densidad de tráfico (veh/km) - viene del GPS

    Reglas:
      NORMAL:     Q < 5  AND Vp > 35 AND D < 20
      CONGESTION: Q >= 10 OR  Vp <= 15 OR  D >= 40
      INTERMEDIO: cualquier otro caso
    """
    # Primero verifico si hay congestión (tiene prioridad)
    if Q >= 10 or Vp <= 15 or D >= 40:
        return "CONGESTION"

    # Si todo está tranquilo, es tráfico normal
    if Q < 5 and Vp > 35 and D < 20:
        return "NORMAL"

    # Si no es ni una ni otra, es un estado intermedio
    return "INTERMEDIO"


def crear_comando_semaforo(interseccion, estado):
    """
    Crea un comando para enviar al servicio de semáforos.
    Dependiendo del estado, cambio la duración del verde:
      NORMAL -> 15 segundos (ciclo normal)
      CONGESTION -> 30 segundos (verde extendido)
      PRIORIZACION -> 45 segundos (ola verde)
    """
    if estado == "CONGESTION":
        return {
            "interseccion": interseccion,
            "accion": "EXTENDER_VERDE",
            "duracion_verde": 30,
            "motivo": "Congestión detectada",
            "timestamp": timestamp_ahora()
        }
    elif estado == "PRIORIZACION":
        return {
            "interseccion": interseccion,
            "accion": "OLA_VERDE",
            "duracion_verde": 45,
            "motivo": "Paso de emergencia",
            "timestamp": timestamp_ahora()
        }
    else:
        return {
            "interseccion": interseccion,
            "accion": "CICLO_NORMAL",
            "duracion_verde": 15,
            "motivo": "Tráfico normal",
            "timestamp": timestamp_ahora()
        }


def hilo_recibir_sensores(contexto):
    """
    Este hilo se suscribe al broker y recibe los eventos de los sensores.
    Por cada evento:
      1. Lo deserializo (unmarshalling del JSON)
      2. Actualizo las variables Q, Vp, D de la intersección
      3. Evalúo las reglas de tráfico
      4. Envío los datos a las dos BDs
      5. Si cambió el estado, envío comando al semáforo
    """
    global pc3_esta_vivo

    # Me suscribo al broker para recibir los eventos
    socket_sub = contexto.socket(zmq.SUB)
    socket_sub.connect(f"tcp://{BROKER_IP}:5556")
    socket_sub.setsockopt_string(zmq.SUBSCRIBE, "camara")
    socket_sub.setsockopt_string(zmq.SUBSCRIBE, "espira")
    socket_sub.setsockopt_string(zmq.SUBSCRIBE, "gps")

    # Socket PUSH para enviar datos a la BD principal (PC3)
    push_principal = contexto.socket(zmq.PUSH)
    push_principal.setsockopt(zmq.SNDTIMEO, 3000)  # Timeout explícito de envío
    push_principal.setsockopt(zmq.RCVTIMEO, 3000)  # Timeout explícito de recepción
    push_principal.setsockopt(zmq.LINGER, 0)
    push_principal.connect(f"tcp://{BD_PRINCIPAL_IP}:5570")

    # Socket PUSH para enviar datos a la BD réplica (PC2)
    push_replica = contexto.socket(zmq.PUSH)
    push_replica.connect(f"tcp://{ANALITICA_IP}:5562")

    # Socket PUSH para enviar comandos al control de semáforos
    push_semaforos = contexto.socket(zmq.PUSH)
    push_semaforos.bind(f"tcp://{ANALITICA_IP}:5563")

    # Uso un Poller para no quedarme bloqueado esperando mensajes
    poller = zmq.Poller()
    poller.register(socket_sub, zmq.POLLIN)

    print("[ANALÍTICA] Escuchando eventos de sensores...")
    print(f"[ANALÍTICA] Conectado al broker en tcp://{BROKER_IP}:5556")

    while True:
        # Espero hasta 2 segundos a que llegue un mensaje
        eventos = dict(poller.poll(2000))

        if socket_sub not in eventos:
            # No llegó nada, sigo esperando
            continue

        # Recibo el mensaje del broker
        mensaje = socket_sub.recv_string()

        # Separo el tópico del JSON: "camara {JSON}"
        partes = mensaje.split(" ", 1)
        if len(partes) != 2:
            continue

        topico = partes[0]
        json_str = partes[1]

        # Unmarshalling: convierto el JSON de vuelta a diccionario
        try:
            evento = json.loads(json_str)
        except:
            print("[ANALÍTICA] Error leyendo JSON, lo ignoro")
            continue

        interseccion = evento.get("interseccion", "DESCONOCIDA")

        # Actualizo los datos de la intersección
        lock.acquire()
        try:
            if interseccion not in datos_intersecciones:
                datos_intersecciones[interseccion] = {
                    "Q": 0, "Vp": 50, "D": 0, "estado": "NORMAL"
                }

            datos = datos_intersecciones[interseccion]

            # Actualizo las variables según el tipo de sensor
            if topico == "camara":
                datos["Q"] = evento.get("volumen", 0)
                datos["Vp"] = evento.get("velocidad_promedio", 50)
            elif topico == "gps":
                datos["D"] = evento.get("densidad", 0)
                vp_gps = evento.get("velocidad_promedio", None)
                if vp_gps is not None:
                    datos["Vp"] = (datos["Vp"] + vp_gps) / 2

            # Evalúo las reglas de tráfico
            estado_nuevo = evaluar_trafico(datos["Q"], datos["Vp"], datos["D"])
            estado_anterior = datos["estado"]
            datos["estado"] = estado_nuevo
        finally:
            lock.release()

        # Imprimo el resultado por pantalla
        print(f"\n[ANALÍTICA] Sensor: {topico} | Intersección: {interseccion}")
        print(f"[ANALÍTICA] Q={datos['Q']} | Vp={round(datos['Vp'], 1)} | D={datos['D']}")
        print(f"[ANALÍTICA] Estado: {estado_nuevo}")

        if estado_nuevo != estado_anterior:
            print(f"[ANALÍTICA] ** CAMBIO: {estado_anterior} -> {estado_nuevo} **")

        # Preparo el registro para guardar en la BD
        registro = {
            "interseccion": interseccion,
            "tipo_sensor": topico,
            "datos_sensor": evento,
            "estado_trafico": estado_nuevo,
            "Q": datos["Q"],
            "Vp": round(datos["Vp"], 1),
            "D": datos["D"],
            "timestamp_procesado": timestamp_ahora()
        }

        # Envío a la BD réplica (siempre se envía)
        try:
            push_replica.send_json(registro)
            print(f"[ANALÍTICA] -> Enviado a BD réplica (PC2)")
        except Exception as e:
            print(f"[ANALÍTICA] Error enviando a réplica: {e}")

        # Envío a la BD principal (PC3) - aquí puede fallar
        try:
            push_principal.send_json(registro, flags=zmq.NOBLOCK)
            if not pc3_esta_vivo:
                print(f"[ANALÍTICA] PC3 se recuperó!")
                pc3_esta_vivo = True
            else:
                print(f"[ANALÍTICA] -> Enviado a BD principal (PC3)")
        except zmq.Again:
            # ================================================
            # ENMASCARAMIENTO DE FALLOS:
            # Si no responde la BD principal, guardo en la réplica
            # El sistema NO se detiene, sigue funcionando
            # ================================================
            if pc3_esta_vivo:
                print(f"[ANALÍTICA] !!! FALLO: PC3 no responde !!!")
                print(f"[ANALÍTICA] !!! ENMASCARAMIENTO DE FALLOS ACTIVADO !!!")
                print(f"[ANALÍTICA] !!! Datos guardados SOLO en BD réplica !!!")
                pc3_esta_vivo = False
            else:
                print(f"[ANALÍTICA] PC3 sigue caído, datos en réplica")

        # Si cambió el estado, envío comando al semáforo
        if estado_nuevo != estado_anterior or estado_nuevo == "CONGESTION":
            comando = crear_comando_semaforo(interseccion, estado_nuevo)
            try:
                push_semaforos.send_json(comando)
                print(f"[ANALÍTICA] -> Comando enviado al semáforo: {comando['accion']}")
            except Exception as e:
                print(f"[ANALÍTICA] Error enviando al semáforo: {e}")


def hilo_atender_monitoreo(contexto):
    """
    Este hilo atiende las solicitudes del servicio de monitoreo.
    Usa el patrón REQ/REP. El monitoreo puede:
      - Pedir el estado actual de una intersección
      - Forzar un cambio de semáforo (ambulancia)
    """
    # Socket REP para recibir solicitudes del monitoreo
    socket_rep = contexto.socket(zmq.REP)
    socket_rep.setsockopt(zmq.SNDTIMEO, 3000)
    socket_rep.setsockopt(zmq.RCVTIMEO, 3000)
    socket_rep.bind(f"tcp://{ANALITICA_IP}:5560")

    # Socket PUSH para reenviar comandos al semáforo
    push_semaforos = contexto.socket(zmq.PUSH)
    push_semaforos.connect(f"tcp://{ANALITICA_IP}:5563")

    # Uso un Poller para no quedarme bloqueado
    poller = zmq.Poller()
    poller.register(socket_rep, zmq.POLLIN)

    print(f"[ANALÍTICA-REP] Esperando comandos de monitoreo en tcp://{ANALITICA_IP}:5560")

    while True:
        # Espero hasta 2 segundos
        eventos = dict(poller.poll(2000))

        if socket_rep not in eventos:
            continue

        # Recibo la solicitud del monitoreo
        solicitud = socket_rep.recv_json()
        tipo = solicitud.get("tipo", "")

        if tipo == "PRIORIZACION":
            # El usuario quiere priorizar una vía (ej. ambulancia)
            interseccion = solicitud.get("interseccion", "")
            motivo = solicitud.get("motivo", "Priorización")

            print(f"\n[ANALÍTICA-REP] PRIORIZACIÓN para {interseccion}: {motivo}")

            # Actualizo el estado de la intersección
            lock.acquire()
            try:
                if interseccion in datos_intersecciones:
                    datos_intersecciones[interseccion]["estado"] = "PRIORIZACION"
            finally:
                lock.release()

            # Envío comando de OLA VERDE al semáforo
            comando = crear_comando_semaforo(interseccion, "PRIORIZACION")
            try:
                push_semaforos.send_json(comando)
                print(f"[ANALÍTICA-REP] -> OLA VERDE enviada para {interseccion}")
            except:
                pass

            # Respondo al monitoreo
            respuesta = {
                "status": "OK",
                "mensaje": f"Priorización activada en {interseccion}",
                "comando": comando
            }
            socket_rep.send_json(respuesta)

        elif tipo == "ESTADO_ACTUAL":
            # El usuario quiere saber el estado de una intersección
            interseccion = solicitud.get("interseccion", "")
            print(f"[ANALÍTICA-REP] Consulta de estado: {interseccion}")

            lock.acquire()
            try:
                datos = datos_intersecciones.get(interseccion, None)
            finally:
                lock.release()

            if datos:
                respuesta = {
                    "status": "OK",
                    "interseccion": interseccion,
                    "Q": datos["Q"],
                    "Vp": round(datos["Vp"], 1),
                    "D": datos["D"],
                    "estado_trafico": datos["estado"]
                }
            else:
                respuesta = {
                    "status": "OK",
                    "mensaje": f"No hay datos para {interseccion}"
                }
            socket_rep.send_json(respuesta)

        else:
            # Tipo desconocido
            respuesta = {"status": "ERROR", "mensaje": f"No entiendo: {tipo}"}
            socket_rep.send_json(respuesta)


def main():
    print("=" * 60)
    print("  SERVICIO DE ANALÍTICA - PC2 (Cerebro)")
    print("=" * 60)
    print(f"  Broker:       tcp://{BROKER_IP}:5556")
    print(f"  BD Principal: tcp://{BD_PRINCIPAL_IP}:5570")
    print(f"  BD Réplica:   tcp://{ANALITICA_IP}:5562")
    print(f"  Semáforos:    tcp://{ANALITICA_IP}:5563")
    print(f"  Monitoreo:    tcp://{ANALITICA_IP}:5560")
    print("=" * 60)
    print("  Reglas de tráfico:")
    print("    NORMAL:     Q < 5  AND Vp > 35 AND D < 20")
    print("    CONGESTION: Q >= 10 OR Vp <= 15 OR D >= 40")
    print("    PRIORIZACION: Comando directo de monitoreo")
    print("=" * 60)

    contexto = zmq.Context()

    # Inicio el hilo que recibe eventos de sensores
    t1 = threading.Thread(target=hilo_recibir_sensores, args=(contexto,), daemon=True)
    t1.start()

    # Inicio el hilo que atiende al monitoreo
    t2 = threading.Thread(target=hilo_atender_monitoreo, args=(contexto,), daemon=True)
    t2.start()

    print("\n[ANALÍTICA] Servicio corriendo. Ctrl+C para detener.\n")

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\n[ANALÍTICA] Cerrando...")
        print("[ANALÍTICA] Listo.")


if __name__ == "__main__":
    main()
