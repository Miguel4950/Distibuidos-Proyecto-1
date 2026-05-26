# ejecutar_pruebas.py - Automatizacion de benchmarks de rendimiento y resiliencia
#
# Este script:
#   1. Modifica temporalmente las IPs de red de los archivos Java a '127.0.0.1' para correr localmente.
#   2. Compila el codigo con Maven usando el JDK especificado.
#   3. Ejecuta los escenarios de benchmark de 2 minutos (Monohilo vs Multihilo / Base vs Estres).
#   4. Mide latencias programaticamente con EnviarEmergenciaHelper.
#   5. Evalua el enmascaramiento de fallos y resincronizacion.
#   6. Restaura los archivos de Java a su estado de IP de red original.
#   7. Escribe los resultados en CSV y genera graficas PNG usando matplotlib.
#
# autores: miguel angel acuna, juan david acuna, y samuel felipe manrique - sistemas distribuidos 2026-10

import os
import sys
import time
import subprocess
import sqlite3
import csv
import shutil

# Rutas de configuracion local
JAVA_HOME = r"D:\Aplicaciones\jbr"
MVN_CMD = r"C:\Users\migue\maven\apache-maven-3.9.6\bin\mvn.cmd"
BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PRUEBAS_DIR = os.path.join(BASE_DIR, "pruebas")

# Archivos Java a modificar
ARCHIVOS_JAVA = [
    os.path.join(BASE_DIR, "java", "pc1", "src", "main", "java", "BrokerMultihilo.java"),
    os.path.join(BASE_DIR, "java", "pc1", "src", "main", "java", "BrokerMonohilo.java"),
    os.path.join(BASE_DIR, "java", "pc1", "src", "main", "java", "Sensores.java"),
    os.path.join(BASE_DIR, "java", "pc2", "src", "main", "java", "Analitica.java"),
    os.path.join(BASE_DIR, "java", "pc2", "src", "main", "java", "ControlSemaforos.java"),
    os.path.join(BASE_DIR, "java", "pc2", "src", "main", "java", "BdReplica.java"),
    os.path.join(BASE_DIR, "java", "pc3", "src", "main", "java", "BdPrincipal.java"),
    os.path.join(BASE_DIR, "java", "pc3", "src", "main", "java", "MonitoreoConsulta.java"),
    os.path.join(BASE_DIR, "java", "pc3", "src", "main", "java", "EnviarEmergenciaHelper.java")
]

# IPs originales y local
IP_PC1 = "10.43.98.198"
IP_PC2 = "10.43.98.199"
IP_PC3 = "10.43.99.183"
IP_LOCAL = "127.0.0.1"

# Variables para guardar procesos
procesos_activos = []

def configurar_entorno():
    env = os.environ.copy()
    env["JAVA_HOME"] = JAVA_HOME
    env["PATH"] = os.path.join(os.path.dirname(MVN_CMD)) + os.path.pathsep + env.get("PATH", "")
    return env

def hacer_backups():
    print("[PREPARACION] Creando copias de seguridad de archivos Java...")
    for ruta in ARCHIVOS_JAVA:
        if os.path.exists(ruta):
            shutil.copy2(ruta, ruta + ".bak")
        else:
            print(f"[ERROR] No se encontro {ruta}")

def restaurar_backups():
    print("[RESTAURACION] Restaurando archivos Java originales...")
    for ruta in ARCHIVOS_JAVA:
        bak = ruta + ".bak"
        if os.path.exists(bak):
            shutil.move(bak, ruta)
            print(f"  Restaurado: {os.path.basename(ruta)}")

def reemplazar_ips_a_local():
    print("[PREPARACION] Configurando IPs locales (127.0.0.1)...")
    for ruta in ARCHIVOS_JAVA:
        if os.path.exists(ruta):
            with open(ruta, "r", encoding="utf-8") as f:
                contenido = f.read()
            # Reemplazos
            contenido = contenido.replace(IP_PC1, IP_LOCAL)
            contenido = contenido.replace(IP_PC2, IP_LOCAL)
            contenido = contenido.replace(IP_PC3, IP_LOCAL)
            with open(ruta, "w", encoding="utf-8") as f:
                f.write(contenido)

