# Manual de Despliegue — Gestión Inteligente de Tráfico Urbano (Versión Completa)

Sistema distribuido en 3 máquinas virtuales (PC1, PC2, PC3) que monitorea, analiza y controla el tráfico urbano usando ZeroMQ (JeroMQ) como middleware de comunicación. Esta versión incluye el **diseño de doble semáforo (Carrera/Calle)**, el **Servicio de Monitoreo y Consulta con Failover**, el **Protocolo de Sincronización pos-fallo**, y **mecanismos de seguridad HMAC-SHA256**.

### Arquitectura de Red y Flujo de Mensajería

```
   PC1 (Ingesta)                 PC2 (Cerebro)                  PC3 (Persistencia / Operador)
┌──────────────────┐  PUB/SUB   ┌───────────────────┐  PUSH/PULL  ┌────────────────────────┐
│  Sensores (x2)   │───────────→│     Analítica     │────────────→│   BD Principal (PULL)  │
│  (Carrera/Calle) │            │ (Cerebro Central) │             │     (tcp:*:5570)       │
├──────────────────┤            ├───────────────────┤             └───────────▲────────────┘
│  Broker ZMQ      │            │ Control Semáforos │                         │
│  (SUB/PUB)       │            │      (PULL)       │                         │ REQ/REP
└──────────────────┘            ├───────────────────┤                         │ (Failover)
                                │    BD Réplica     │                         │
                                │ (tcp:*:5562/5572) │                         │
                                └─────────▲─────────┘                         │
                                          │                                   │
                                          └───────────────────────────────────┴────────
                                                Consultas REP (PC3 Caído)
                                                
   PC3 (Monitoreo)
┌──────────────────┐  REQ/REP   ┌───────────────────┐
│ MonitoreoCliente │───────────→│ Analítica (REP)   │  (Comandos forzados seguros firmados
│      (REQ)       │  (Firma)   │   (tcp:*:5566)    │   con HMAC-SHA256)
└──────────────────┘            └───────────────────┘
```

---

## 1. Configuración de IPs

Antes de ejecutar el sistema, **debes cambiar las IPs** en los archivos `.java` para que coincidan con las direcciones de tus máquinas virtuales.

### Asignación de IPs por defecto

| Máquina | IP por defecto    | Rol |
| :--- | :--- | :--- |
| **PC1** | `10.43.98.198` | Ingesta (sensores + broker) |
| **PC2** | `10.43.98.199` | Cerebro (analítica + semáforos + réplica) |
| **PC3** | `10.43.99.183` | Persistencia (BD principal) y Monitoreo |

### Archivos a modificar por PC

*   **PC1**: Editar las variables `static String BROKER_IP` en:
    *   `BrokerMultihilo.java`
    *   `Sensores.java`
*   **PC2**: Editar en:
    *   `Analitica.java` (variables `BROKER_IP`, `ANALITICA_IP`, `BD_PRINCIPAL_IP`)
    *   `ControlSemaforos.java` (variable `ANALITICA_IP`)
    *   `BdReplica.java` (variable `REPLICA_IP`)
*   **PC3**: Editar en:
    *   `BdPrincipal.java` (variable `BD_IP`)
    *   `MonitoreoConsulta.java` (variables `ANALITICA_IP`, `BD_PRINCIPAL_IP`)

---

## 2. Compilación

En **cada máquina virtual**, abre una terminal en la carpeta correspondiente para compilar los módulos de Java con Maven:

```bash
# Ejemplo en PC1:
cd ~/trafico/java/pc1
mvn compile

# Ejemplo en PC2:
cd ~/trafico/java/pc2
mvn compile

# Ejemplo en PC3:
cd ~/trafico/java/pc3
mvn compile
```

---

## 3. Orden de Ejecución de los Servicios

> **IMPORTANTE**: Respetar el orden exacto de arranque.

### Paso 1: Iniciar PC3 (Persistencia)
Inicia el motor de persistencia principal:
```bash
# Terminal de PC3:
cd ~/trafico/java/pc3
mvn exec:java -Dexec.mainClass="BdPrincipal"
```

### Paso 2: Iniciar PC2 (Cerebro y Control)
Abre 3 terminales en el PC2 y arranca los servicios en este orden:
```bash
# Terminal 1 (BD Réplica):
mvn exec:java -Dexec.mainClass="BdReplica"

# Terminal 2 (Controlador de Semáforos):
mvn exec:java -Dexec.mainClass="ControlSemaforos"

# Terminal 3 (Servicio de Analítica):
mvn exec:java -Dexec.mainClass="Analitica"
```

