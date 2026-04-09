# Manual de Despliegue — Gestión Inteligente de Tráfico Urbano (Versión Java)

## Descripción General

Sistema distribuido en 3 máquinas virtuales (PC1, PC2, PC3) que monitorea, analiza y controla el tráfico urbano usando ZeroMQ (JeroMQ) como middleware de comunicación.

### Arquitectura

```
PC1 (Ingesta)           PC2 (Cerebro)              PC3 (Persistencia)
┌─────────────┐    PUB/SUB    ┌─────────────────┐   PUSH/PULL   ┌──────────────┐
│  Sensores   │──────────────→│   Analítica     │──────────────→│ BD Principal │
│  (PUB)      │               │   (SUB/PUSH)    │               │ (PULL)       │
├─────────────┤               ├─────────────────┤               └──────────────┘
│   Broker    │               │ Control Semáf.  │               
│ (XSUB/XPUB)│               │   (PULL)        │               
└─────────────┘               ├─────────────────┤               
                              │  BD Réplica     │
                              │ (PULL)          │
                              └─────────────────┘
```

---

## 1. Requisitos del Sistema

### Software
- Java 11 o superior (JDK)
- Maven 3.6 o superior
- 3 Máquinas Virtuales con Ubuntu/Linux

### Instalación de dependencias

Ejecutar en **CADA una de las 3 máquinas virtuales**:

```bash
# actualizar repositorios
sudo apt update && sudo apt upgrade -y

# instalar java jdk y maven
sudo apt install default-jdk maven -y

# verificar instalacion
java -version
mvn -version
```

---

## 2. Configuración de IPs

Antes de ejecutar el sistema, **debes cambiar las IPs** en los archivos `.java` para que coincidan con las direcciones de tus máquinas virtuales.

### Asignación de IPs por defecto

| Máquina | IP por defecto    | Rol                  |
|---------|-------------------|----------------------|
| PC1     | `10.43.98.198`    | Ingesta (sensores + broker) |
| PC2     | `10.43.98.199`    | Cerebro (analítica + semáforos + réplica) |
| PC3     | `10.43.99.183`    | Persistencia (BD principal) |

### Archivos a modificar por PC

**PC1** — Editar las variables `static` al inicio de cada archivo:

```java
// En BrokerMultihilo.java
static String BROKER_IP = "TU_IP_PC1";

// En Sensores.java
static String BROKER_IP = "TU_IP_PC1";
```

**PC2** — Editar:

```java
// En Analitica.java
static String BROKER_IP = "IP_DEL_PC1";
static String ANALITICA_IP = "TU_IP_PC2";
static String BD_PRINCIPAL_IP = "IP_DEL_PC3";

// En ControlSemaforos.java
static String ANALITICA_IP = "TU_IP_PC2";

// En BdReplica.java
static String REPLICA_IP = "TU_IP_PC2";
```

**PC3** — Editar:

```java
// En BdPrincipal.java
static String BD_IP = "TU_IP_PC3";
```

---

## 3. Transferencia de Archivos

Copiar las carpetas a cada máquina virtual:

```bash
# PC1:
scp -r pc1/ usuario@IP_PC1:~/trafico/

# PC2:
scp -r pc2/ usuario@IP_PC2:~/trafico/

# PC3:
scp -r pc3/ usuario@IP_PC3:~/trafico/
```

---

## 4. Compilación

En **cada máquina virtual**, abre una terminal para compilar los módulos de Java con Maven:

```bash
# Ejemplo si estás en PC1:
cd ~/Desktop/Distibuidos-Proyecto-1-main/java/pc1
mvn compile
```

Maven descargará automáticamente las dependencias (JeroMQ, org.json, sqlite-jdbc). Haz esto en la carpeta `pc2` y `pc3` en sus respectivas máquinas.

---

## 5. Orden Exacto de Ejecución

> **IMPORTANTE**: Respetar el orden exacto. Los servicios que reciben datos (PULL, SUB) deben iniciarse ANTES que los que envían datos (PUB, PUSH).