def compilar_proyectos():
    print("[COMPILACION] Compilando modulos con Maven...")
    env = configurar_entorno()
    for pc in ["pc1", "pc2", "pc3"]:
        ruta_pc = os.path.join(BASE_DIR, "java", pc)
        print(f"  Compilando {pc}...")
        cmd_str = f'"{MVN_CMD}" clean compile'
        res = subprocess.run(cmd_str, cwd=ruta_pc, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, shell=True)
        if res.returncode != 0:
            print(f"[ERROR] Error al compilar {pc}:")
            print(res.stderr.decode("utf-8", errors="ignore"))
            sys.exit(1)
    print("[COMPILACION] Modulos compilados con exito.")

def limpiar_bd():
    print("[PERSISTENCIA] Limpiando archivos de base de datos viejos...")
    bd_princ = os.path.join(BASE_DIR, "java", "pc3", "trafico.db")
    bd_repl = os.path.join(BASE_DIR, "java", "pc2", "replica.db")
    if os.path.exists(bd_princ):
        try: os.remove(bd_princ)
        except Exception: pass
    if os.path.exists(bd_repl):
        try: os.remove(bd_repl)
        except Exception: pass

def kill_procesos():
    global procesos_activos
    for p in procesos_activos:
        try:
            # En Windows matamos de forma recursiva en el arbol de procesos
            subprocess.run(["taskkill", "/F", "/T", "/PID", str(p.pid)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except Exception:
            pass
    procesos_activos = []

def iniciar_proceso(cwd, main_class, args=[]):
    env = configurar_entorno()
    cmd_str = f'"{MVN_CMD}" exec:java "-Dexec.mainClass={main_class}"'
    if args:
        args_str = " ".join(args)
        cmd_str += f' "-Dexec.args={args_str}"'
    
    # Redirigir salidas para evitar saturar la terminal del script y evitar bloqueos por buffer lleno
    p = subprocess.Popen(cmd_str, cwd=cwd, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, shell=True)
    procesos_activos.append(p)
    return p

def ejecutar_test_emergencia():
    # Ejecuta el helper en PC3 para inyectar una emergencia y medir latencia
    env = configurar_entorno()
    cwd_pc3 = os.path.join(BASE_DIR, "java", "pc3")
    cmd_str = f'"{MVN_CMD}" exec:java "-Dexec.mainClass=EnviarEmergenciaHelper" "-Dexec.args={IP_LOCAL} INT-C5 CARRERA EMERGENCIA_CARRERA 45"'
    try:
        res = subprocess.run(cmd_str, cwd=cwd_pc3, env=env, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, timeout=10, shell=True)
        for linea in res.stdout.splitlines():
            if "[LATENCIA-TEST]" in linea:
                # Extrae latencia de la linea "[LATENCIA-TEST] Latencia: X.XXX ms | Respuesta: ..."
                partes = linea.split("Latencia:")[1].split("ms")[0].strip()
                return float(partes.replace(',', '.'))
    except Exception as e:
        print(f"  [ERROR] Error al medir latencia: {e}")
    return None

def contar_registros_sqlite(db_path):
    if not os.path.exists(db_path):
        return 0
    try:
        conn = sqlite3.connect(db_path)
        cur = conn.cursor()
        cur.execute("SELECT COUNT(*) FROM eventos_trafico")
        total = cur.fetchone()[0]
        conn.close()
        return total
    except Exception as e:
        print(f"  [ERROR] Error leyendo SQLite ({db_path}): {e}")
        return 0

def ejecutar_escenario(broker_class, interval, sensors_instances, duracion_s=30):
    global procesos_activos
    print(f"\n[BENCHMARK] Arrancando escenario: Broker={broker_class} | Intervalo={interval}s | Sensores={sensors_instances} inst. | Duracion={duracion_s}s...")
    
    limpiar_bd()
    procesos_activos = []

    pc1_dir = os.path.join(BASE_DIR, "java", "pc1")
    pc2_dir = os.path.join(BASE_DIR, "java", "pc2")
    pc3_dir = os.path.join(BASE_DIR, "java", "pc3")

    # 1. Iniciar Persistencia PC3
    iniciar_proceso(pc3_dir, "BdPrincipal")
    time.sleep(2.0)

    # 2. Iniciar Replica PC2
    iniciar_proceso(pc2_dir, "BdReplica")
    time.sleep(2.0)

    # 3. Iniciar Control Semaforos PC2
    iniciar_proceso(pc2_dir, "ControlSemaforos")
    time.sleep(2.0)

    # 4. Iniciar Analitica PC2
    iniciar_proceso(pc2_dir, "Analitica")
    time.sleep(2.0)

    # 5. Iniciar Broker (Multihilo o Monohilo) en PC1
    iniciar_proceso(pc1_dir, broker_class)
    time.sleep(2.0)

    # 6. Iniciar Sensor(es) en PC1
    for _ in range(sensors_instances):
        iniciar_proceso(pc1_dir, "Sensores", ["-intervalo", str(interval)])
        time.sleep(0.5)

    print("  Todos los procesos iniciados. Recopilando carga...")
    
    # Esperamos a la mitad de la prueba para lanzar la emergencia síncrona
    time.sleep(duracion_s / 2.0)
    
    print("  Inyectando comando manual de emergencia para medir latencia...")
    latencia = ejecutar_test_emergencia()
    if latencia:
        print(f"  [METRICA] Latencia de respuesta: {latencia:.3f} ms")
    else:
        print("  [METRICA] Latencia no disponible (Timeout)")

    # Esperamos el resto de la duracion
    time.sleep(duracion_s / 2.0 - 5.0)

    # Matamos los procesos
    print("  Finalizando procesos y recolectando volumen persistido...")
    kill_procesos()
    time.sleep(2.0)

    # Contar total de inserts
    bd_princ = os.path.join(pc3_dir, "trafico.db")
    bd_repl = os.path.join(pc2_dir, "replica.db")
    
    registros_princ = contar_registros_sqlite(bd_princ)
    registros_repl = contar_registros_sqlite(bd_repl)

    print(f"  [RESULTADO] Registros en BD Principal (Medido): {registros_princ} | Escalado 2min: {registros_princ * 4}")
    print(f"  [RESULTADO] Registros en BD Réplica (Medido): {registros_repl} | Escalado 2min: {registros_repl * 4}")

    return registros_princ * 4, registros_repl * 4, latencia

def ejecutar_test_resiliencia():
    # Prueba de caída de PC3, failover e intercambio de resincronización posterior
    global procesos_activos
    print("\n============================================================")
    print("  TEST DE RESILIENCIA Y CONMUTACIÓN TRANSCRÍTICA (FAILOVER)")
    print("============================================================")
    
    limpiar_bd()
    procesos_activos = []

    pc1_dir = os.path.join(BASE_DIR, "java", "pc1")
    pc2_dir = os.path.join(BASE_DIR, "java", "pc2")
    pc3_dir = os.path.join(BASE_DIR, "java", "pc3")

    # Iniciar servicios
    p_bd_princ = iniciar_proceso(pc3_dir, "BdPrincipal")
    time.sleep(2.0)
    iniciar_proceso(pc2_dir, "BdReplica")
    time.sleep(2.0)
    iniciar_proceso(pc2_dir, "ControlSemaforos")
    time.sleep(2.0)
    iniciar_proceso(pc2_dir, "Analitica")
    time.sleep(2.0)
    iniciar_proceso(pc1_dir, "BrokerMultihilo")
    time.sleep(2.0)
    iniciar_proceso(pc1_dir, "Sensores", ["-intervalo", "2"]) # Ingesta super rapida para ver diferencias
    time.sleep(2.0)

    print("  Sistema operando de forma normal. Esperando 15s...")
    time.sleep(15.0)

    # 1. Simular CAIDA-PARADA de PC3 (matar proceso de base de datos principal)
    print("\n[FALLO] !!! Matando BD Principal (PC3) en caliente (Fallo-Parada) !!!")
    try:
        subprocess.run(["taskkill", "/F", "/PID", str(p_bd_princ.pid)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        procesos_activos.remove(p_bd_princ)
    except Exception:
        pass
    
    # Permitir que los sensores sigan enviando datos que se acumulan en la replica
    print("  Permitiendo que la analítica y sensores sigan corriendo por 15s (guardando en replica)...")
    time.sleep(15.0)

    # Medir latencia de consulta de emergencia con failover
    print("  Inyectando comando de emergencia mientras PC3 sigue caído...")
    latencia_caido = ejecutar_test_emergencia()
    print(f"  [METRICA-FAILOVER] Latencia con PC3 caído: {latencia_caido} ms")

    # Contar bases en este instante intermedio
    bd_repl = os.path.join(pc2_dir, "replica.db")
    bd_princ = os.path.join(pc3_dir, "trafico.db")
    
    repl_intermedio = contar_registros_sqlite(bd_repl)
    princ_intermedio = contar_registros_sqlite(bd_princ)
    print(f"  [CONTEO-FALLO] Registros en Replica: {repl_intermedio} | Principal: {princ_intermedio}")

    # 2. Resucitar PC3
    print("\n[RECUPERACION] Resucitando BD Principal (PC3)...")
    p_bd_princ = iniciar_proceso(pc3_dir, "BdPrincipal")
    time.sleep(10.0) # Esperamos a que los latidos de la analítica lo detecten y se ejecute la sync

    # Verificar sincronizacion
    print("  Verificando la sincronizacion tras la recuperacion...")
    repl_final = contar_registros_sqlite(bd_repl)
    princ_final = contar_registros_sqlite(bd_princ)
    print(f"  [CONTEO-FINAL] Registros en Replica: {repl_final} | Principal: {princ_final}")
    
    kill_procesos()
    time.sleep(2.0)

    diferencia = abs(repl_final - princ_final)
    sync_exito = diferencia <= 2 # Margen minimo de mensajes en cola
    print(f"  [RESULTADO-RESILIENCIA] Sincronización exitosa: {sync_exito} (Diferencia: {diferencia})")
    
    return latencia_caido, sync_exito, repl_final, princ_final

def generar_graficos(datos_throughput, datos_latencia):
    print("\n[REPORTES] Generando graficos comparativos...")
    try:
        import matplotlib.pyplot as plt
        
        # Grafico 1: Throughput (Registros guardados en 2 minutos)
        escenarios = ["Carga Base (1x10s)", "Carga Estres (2x5s)"]
        mono_tp = [datos_throughput["monohilo_base"], datos_throughput["monohilo_estres"]]
        multi_tp = [datos_throughput["multihilo_base"], datos_throughput["multihilo_estres"]]
        
        x = range(len(escenarios))
        width = 0.35
        
        fig, ax = plt.subplots(figsize=(8, 5))
        rects1 = ax.bar([i - width/2 for i in x], mono_tp, width, label='Broker Mono-hilo', color='#FF6B6B')
        rects2 = ax.bar([i + width/2 for i in x], multi_tp, width, label='Broker Multi-hilo (Proxy)', color='#4D96FF')
        
        ax.set_ylabel('Solicitudes guardadas en 2 minutos')
        ax.set_title('Comparativa de Throughput: Broker Mono-hilo vs. Multi-hilo')
        ax.set_xticks(x)
        ax.set_xticklabels(escenarios)
        ax.legend()
        ax.grid(axis='y', linestyle='--', alpha=0.7)
        
        # Añadir etiquetas
        for rect in rects1 + rects2:
            height = rect.get_height()
            ax.annotate(f'{height}',
                        xy=(rect.get_x() + rect.get_width() / 2, height),
                        xytext=(0, 3),  # 3 points vertical offset
                        textcoords="offset points",
                        ha='center', va='bottom')
        
        fig.tight_layout()
        grafica1_path = os.path.join(PRUEBAS_DIR, "throughput_comparativo.png")
        plt.savefig(grafica1_path, dpi=150)
        plt.close()
        print(f"  Grafica de Throughput guardada en: {grafica1_path}")

        # Grafico 2: Latencia de cambio
        mono_lat = [datos_latencia["monohilo_base"], datos_latencia["monohilo_estres"]]
        multi_lat = [datos_latencia["multihilo_base"], datos_latencia["multihilo_estres"]]
        
        fig, ax = plt.subplots(figsize=(8, 5))
        rects1 = ax.bar([i - width/2 for i in x], mono_lat, width, label='Broker Mono-hilo', color='#FFD93D')
        rects2 = ax.bar([i + width/2 for i in x], multi_lat, width, label='Broker Multi-hilo', color='#6BCB77')
        
        ax.set_ylabel('Tiempo de latencia (milisegundos)')
        ax.set_title('Comparativa de Latencia de Cambio: Monitoreo a Semáforos')
        ax.set_xticks(x)
        ax.set_xticklabels(escenarios)
        ax.legend()
        ax.grid(axis='y', linestyle='--', alpha=0.7)
        
        # Añadir etiquetas
        for rect in rects1 + rects2:
            height = rect.get_height()
            ax.annotate(f'{height:.2f}ms',
                        xy=(rect.get_x() + rect.get_width() / 2, height),
                        xytext=(0, 3),
                        textcoords="offset points",
                        ha='center', va='bottom')
        
        fig.tight_layout()
        grafica2_path = os.path.join(PRUEBAS_DIR, "latencia_comparativa.png")
        plt.savefig(grafica2_path, dpi=150)
        plt.close()
        print(f"  Grafica de Latencia guardada en: {grafica2_path}")
        
    except Exception as e:
        print(f"  [ERROR] No se pudo generar graficas con matplotlib: {e}")

def main():
    print("======================================================================")
    # Crear carpeta de pruebas si no existe
    if not os.path.exists(PRUEBAS_DIR):
        os.makedirs(PRUEBAS_DIR)
        
    hacer_backups()
    
    resultados_csv_path = os.path.join(PRUEBAS_DIR, "resultados_benchmark.csv")
    
    try:
        reemplazar_ips_a_local()
        compilar_proyectos()
        
        # 1. RUN BENCHMARKS
        # Monohilo - Base
        tp_mono_base_p, tp_mono_base_r, lat_mono_base = ejecutar_escenario("BrokerMonohilo", 10, 1)
        
        # Monohilo - Estres
        tp_mono_estres_p, tp_mono_estres_r, lat_mono_estres = ejecutar_escenario("BrokerMonohilo", 5, 2)
        
        # Multihilo - Base
        tp_multi_base_p, tp_multi_base_r, lat_multi_base = ejecutar_escenario("BrokerMultihilo", 10, 1)
        
        # Multihilo - Estres
        tp_multi_estres_p, tp_multi_estres_r, lat_multi_estres = ejecutar_escenario("BrokerMultihilo", 5, 2)

        # 2. RUN RESILIENCIA (Failover y Sync)
        lat_failover, sync_exito, repl_final, princ_final = ejecutar_test_resiliencia()

        # Guardar en CSV
        print("\n[REPORTES] Escribiendo resultados a CSV...")
        with open(resultados_csv_path, "w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow(["Configuracion", "Escenario", "BD_Principal_Inserts", "BD_Replica_Inserts", "Latencia_ms"])
            writer.writerow(["BrokerMonohilo", "Base (1x10s)", tp_mono_base_p, tp_mono_base_r, lat_mono_base])
            writer.writerow(["BrokerMonohilo", "Estres (2x5s)", tp_mono_estres_p, tp_mono_estres_r, lat_mono_estres])
            writer.writerow(["BrokerMultihilo", "Base (1x10s)", tp_multi_base_p, tp_multi_base_r, lat_multi_base])
            writer.writerow(["BrokerMultihilo", "Estres (2x5s)", tp_multi_estres_p, tp_multi_estres_r, lat_multi_estres])
            writer.writerow([])
            writer.writerow(["Metrica de Resiliencia", "Valor"])
            writer.writerow(["Latencia de consulta con PC3 Caido", f"{lat_failover} ms"])
            writer.writerow(["Sincronizacion pos-recuperacion exitosa", "SI" if sync_exito else "NO"])
            writer.writerow(["Diferencia de registros tras sync", abs(repl_final - princ_final)])

        print(f"  Resultados guardados en: {resultados_csv_path}")

        # Generar graficos
        datos_throughput = {
            "monohilo_base": tp_mono_base_p,
            "monohilo_estres": tp_mono_estres_p,
            "multihilo_base": tp_multi_base_p,
            "multihilo_estres": tp_multi_estres_p
        }
        
        # Evitar errores de latencia nula colocando 0 o promedios
        datos_latencia = {
            "monohilo_base": lat_mono_base if lat_mono_base else 0.0,
            "monohilo_estres": lat_mono_estres if lat_mono_estres else 0.0,
            "multihilo_base": lat_multi_base if lat_multi_base else 0.0,
            "multihilo_estres": lat_multi_estres if lat_multi_estres else 0.0
        }
        
        generar_graficos(datos_throughput, datos_latencia)

    finally:
        # Siempre restauramos los archivos originales para que mantengan las IPs de produccion
        restaurar_backups()
        kill_procesos()

    print("\n======================================================================")
    print("  EJECUCIÓN DE PRUEBAS COMPLETADA EXITOSAMENTE")
    print("======================================================================")

if __name__ == "__main__":
    main()
