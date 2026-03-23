# broker_multihilo.py - broker zeromq para el pc1 (ingesta de datos)
# 
# este script actúa como intermediario entre los sensores y el 
# servicio de analítica. 
# usa xsub/xpub para reenviar los mensajes sin procesarlos.
# 
# tiene un diseño multihilo:
#   - un hilo para el proxy (reenviar mensajes)
#   - un hilo para contar mensajes (métricas de rendimiento)
# 
# autores: miguel angel acuña, juan david acuña, y samuel felipe manrique - sistemas distribuidos 2026-10

import zmq
import threading
import time
import sys

# ============================================================
# CONFIGURACIÓN DE RED - Cambiar según la IP del PC1
# ============================================================
BROKER_IP = "10.43.98.198"
PUERTO_XSUB = 5555   # Aquí se conectan los sensores (PUB)
PUERTO_XPUB = 5556   # Aquí se conecta la analítica (SUB)

# TODO: Implementar CurveZMQ para la entrega final

# Variable global para el contador
contador_mensajes = 0
lock_contador = threading.Lock()

def hilo_metricas():
    # este hilo simplemente imprime el valor del contador 
    # global cada 30 segundos.
    global contador_mensajes
    print("[METRICAS] Hilo de métricas iniciado")

    while True:
        time.sleep(30)
        
        lock_contador.acquire()
        total = contador_mensajes
        contador_mensajes = 0  # Reseteo el contador
        lock_contador.release()
        
        tasa = total / 30.0
        print(f"[METRICAS] Mensajes: {total} | Tasa: {tasa:.2f} msg/s")


def hilo_proxy(contexto):
    # este hilo es el proxy principal del broker.
    # recibe mensajes de los sensores y los reenvía manualmente a 
    # la analítica.
    # no usamos zmq.proxy() para poder contar los mensajes explícitamente.
    global contador_mensajes

    # Socket XSUB - aquí llegan los mensajes de los sensores
    frontend = contexto.socket(zmq.XSUB)
    frontend.bind(f"tcp://{BROKER_IP}:{PUERTO_XSUB}")
    print(f"[BROKER] XSUB esperando sensores en tcp://{BROKER_IP}:{PUERTO_XSUB}")

    # Socket XPUB - desde aquí salen los mensajes hacia la analítica
    backend = contexto.socket(zmq.XPUB)
    backend.bind(f"tcp://{BROKER_IP}:{PUERTO_XPUB}")
    print(f"[BROKER] XPUB esperando suscriptores en tcp://{BROKER_IP}:{PUERTO_XPUB}")

    print("[BROKER] Proxy iniciado manual, reenviando mensajes...")

    while True:
        try:
            # Recibimos el mensaje crudo (string con tópico y JSON)
            mensaje = frontend.recv_string()
            
            # Sumamos 1 a la variable global del contador
            lock_contador.acquire()
            contador_mensajes += 1
            lock_contador.release()

            # Reenviamos el mensaje
            backend.send_string(mensaje)
            
        except zmq.ContextTerminated:
            print("[BROKER] Proxy detenido")
            break
        except Exception as e:
            print(f"[BROKER] Error reenviando: {e}")

    frontend.close()
    backend.close()


def main():
    print("=" * 60)
    print("  BROKER MULTIHILO - PC1 (Ingesta de datos)")
    print("=" * 60)
    print(f"  Sensores (XSUB) -> tcp://{BROKER_IP}:{PUERTO_XSUB}")
    print(f"  Analítica (XPUB) -> tcp://{BROKER_IP}:{PUERTO_XPUB}")
    print("=" * 60)

    # Creo el contexto de ZeroMQ (compartido entre hilos)
    contexto = zmq.Context()

    # Inicio el hilo de métricas (daemon para que muera con el programa)
    t1 = threading.Thread(target=hilo_metricas, daemon=True)
    t1.start()

    # Inicio el hilo del proxy
    t2 = threading.Thread(target=hilo_proxy, args=(contexto,), daemon=True)
    t2.start()

    print("[BROKER] Broker corriendo. Presione Ctrl+C para detener.\n")

    # Mantengo el programa vivo hasta que el usuario lo detenga
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\n[BROKER] Cerrando broker...")
        contexto.term()
        print("[BROKER] Listo, broker cerrado.")


if __name__ == "__main__":
    main()
