# 8. Protocolo de Pruebas (Orientado a la Entrega Final)

Este protocolo define la metodología a ejecutar para la entrega final, en donde se validará la funcionalidad total del sistema distribuido y se responderá empíricamente a la exigencia de tolerancia de fallos y rendimiento continuo. En concordancia teórica, el protocolo se divide en los siguientes aspectos de testing de software:

### 8.1 Objetivo
Evaluar el rendimiento de la plataforma de semaforización urbana (Ingesta, Análisis y Persistencia) a través del middleware ZeroMQ (ZMQ), simulando diferentes picos de tráfico vehicular y distintos tipos de dispositivos o interfaces de consulta.

### 8.2 Prueba de carga
Simularemos usuarios virtuales y vehículos en tránsito constante publicando información de sensores pasivos (Espiras, GPS y Cámaras). El objetivo es someter al sistema a un tráfico recurrente en un **intervalo de 10 segundos continuos** durante un lapso prologado, evaluando si las Bases de Datos logran asentar los registros sin pérdida de paquetes (Rendimiento estable).

### 8.3 Pruebas de estrés
Aplicaremos un aumento sustancial de eventos simulando una congestión caótica inyectando datos cada **5 segundos o menos**. El objetivo es forzar la red y diagnosticar la estabilidad del servidor en un evento pico, calculando cuántas colas del Broker ZeroMQ se desbordan o dónde sucumbe la Base de Datos Réplica (PC2) antes de fallar.

### 8.4 Pruebas de escalabilidad
Agregaremos "Intersecciones (Sensores) virtuales" de forma incremental (escalamiento vertical y horizontal del número de usuarios virtuales lanzando eventos) para medir la capacidad óptima de carga del servidor y sus tiempos de respuesta. A su vez, compararemos frente al código de un "Broker Multihilo" para comprobar qué tanto escala nuestra solución arquitectónica frente a un cuello de botella.

### 8.5 Monitoreo de usuarios reales (Terminales)
Realizaremos un seguimiento de las acciones de los usuarios emulados durante la interacción directa con el sistema de control. Especificamente, se trazará el patrón completo del operador usando el Módulo de Monitoreo (PC3) cuando efectúe un comando agresivo de `OLA_VERDE` forzada, verificando que no ocurra caída en el rendimiento mientras se procesa la condición.

### 8.6 Métricas de rendimiento
Tal cual establecen los Indicadores Clave (KPI), de este protocolo extraeremos mediciones puras de tiempos de carga entre máquinas (PC1 a PC2), las respuestas directas (latencia de red local al cambiar variables ZMQ), uso base de CPU/Memoria del entorno Java (JVM) y estimaciones del ancho de banda exigido bajo estrés.

### 8.7 Informes y Análisis
Con todos los resultados extraídos, crearemos un informe de rendimiento con información vital sobre picos e inyecciones de datos, derivando a recomendaciones sobre tolerancia a fallos, balance de usuarios simultáneos y mejoras probables de concurrencia. Adicional, presentaremos las métricas graficadas requeridas en la siguiente sección.

### 8.8 Pruebas de Resiliencia de Red y Saturación de Colas (ZeroMQ HWM)
*   **Objetivo:** Validar la resiliencia del software ante latencia extrema en la red (Network Partitioning) y prevenir caídas por _Out-Of-Memory (OOM)_.
*   **Metodología:** Introduciremos latencia artificial en la tarjeta de red (ej. 500ms de retraso entre PC1 y PC2). ZeroMQ acumulará mensajes en memoria hasta alcanzar su _High Water Mark_ (HWM). El objetivo es verificar que al llenarse la cola, los mensajes se descarten ordenadamente (Drop) en lugar de crashear abruptamente la Máquina Virtual de Java.

### 8.9 Pruebas de Concurrencia (Thread-Safety y Deadlocks)
*   **Objetivo:** Asegurar que las implementaciones Multihilo en el Broker y en el Control de Semáforos están libres de condiciones de carrera y bloqueos mutuos (_Deadlocks_).
*   **Metodología:** Múltiples hilos inyectarán comandos de semáforos paralelos mientras un script de estrés demanda las métricas usando `ReentrantLock`. Observaremos mediante un Thread Dump (`jstack`) que ningún hilo quede bloqueado en estado _WAITING_ indefinidamente.

---

# 9. Obtención de las Métricas de Rendimiento de la Tabla 1 (Mecanismos)

De acuerdo con lo requerido textualmente y derivado de los KPIs base estipulados para la Entrega Final (Tabla 1), el sistema se preparará para capturar los indicadores de la siguiente manera operativa:

### 9.1 Rendimiento / Throughput (Solicitudes almacenadas en BD en 2 minutos)
*   **Variable a medir (KPI - Rendimiento):** Cantidad de unidades de datos procesadas durante este período exacto.
*   **Mecanismo Computacional:**
    *   Mientras la Prueba de Carga fluye, dispararemos una consulta local a las bases de datos (SQLite) haciendo un conteo exacto (`COUNT(*)`) de todas las `solicitudes` insertadas cuando el cronómetro marque 0 (`$T_{inicio}$`).
    *   Pasados los 2 minutos estrictos, volveremos a ejecutar la consulta (`$T_{fin}$`). La diferencia restada de eventos será el resultado matemático del caudal sistémico.
    *   Este cálculo se refrendará apoyándose en el "Hilo de Métricas Intramemoria" del Nodo 1 (Broker) que exportará paralelamente en terminal una validación base.

### 9.2 Tiempo de Latencia / Respuesta (Tiempo desde Usuario a Semáforo)
*   **Variable a medir (KPI - Tiempo de Latencia/Respuesta):** La duración de tiempo que transcurre entre una solicitud directiva manual y la respuesta del sistema.
*   **Mecanismo Computacional:**
    *   A través de la librería `java.time` de Java (Timestamps Mils o Iso-8601), marcaremos como `$T_0$` (Timestamp 0) el instante puro y preciso en el que un operador teclee desde el PC3 una solicitud de Ola Verde prioritaria.
    *   Cuando ese mensaje se encamine por el patrón ZMQ y desencadene el ajuste físico en el log terminal del Semáforo (Ubicado en el PC2), sellaremos ese instante como `$T_1$` (Timestamp 1).
    *   Toda medición será tabulada localmente y restada (Latencia = $T_1 - T_0$), arrojando promedios milisegundo a milisegundo de las respuestas.

### 9.3 Extracción Adicional de Utilización de Recursos y Memoria
*   **Uso de CPU, Red y Memoria (RAM):** Se aplicará el uso de administradores visuales de Java (`JConsole` / `VisualVM`) cruzados con simulaciones ZMQ de alta densidad para visualizar si el problema de Latencia nace del "Ancho de Banda" saturado en el Sistema Operativo, o por estrangulación en el *Heap* de memoria. Todo se recopilará para alimentar la Tabla 1 de Escenarios de Prueba.

### 9.4 Cuellos de Botella de E/S (Latencia de I/O en SQLite)
*   **Significado:** SQLite es una base de datos embebida que bloquea el archivo entero durante las transacciones de escritura. Medir esto es vital para sistemas concurrentes.
*   **Metodología Categórica:** Monitorearemos cuánto tiempo (ms) el hilo de ZMQ `PULL` se pasa esperando que SQLite libere el candado de escritura (Write Lock). Si la *Latencia de Disco* supera la tasa de llegada de mensajes de ZeroMQ, declararemos a la base de datos como el verdadero Cuello de Botella del ecosistema.
