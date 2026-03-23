# monitoreo.py - servicio de monitoreo y consulta - pc3
#
# este es el servicio que usa el usuario (operador) para interactuar
# con el sistema de tráfico. tiene un menú por consola con estas opciones:
#
#   1. ver estado actual de una intersección (pregunta a analítica)
#   2. consulta histórica entre fechas (pregunta a bd principal)
#   3. ver resumen de estados de congestión (pregunta a bd)
#   4. forzar cambio de semáforo - ambulancia (envía a analítica)
#   5. ver throughput del sistema (pregunta a bd)
#   6. consultar datos de intersección en bd
#
# tolerancia a fallos:
# si la bd principal (pc3) no responde, este servicio cambia
# automáticamente para consultar a la bd réplica (pc2).
# el programa no se cierra, sigue funcionando normal.
#
# autores: miguel angel acuña, juan david acuña, y samuel felipe manrique - sistemas distribuidos 2026-10

import zmq
import json
import time
from datetime import datetime, timezone

# ============================================================
# CONFIGURACIÓN DE RED - Cambiar según las IPs
# ============================================================
ANALITICA_IP = "10.43.98.199"      # PC2 - servicio de analítica
BD_PRINCIPAL_IP = "10.43.99.183"   # PC3 - BD principal (esta máquina)
BD_REPLICA_IP = "10.43.98.199"     # PC2 - BD réplica (fallback)

TIMEOUT = 5000  # Timeout en milisegundos (5 segundos)

# TODO: Implementar CurveZMQ para la entrega final

# Esta variable indica si estamos usando la réplica por fallo
usando_replica = False


def crear_socket(contexto, tipo, endpoint):
    # crea un socket zmq simple con timeout configurado.
    socket = contexto.socket(tipo)
    socket.setsockopt(zmq.RCVTIMEO, TIMEOUT)
    socket.setsockopt(zmq.SNDTIMEO, TIMEOUT)
    socket.setsockopt(zmq.LINGER, 0)
    socket.connect(endpoint)
    return socket


def consultar_bd(contexto, socket_bd, consulta, endpoint_actual):
    # envía una consulta a la bd y maneja el failover automático.
    # 
    # si la bd principal no responde, cierro el socket viejo,
    # creo uno nuevo conectado a la bd réplica y reintento.
    # el programa nunca se cierra por esto.
    global usando_replica

    try:
        # Intento enviar y recibir la consulta
        socket_bd.send_json(consulta)
        respuesta = socket_bd.recv_json()
        return respuesta, socket_bd, endpoint_actual

    except (zmq.Again, zmq.ZMQError) as error:
        # La BD no respondió a tiempo
        if not usando_replica:
            # ====================================================
            # ENMASCARAMIENTO DE FALLOS TRANSPARENTE:
            # La BD principal no responde, me reconecto a la réplica.
            # El usuario puede seguir usando el sistema normal.
            # ====================================================
            print(f"\n[MONITOREO] !!! BD Principal no responde: {error}")
            print(f"[MONITOREO] !!! Cambiando a BD Réplica (PC2)...")

            # Cierro el socket viejo y creo uno nuevo a la réplica
            try:
                socket_bd.close()
            except:
                pass

            endpoint_nuevo = f"tcp://{BD_REPLICA_IP}:5564"
            socket_bd = crear_socket(contexto, zmq.REQ, endpoint_nuevo)
            usando_replica = True
            endpoint_actual = endpoint_nuevo

            # Reintento la consulta en la réplica
            try:
                socket_bd.send_json(consulta)
                respuesta = socket_bd.recv_json()
                print(f"[MONITOREO] Consulta exitosa en BD Réplica")
                return respuesta, socket_bd, endpoint_actual
            except (zmq.Again, zmq.ZMQError) as error2:
                print(f"[MONITOREO] BD Réplica tampoco responde: {error2}")
                # Recreo el socket para la próxima vez
                try:
                    socket_bd.close()
                except:
                    pass
                socket_bd = crear_socket(contexto, zmq.REQ, endpoint_nuevo)
                return {"status": "ERROR", "mensaje": "Ninguna BD disponible"}, \
                       socket_bd, endpoint_actual
        else:
            # Ya estamos en la réplica y tampoco responde
            print(f"[MONITOREO] BD Réplica no responde: {error}")
            try:
                socket_bd.close()
            except:
                pass
            socket_bd = crear_socket(contexto, zmq.REQ, f"tcp://{BD_REPLICA_IP}:5564")
            return {"status": "ERROR", "mensaje": "BD Réplica no disponible"}, \
                   socket_bd, endpoint_actual