### Paso 1: Iniciar PC3 (Persistencia)

```bash
# Terminal de PC3:
cd ~/Desktop/Distibuidos-Proyecto-1-main/java/pc3
mvn exec:java -Dexec.mainClass="BdPrincipal"
```

### Paso 2: Iniciar PC2 (Cerebro)

```bash
# Terminal 1 de PC2:
cd ~/Desktop/Distibuidos-Proyecto-1-main/java/pc2
mvn exec:java -Dexec.mainClass="BdReplica"

# Terminal 2 de PC2 (abrir nueva terminal):
cd ~/Desktop/Distibuidos-Proyecto-1-main/java/pc2
mvn exec:java -Dexec.mainClass="ControlSemaforos"

# Terminal 3 de PC2 (abrir nueva terminal):
cd ~/Desktop/Distibuidos-Proyecto-1-main/java/pc2
mvn exec:java -Dexec.mainClass="Analitica"
```

### Paso 3: Iniciar PC1 (Ingesta)

```bash
# Terminal 1 de PC1:
cd ~/Desktop/Distibuidos-Proyecto-1-main/java/pc1
mvn exec:java -Dexec.mainClass="BrokerMultihilo"

# Terminal 2 de PC1 (abrir nueva terminal):
cd ~/Desktop/Distibuidos-Proyecto-1-main/java/pc1
mvn exec:java -Dexec.mainClass="Sensores"
```

---

## 6. Verificación de Funcionamiento

### 6.1 Flujo Operativo y Detección
1. Los sensores (PC1) publican eventos generados cada 10 segundos con valores aleatorios ajustados orgánicamente para simular una red urbana (volumen vehícular 0-30, velocidad 0-60km/h).
2. La analítica (PC2) enruta y evalúa estas métricas en tiempo real e imprime transiciones dinámicas entre los estados: NORMAL, INTERMEDIO o CONGESTION.
3. El control de semáforos (PC2) ejecuta el ciclo automático base (15s); si ocurre una emergencia o alto volumen vehicular, obedece comandos de la analítica como extender el verde a 30s.
4. Las BDs Principal y Réplica (PC2 y PC3) persisten ininterrumpidamente cada evento procesado para fines de monitoreo histórico.

### 6.2 Prueba de enmascaramiento de fallos
1. Con el sistema funcionando, **detener** `BdPrincipal` en PC3 (Ctrl+C).
2. Verificar que la analítica (PC2) imprime el mensaje de reconexión manual / error.
3. Los datos deben seguir guardándose ininterrumpidamente en la BD réplica (PC2).

---

## 7. Detener el Sistema

Para detener cada servicio, presionar **Ctrl+C** en la terminal correspondiente.

Orden de detención recomendado (inverso al de inicio):
1. Detener sensores y broker (PC1)
2. Detener analítica, semáforos y réplica (PC2)
3. Detener BD principal (PC3)

---

## 8. Dependencias (Maven)

| Librería | Versión | Uso |
|----------|---------|-----|
| JeroMQ | 0.6.0 | Implementación pura Java de ZeroMQ |
| org.json | 20231013 | Serialización/deserialización JSON |
| sqlite-jdbc | 3.44.1.0 | Driver JDBC para SQLite (solo PC2 y PC3) |

---

## 9. Estructura de Archivos

```
java/
├── README.md
├── diagramas/
│   ├── diagrama_despliegue.puml
│   ├── diagrama_componentes.puml
│   ├── diagrama_clases.puml
│   └── diagrama_secuencia.puml
├── pc1/
│   ├── pom.xml
│   └── src/main/java/
│       ├── BrokerMultihilo.java
│       └── Sensores.java
├── pc2/
│   ├── pom.xml
│   └── src/main/java/
│       ├── Analitica.java
│       ├── ControlSemaforos.java
│       └── BdReplica.java
└── pc3/
    ├── pom.xml
    └── src/main/java/
        └── BdPrincipal.java
```

## Autores

Miguel Angel Acuña, Juan David Acuña, y Samuel Felipe Manrique — Sistemas Distribuidos 2026-10
