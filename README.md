# Manual de Despliegue — Gestión Inteligente de Tráfico Urbano

## Descripción General

Sistema distribuido en 3 máquinas virtuales (PC1, PC2, PC3) que monitorea, analiza y controla el tráfico urbano usando ZeroMQ como middleware de comunicación.

### Arquitectura

```
PC1 (Ingesta)           PC2 (Cerebro)              PC3 (Persistencia)
┌─────────────┐    PUB/SUB    ┌─────────────────┐   PUSH/PULL   ┌──────────────┐
│  Sensores   │──────────────→│   Analítica     │──────────────→│ BD Principal │
│  (PUB)      │               │   (SUB/PUSH/REP)│               │ (PULL/REP)   │
├─────────────┤               ├─────────────────┤               ├──────────────┤
│   Broker    │               │ Control Semáf.  │               │  Monitoreo   │
│ (XSUB/XPUB)│               │   (PULL)        │               │  (REQ)       │
└─────────────┘               ├─────────────────┤               └──────────────┘
                              │  BD Réplica     │
                              │ (PULL/REP)      │
                              └─────────────────┘
```

---

## 1. Requisitos del Sistema

### Software
- Python 3.8 o superior
- pip (gestor de paquetes de Python)
- 3 Máquinas Virtuales con Ubuntu/Linux (o cualquier distribución Linux)

### Instalación de dependencias

Ejecutar en **CADA una de las 3 máquinas virtuales**:

```bash
# Actualizar repositorios
sudo apt update && sudo apt upgrade -y

# Instalar Python y pip (si no están instalados)
sudo apt install python3 python3-pip -y

# Instalar pyzmq (ZeroMQ para Python)
pip3 install pyzmq
```

---

## 2. Configuración de IPs

Antes de ejecutar el sistema, **debes cambiar las IPs** en los scripts para que coincidan con las direcciones de tus máquinas virtuales.

### Asignación de IPs por defecto

| Máquina | IP por defecto    | Rol                  |
|---------|-------------------|----------------------|
| PC1     | `192.168.1.100`   | Ingesta (sensores + broker) |
| PC2     | `192.168.1.101`   | Cerebro (analítica + semáforos + réplica) |
| PC3     | `192.168.1.102`   | Persistencia (BD principal + monitoreo) |

### Verificar IP de cada máquina

```bash
# En cada máquina virtual, ejecutar:
ip addr show | grep "inet "
# o
hostname -I
```

### Archivos a modificar por PC

**PC1** — Editar las variables de configuración al inicio de cada archivo:

```bash
# En pc1/broker_multihilo.py
BROKER_IP = "TU_IP_PC1"

# En pc1/sensores.py
BROKER_IP = "TU_IP_PC1"
```

**PC2** — Editar:

```bash
# En pc2/analitica.py
BROKER_IP = "IP_DEL_PC1"
ANALITICA_IP = "TU_IP_PC2"
BD_PRINCIPAL_IP = "IP_DEL_PC3"

# En pc2/control_semaforos.py
ANALITICA_IP = "TU_IP_PC2"

# En pc2/bd_replica.py
REPLICA_IP = "TU_IP_PC2"
```

**PC3** — Editar:

```bash
# En pc3/bd_principal.py
BD_PRINCIPAL_IP = "TU_IP_PC3"

# En pc3/monitoreo.py
ANALITICA_IP = "IP_DEL_PC2"
BD_PRINCIPAL_IP = "TU_IP_PC3"
BD_REPLICA_IP = "IP_DEL_PC2"
```

---

## 3. Transferencia de Archivos

Copiar los scripts a cada máquina virtual:

```bash
# Desde tu máquina host, enviar archivos a cada VM:

# PC1:
scp -r pc1/ usuario@IP_PC1:~/trafico/

# PC2:
scp -r pc2/ usuario@IP_PC2:~/trafico/

# PC3:
scp -r pc3/ usuario@IP_PC3:~/trafico/
```

---

## 4. Orden Exacto de Ejecución

> **IMPORTANTE**: Respetar el orden exacto. Los servicios que reciben datos (PULL, SUB) deben iniciarse ANTES que los que envían datos (PUB, PUSH).

### Paso 1: Iniciar PC3 (Persistencia)

```bash
# Terminal 1 de PC3:
cd ~/trafico/
python3 bd_principal.py

# Terminal 2 de PC3 (abrir nueva terminal):
cd ~/trafico/
python3 monitoreo.py
```

### Paso 2: Iniciar PC2 (Cerebro)

```bash
# Terminal 1 de PC2:
cd ~/trafico/
python3 bd_replica.py

# Terminal 2 de PC2 (abrir nueva terminal):
cd ~/trafico/
python3 control_semaforos.py

# Terminal 3 de PC2 (abrir nueva terminal):
cd ~/trafico/
python3 analitica.py
```

### Paso 3: Iniciar PC1 (Ingesta)

```bash
# Terminal 1 de PC1:
cd ~/trafico/
python3 broker_multihilo.py

# Terminal 2 de PC1 (abrir nueva terminal):
cd ~/trafico/
python3 sensores.py
```

---

## 5. Verificación de Funcionamiento

### 5.1 Flujo normal
1. Los sensores (PC1) deben imprimir eventos generados cada 10 segundos.
2. La analítica (PC2) debe imprimir los eventos recibidos y el estado de tráfico evaluado.
3. El control de semáforos (PC2) debe imprimir cambios de estado.
4. Las BDs (PC2 y PC3) deben imprimir confirmaciones de inserción.
5. El monitoreo (PC3) permite realizar consultas interactivas.

### 5.2 Prueba de enmascaramiento de fallos
1. Con el sistema funcionando, **detener** `bd_principal.py` en PC3 (Ctrl+C).
2. Verificar que la analítica (PC2) imprime el mensaje de **ENMASCARAMIENTO DE FALLOS**.
3. Los datos deben seguir guardándose en la BD réplica (PC2).
4. En el monitoreo (PC3), intentar una consulta → debe reconectarse automáticamente a la BD réplica.

### 5.3 Prueba de priorización (ambulancia)
1. En el menú del monitoreo, seleccionar opción 4.
2. Ingresar una intersección (ej. `INT-C5`).
3. Verificar que la analítica recibe el comando y envía la orden de OLA VERDE al semáforo.

---

## 6. Detener el Sistema

Para detener cada servicio, presionar **Ctrl+C** en la terminal correspondiente.

El orden de detención recomendado es inverso al de inicio:
1. Detener sensores (PC1)
2. Detener broker (PC1)
3. Detener analítica (PC2)
4. Detener control de semáforos (PC2)
5. Detener BD réplica (PC2)
6. Detener monitoreo (PC3)
7. Detener BD principal (PC3)

---

## 7. Estructura de Archivos

```
distribuidos/
├── README.md                  # Este archivo
├── INFORME.md                 # Informe técnico
├── diagramas/                 # Carpeta con diagramas PlantUML
│   ├── diagrama_despliegue.puml
│   ├── diagrama_componentes.puml
│   ├── diagrama_clases.puml
│   └── diagrama_secuencia.puml
├── pc1/
│   ├── broker_multihilo.py    # Broker ZMQ XSUB/XPUB multihilo
│   └── sensores.py            # Simulación de sensores de tráfico
├── pc2/
│   ├── analitica.py           # Servicio de analítica y reglas
│   ├── control_semaforos.py   # Control de semáforos
│   └── bd_replica.py          # Base de datos réplica
└── pc3/
    ├── bd_principal.py        # Base de datos principal
    └── monitoreo.py           # Servicio de monitoreo y consulta
```