def mostrar_menu():
    # muestra el menú principal.
    print("\n" + "=" * 55)
    print("   MONITOREO Y CONSULTA - Sistema de Tráfico")
    print("=" * 55)
    print("   1. Estado actual de una intersección")
    print("   2. Consulta histórica (rango de fechas)")
    print("   3. Resumen de estados de congestión")
    print("   4. Forzar semáforo (ambulancia)")
    print("   5. Ver throughput del sistema")
    print("   6. Consultar intersección en BD")
    print("   0. Salir")
    print("=" * 55)
    if usando_replica:
        print("   !! MODO FAILOVER: Usando BD Réplica (PC2)")
    else:
        print("   BD Principal (PC3) conectada")
    print()


def main():
    global usando_replica

    print("=" * 55)
    print("  SERVICIO DE MONITOREO - PC3")
    print("=" * 55)
    print(f"  Analítica:    tcp://{ANALITICA_IP}:5560")
    print(f"  BD Principal: tcp://{BD_PRINCIPAL_IP}:5571")
    print(f"  BD Réplica:   tcp://{BD_REPLICA_IP}:5564 (failover)")
    print("=" * 55)

    contexto = zmq.Context()

    # Socket REQ para hablar con la analítica (comandos directos)
    socket_analitica = crear_socket(contexto, zmq.REQ, f"tcp://{ANALITICA_IP}:5560")
    print(f"[MONITOREO] Conectado a analítica")

    # Socket REQ para consultas a la BD principal
    endpoint_bd = f"tcp://{BD_PRINCIPAL_IP}:5571"
    socket_bd = crear_socket(contexto, zmq.REQ, endpoint_bd)
    print(f"[MONITOREO] Conectado a BD principal")

    print("[MONITOREO] Servicio listo.\n")

    while True:
        try:
            mostrar_menu()
            opcion = input("   Opción: ").strip()

            # ============================================
            # OPCIÓN 1: Estado actual (pregunta a analítica)
            # ============================================
            if opcion == "1":
                inter = input("   Intersección (ej. INT-C5): ").strip().upper()
                if not inter:
                    print("   Intersección inválida")
                    continue

                print(f"\n[MONITOREO] Consultando estado de {inter}...")

                try:
                    socket_analitica.send_json({
                        "tipo": "ESTADO_ACTUAL",
                        "interseccion": inter
                    })
                    resp = socket_analitica.recv_json()

                    if resp.get("status") == "OK" and "Q" in resp:
                        print(f"\n   Intersección: {resp.get('interseccion')}")
                        print(f"   Q (Cola):       {resp.get('Q')}")
                        print(f"   Vp (Velocidad): {resp.get('Vp')} km/h")
                        print(f"   D (Densidad):   {resp.get('D')} veh/km")
                        print(f"   Estado:         {resp.get('estado_trafico')}")
                    else:
                        print(f"   {resp.get('mensaje', 'Sin datos')}")
                except (zmq.Again, zmq.ZMQError) as e:
                    print(f"   Error: analítica no responde ({e})")
                    # Recreo el socket para que no quede en estado raro
                    try:
                        socket_analitica.close()
                    except:
                        pass
                    socket_analitica = crear_socket(contexto, zmq.REQ,
                                                     f"tcp://{ANALITICA_IP}:5560")

            # ============================================
            # OPCIÓN 2: Consulta histórica (pregunta a BD)
            # ============================================
            elif opcion == "2":
                print("   Formato: YYYY-MM-DDTHH:MM:SSZ")
                inicio = input("   Fecha inicio: ").strip()
                fin = input("   Fecha fin:    ").strip()
                if not inicio or not fin:
                    print("   Fechas inválidas")
                    continue

                consulta = {
                    "tipo": "CONSULTA_HISTORICA",
                    "fecha_inicio": inicio,
                    "fecha_fin": fin
                }

                resp, socket_bd, endpoint_bd = consultar_bd(
                    contexto, socket_bd, consulta, endpoint_bd
                )

                print(f"\n   Fuente: {resp.get('fuente', '?')}")
                if resp.get("status") == "OK":
                    total = resp.get("total", 0)
                    print(f"   Registros: {total}")
                    for r in resp.get("resultados", [])[:10]:
                        print(f"   | {r['timestamp']} | {r['interseccion']} | "
                              f"{r['estado_trafico']} | Q={r['Q']} Vp={r['Vp']} D={r['D']}")
                    if total > 10:
                        print(f"   ... ({total} total, mostrando 10)")
                else:
                    print(f"   Error: {resp.get('mensaje')}")

            # ============================================
            # OPCIÓN 3: Resumen de estados (pregunta a BD)
            # ============================================
            elif opcion == "3":
                consulta = {"tipo": "CONSULTA_ESTADOS"}
                resp, socket_bd, endpoint_bd = consultar_bd(
                    contexto, socket_bd, consulta, endpoint_bd
                )

                print(f"\n   Fuente: {resp.get('fuente', '?')}")
                if resp.get("status") == "OK":
                    estados = resp.get("resumen_estados", {})
                    for estado, cantidad in estados.items():
                        barra = "#" * min(cantidad, 50)
                        print(f"   {estado:15s} | {cantidad:5d} | {barra}")
                else:
                    print(f"   Error: {resp.get('mensaje')}")

            # ============================================
            # OPCIÓN 4: Priorización / ambulancia
            # ============================================
            elif opcion == "4":
                inter = input("   Intersección (ej. INT-C5): ").strip().upper()
                motivo = input("   Motivo (ej. Ambulancia): ").strip()
                if not inter:
                    print("   Intersección inválida")
                    continue
                if not motivo:
                    motivo = "Priorización por operador"

                # Mido el tiempo para calcular la latencia
                tiempo_inicio = time.time()

                print(f"\n[MONITOREO] Enviando priorización para {inter}...")

                try:
                    socket_analitica.send_json({
                        "tipo": "PRIORIZACION",
                        "interseccion": inter,
                        "motivo": motivo,
                        "timestamp_solicitud": datetime.now(timezone.utc).strftime(
                            "%Y-%m-%dT%H:%M:%SZ")
                    })
                    resp = socket_analitica.recv_json()

                    # Calculo la latencia (tiempo de respuesta del semáforo)
                    latencia_ms = (time.time() - tiempo_inicio) * 1000

                    if resp.get("status") == "OK":
                        print(f"   {resp.get('mensaje')}")
                        cmd = resp.get("comando", {})
                        print(f"   Acción: {cmd.get('accion')}")
                        print(f"   Verde: {cmd.get('duracion_verde')}s")
                        print(f"   Latencia: {latencia_ms:.1f} ms")
                    else:
                        print(f"   Error: {resp.get('mensaje')}")
                except (zmq.Again, zmq.ZMQError) as e:
                    print(f"   Error: analítica no responde ({e})")
                    try:
                        socket_analitica.close()
                    except:
                        pass
                    socket_analitica = crear_socket(contexto, zmq.REQ,
                                                     f"tcp://{ANALITICA_IP}:5560")

            # ============================================
            # OPCIÓN 5: Throughput (pregunta a BD)
            # ============================================
            elif opcion == "5":
                consulta = {"tipo": "CONSULTA_THROUGHPUT"}
                resp, socket_bd, endpoint_bd = consultar_bd(
                    contexto, socket_bd, consulta, endpoint_bd
                )

                print(f"\n   Fuente: {resp.get('fuente', '?')}")
                if resp.get("status") == "OK":
                    print(f"   Total registros en BD: {resp.get('total_registros')}")
                else:
                    print(f"   Error: {resp.get('mensaje')}")

            # ============================================
            # OPCIÓN 6: Consultar intersección en BD
            # ============================================
            elif opcion == "6":
                inter = input("   Intersección (ej. INT-C5): ").strip().upper()
                if not inter:
                    print("   Intersección inválida")
                    continue

                consulta = {
                    "tipo": "CONSULTA_INTERSECCION",
                    "interseccion": inter
                }
                resp, socket_bd, endpoint_bd = consultar_bd(
                    contexto, socket_bd, consulta, endpoint_bd
                )

                print(f"\n   Fuente: {resp.get('fuente', '?')}")
                if resp.get("status") == "OK":
                    total = resp.get("total", 0)
                    print(f"   Registros: {total}")
                    for r in resp.get("resultados", []):
                        print(f"   | {r['timestamp']} | {r['tipo_sensor']} | "
                              f"{r['estado_trafico']} | Q={r['Q']} Vp={r['Vp']} D={r['D']}")
                else:
                    print(f"   Error: {resp.get('mensaje')}")

            # ============================================
            # OPCIÓN 0: Salir
            # ============================================
            elif opcion == "0":
                print("\n[MONITOREO] Cerrando...")
                break
            else:
                print("   Opción inválida")

        except KeyboardInterrupt:
            print("\n\n[MONITOREO] Cerrando...")
            break
        except EOFError:
            break

    # Cierro todo
    socket_analitica.close()
    socket_bd.close()
    contexto.term()
    print("[MONITOREO] Listo.")


if __name__ == "__main__":
    main()