### Paso 3: Iniciar PC1 (Ingesta)
Abre 2 terminales en el PC1:
```bash
# Terminal 1 (Broker Multihilo):
mvn exec:java -Dexec.mainClass="BrokerMultihilo"

# Terminal 2 (Simulación de Sensores):
# Para ejecutar en modo normal:
mvn exec:java -Dexec.mainClass="Sensores"
```

### Paso 4: Iniciar Monitoreo y Consulta (PC3)
Arranca la consola interactiva del operador:
```bash
# Abrir otra terminal en PC3:
mvn exec:java -Dexec.mainClass="MonitoreoConsulta"
```

---

## 4. Simulación de Fallos y Pruebas Especiales

### 4.1. Fallo-Parada de BD y Conmutación Transparente (Failover)
1.  Con el sistema corriendo, haz consultas históricas u obtén datos en tiempo real desde `MonitoreoConsulta`. Verás que el origen de datos es `BD_PRINCIPAL (PC3)`.
2.  Detén la base de datos principal en el **PC3** presionando `Ctrl+C` en su terminal.
3.  Vuelve a realizar una consulta en `MonitoreoConsulta`. Notarás que el sistema continúa operando de forma ininterrumpida imprimiendo:
    `[MONITOREO-FALLOVER] PC3 no responde. Conectando con BD Replica en PC2 de forma transparente...`
    El origen cambiará automáticamente a `BD_REPLICA (PC2)`.

### 4.2. Recuperación y Protocolo de Sincronización
1.  Con `BdPrincipal` caída, permite que los sensores sigan enviando datos. El servicio de Analítica (PC2) guardará estos registros exclusivamente en `replica.db`.
2.  Vuelve a arrancar la `BdPrincipal` en el **PC3**.
3.  A los pocos segundos, el hilo de latidos de `Analitica.java` detectará que PC3 responde, consultará la diferencia de registros entre bases de datos y sincronizará en ráfaga todo el historial faltante. Verás en la consola:
    `[DETECCION-FALLAS] PC3 (BD Principal) ha RESUCITADO.`
    `[SINCRONIZACION] Se encontraron X registros nuevos en la replica para sincronizar.`
    `[SINCRONIZACION] Sincronización exitosa. Base de datos principal actualizada.`

### 4.3. Simulación de Fallos en Sensores (Inyección de Fallas)
Puedes arrancar la simulación en el **PC1** pasando parámetros especiales de Maven para simular diferentes fallos del sistema:

*   **Simular Ruptura Física** (desactiva aleatoriamente el 20% de los sensores):
    ```bash
    mvn exec:java -Dexec.mainClass="Sensores" -Dexec.args="-ruptura"
    ```
*   **Simular Omisión de Canal** (pierde un 15% de los paquetes en la red):
    ```bash
    mvn exec:java -Dexec.mainClass="Sensores" -Dexec.args="-omision 15"
    ```
*   **Simular Fallo de Temporización** (añade 2000 ms de latencia artificial):
    ```bash
    mvn exec:java -Dexec.mainClass="Sensores" -Dexec.args="-temporizacion 2000"
    ```
*   **Combinado**:
    ```bash
    mvn exec:java -Dexec.mainClass="Sensores" -Dexec.args="-ruptura -omision 10 -temporizacion 1000"
    ```

### 4.4. Prueba de Criptografía y Seguridad (Cubo de McCumber)
El sistema protege los comandos críticos (como el paso de ambulancias) contra inyecciones de red mediante **HMAC-SHA256**:
1.  Cuando se activa la opción 3 (Ola Verde) en `MonitoreoConsulta`, el cliente genera un timestamp y computa una firma criptográfica con la clave simétrica precompartida.
2.  La Analítica valida la integridad. Si intentas inyectar un comando falso desde un script externo sin la firma o con datos modificados, la Analítica imprimirá:
    `[ANALITICA-SEGURIDAD] Alerta: Firma no valida en comando` y rechazará el cambio de luz.

### 4.5. Sincronización de Doble Semáforo
Visualiza en la terminal de `ControlSemaforos` (PC2) cómo se gestionan de forma coordinada los semáforos de **Carrera** y **Calle** por intersección:
*   Ciclo alternativo normal de 15s.
*   En caso de congestión en Carrera, el verde de Carrera se extenderá automáticamente a 30s mientras Calle espera en rojo, equilibrando dinámicamente el tránsito.
