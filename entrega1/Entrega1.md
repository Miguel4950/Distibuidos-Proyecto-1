```
Proyecto Entrega 2
```
**Introducción a Sistemas Distribuidos**

```
Realizado por:
```
```
Miguel Acuña
```
```
Juan David Acuña Lesmes
```
```
Samuel Manrique
```
```
Docente Rafael Páez Méndez
```
```
Pontificia Universidad Javeriana
```
```
Ingeniería de Sistemas
```
```
2026
```

## Tabla de Contenidos:


- 1. Introducción
- 2. Descripción del Sistema
   - 2.1 Objetivo.........................................................................................................................
   - 2.2 Arquitectura...................................................................................................................
   - 2.3 Componentes.................................................................................................................
   - 2.4 Patrones de Comunicación
   - 2.5 Flujo de Datos
   - 2.6 Tolerancia a Fallos
   - 2.7 Tecnologías
- 3. Modelos del Sistema
   - 3.1 Modelo Arquitectónico
   - 3.2 Modelo de Interacción
   - 3.3 Modelo de Fallos.........................................................................................................
   - 3.4 Modelo de Seguridad
- 5. Diseño del Sistema
   - 5.1 Diagrama de Despliegue
   - 5.2 Diagrama de Clases.....................................................................................................
   - 5.3 Diagrama de Componentes
- 6. Inicialización del Sistema
   - 6.1 Matriz del entorno urbano simulado
   - 6.2 Sensores
   - 6.3 Variables de configuración del sistema
- 7. Reglas del Sistema
   - 7.1 Reglas de Congestión
   - 7.2 Reglas de Tráfico Normal
   - 7.3 Reglas de Estado Intermedio
   - 7.4 Reglas de priorización de ambulancias
   - 7.5 Ejemplos de decisiones de sistema
- 8. Protocolo de Pruebas
   - 8.1 Objetivo.......................................................................................................................
   - 8.2 Prueba de carga
   - 8.3 Pruebas de estrés
   - 8.4 Pruebas de escalabilidad
   - 8.5 Monitoreo de usuarios reales (Terminales)
   - 8.6 Métricas de rendimiento
   - 8.7 Informes y Análisis
   - 8.8 Pruebas de Resiliencia de Red y Saturación de Colas (ZeroMQ HWM)
   - 8.9 Pruebas de Concurrencia (Thread-Safety y Deadlocks)
- 9. Métricas de Rendimiento
   - 9.1 Rendimiento / Throughput (Solicitudes almacenadas en BD en 2 minutos)
   - 9.2 Tiempo de Latencia / Respuesta (Tiempo desde Usuario a Semáforo)
   - 9.3 Extracción Adicional de Utilización de Recursos y Memoria
   - 9.4 Cuellos de Botella de E/S (Latencia de I/O en SQLite)
- 10. Conclusiones
- 11. Bibliografía


## 1. Introducción

Un sistema distribuido se define como una colección de computadoras independientes que dan al
usuario la impresión de constituir un único sistema coherente [[1]. Esto implica que asi el sistema
esté compuesto por componentes autónomos, la organización y composición interna entre las
computadoras permanecen ocultas para el usuario, quien al final es quien interactúa con el sistema.

De esta forma, podemos decir que los sistemas distribuidos permiten dividir el procesamiento de
información entre múltiples nodos que cooperan mediante mecanismos de comunicación. Este
enfoque lo podemos visualizar siendo aplicado en aplicaciones que requieren procesamiento
concurrente, manejo de eventos en tiempo real y tolerancia a fallos.

En este proyecto desarrollamos un sistema distribuido que simula un entorno de monitoreo y control
de tráfico urbano. El sistema está compuesto por múltiples servicios que generan, procesan y
almacenan eventos relacionados con el estado actual del tráfico en ciertas intersecciones de una
ciudad representada con una matriz de posiciones. A partir de los eventos generados por sensores
simulados, el sistema realiza un análisis del estado del tráfico y envía instrucciones a un módulo de
control de semáforos.

La comunicación entre servicios se implementa utilizando el middleware de mensajería ZeroMQ,
más específicamente, se utiliza el patrón de comunicación Publish/Subscribe para la comunicación
de los mensajes generados por los sensores, que permite separar a aquellos que producen los eventos
de los que los consumen.

El sistema se organiza principalmente en tres nodos que tienen distintas responsabilidades: Un nodo
encargado de generar y distribuir eventos, un nodo de procesamiento, responsable de analizar el
estado del tráfico y ejecutar reglas de control, y un nodo de persistencia que almacena los datos
generados y permite el monitoreo del sistema.

El objetivo principal de este proyecto es diseñar e implementar un sistema distribuido funcional que
permita explorar conceptos fundamentales como la comunicación basada en eventos, replicación de
datos, independencia entre servicios y manejo de fallos dentro de una arquitectura distribuida.


## 2. Descripción del Sistema

### 2.1 Objetivo.........................................................................................................................

Plataforma distribuida para la gestión inteligente del tráfico urbano, diseñada para monitorear,
analizar y responder a las condiciones de tráfico en tiempo real mediante la coordinación de sensores,
semáforos y bases de datos desplegados en 3 máquinas virtuales interconectadas por red LAN.

### 2.2 Arquitectura...................................................................................................................

El sistema sigue una arquitectura distribuida de 3 nodos, donde cada nodo ejecuta servicios
específicos que se comunican mediante ZeroMQ (JeroMQ en Java) utilizando patrones de
comunicación síncronos y asíncronos.

```
Nodo IP Servicios Rol
```
```
PC1 10.43.98.198 BrokerMultihilo, Sensores
```
```
Generación y distribución de
datos
```
```
PC2 10.43.98.
```
```
Analítica, ControlSemáforos,
BdRéplica
```
```
Procesamiento, actuación y
respaldo
```
```
PC3 10.43.99.183 BdPrincipal, Monitoreo
```
```
Persistencia principal e interfaz
de operador
```
### Tabla 1

### 2.3 Componentes.................................................................................................................

**Sensores (PC1):** Simulan 15 sensores (3 por intersección: cámara, espira magnética, GPS)
distribuidos en una cuadrícula 5×5 con 5 intersecciones activas (INT-A1, INT-B3, INT-C5, INT-D2,
INT-E4). Cada sensor genera eventos JSON con datos de tráfico (volumen de cola Q, velocidad
promedio Vp, densidad D) y los publica mediante el patrón PUB cada 10 segundos.

**BrokerMultihilo (PC1):** Hilo dedicado que ejecuta un proxy XSUB/XPUB de ZeroMQ. Recibe
todos los eventos publicados por los sensores (XSUB) y los retransmite a los suscriptores (XPUB) de
forma transparente, desacoplando a los productores de los consumidores.


**Analítica (PC2):** Servicio multihilo que cumple dos funciones:

- **Hilo 1 (SUB):** Se suscribe al broker, recibe eventos de sensores, evalúa las reglas de tráfico
    (NORMAL: Q<5, Vp>35, D<20; INTERMEDIO; CONGESTION: Q>=12 o Vp<20 o D>=30)
    y envía los resultados a las BDs vía PUSH y los comandos a los semáforos.
- **Hilo 2 (REP):** Atiende solicitudes síncronas del Monitoreo para priorización de vías (ola
    verde por paso de ambulancias).

Ambos hilos comparten un HashMap<String, HashMap<String, Object>> protegido por un
ReentrantLock para almacenar el estado actual de cada intersección.

**ControlSemáforos (PC2):** Recibe comandos de la analítica vía PULL. Gestiona el ciclo automático
de cada semáforo (verde/rojo cada 15 segundos) y puede extender el verde a 30s (congestión) o 45s
(ola verde por emergencia).

**BdPrincipal (PC3) y BdRéplica (PC2):** Ambas reciben datos de la analítica vía PULL y los
almacenan en SQLite. La réplica mantiene una copia idéntica de los datos para tolerancia a fallos.
Además, atienden consultas del Monitoreo vía REQ/REP.

**Monitoreo (PC3):** Interfaz de línea de comandos para el operador del sistema. Permite:

1. Consultar el estado actual de una intersección
2. Consultar el historial de una intersección
3. Ver estadísticas generales del sistema
4. Forzar priorización de vía (ola verde)
5. Ver throughput del sistema
6. Consultar datos de una intersección en BD

### 2.4 Patrones de Comunicación

```
Patrón Tipo Componentes Uso
```
```
PUB/SUB Asíncrono
```
```
Sensores → Broker →
Analítica
```
```
Distribución de eventos
de sensores
```
```
PUSH/PULL Asíncrono
```
```
Analítica → BdPrincipal,
BdRéplica
```
```
Persistencia de datos
evaluados
```
```
PUSH/PULL Asíncrono Analítica → ControlSemáforos
```
```
Envío de comandos a
semáforos
```

```
REQ/REP Síncrono Monitoreo → Analítica
```
```
Priorización de vías (ola
verde)
```
```
REQ/REP Síncrono
```
```
Monitoreo →
BdPrincipal/BdRéplica
```
```
Consultas de datos al
operador
```
### Tabla 2

### 2.5 Flujo de Datos

1. Los sensores generan eventos JSON y los publican (PUB) al broker cada 10 segundos.
2. El broker retransmite los eventos (XSUB→XPUB) a la analítica sin modificarlos.
3. La analítica evalúa las reglas de tráfico y determina el estado (NORMAL, INTERMEDIO,
    CONGESTION).
4. Si hay cambio de estado, envía un comando al semáforo (PUSH) para ajustar el ciclo.
5. El evento procesado se envía simultáneamente a la BD principal y a la BD réplica (PUSH
    dual).
6. El monitoreo permite al operador consultar datos (REQ/REP a BD) o forzar semáforos
    (REQ/REP a analítica).

### 2.6 Tolerancia a Fallos

El sistema implementa enmascaramiento de fallos con conmutación automática (failover):

- **Ante caída de BdPrincipal (PC3):** La analítica detecta el fallo mediante el flag NOBLOCK
    en el socket PUSH. A partir de ese momento, los datos se persisten únicamente en la BD
    réplica (PC2). El monitoreo detecta el timeout de 5 segundos en su socket REQ y se reconecta
    automáticamente a la réplica.
- **Ante recuperación de BdPrincipal:** La analítica detecta la reconexión y reanuda el envío
    dual (principal + réplica), imprimiendo [ANALITICA] PC3 se recupero!.

El servicio de sensores, broker, analítica y semáforos continúan operando sin interrupción durante
todo el proceso de failover.

### 2.7 Tecnologías

```
Tecnología Versión Uso
```
```
Java 11 Lenguaje de implementación
```

```
JeroMQ 0.6.0 Comunicación distribuida (ZeroMQ para Java)
```
```
SQLite (sqlite-jdbc) 3.44.1.0 Persistencia de datos
```
```
org.json 20231013 Serialización/deserialización JSON
```
```
Maven 3.8+ Gestión de dependencias y compilación
```
### Tabla 3

## 3. Modelos del Sistema

### 3.1 Modelo Arquitectónico

Para el diseño de este sistema, se analizan los componentes bajo las cuatro preguntas fundamentales
que definen una arquitectura distribuida:

**a. Entidades y comunicación**

Las entidades principales que interactúan en el sistema son procesos y componentes.

- **Procesos Productores:** Los simuladores de sensores (Espiras, Cámaras, GPS) en el PC1.
- **Entidad Intermediaria:** El proceso Broker ZMQ en el PC1.
- **Procesos Consumidores y de Control:** El Servicio de Analítica y el Servicio de Control de
    Semáforos en el PC2.
- **Procesos de Gestión y Persistencia:** El Servicio de Monitoreo y consulta, y los gestores de
    las Bases de Datos (Principal y Réplica) en PC3 y PC2 respectivamente.

**b. Paradigma de comunicación**

Dentro de la arquitectura en capas de un sistema distribuido, el middleware se ubica como una capa
de software intermedia (por encima del hardware de red y el sistema operativo, y por debajo de las
aplicaciones). Para la plataforma de tráfico urbano, es obligatorio el uso de la librería ZeroMQ (ZMQ)
como middleware de comunicación. ZeroMQ actúa como un intermediario (broker) en el PC1,
aislando la complejidad de la red [3].

El sistema utiliza el paradigma de paso de Mensajes a través de la librería ZeroMQ, implementando
tres patrones específicos [5]:

1. **Publish/Subscribe (PUB/SUB):** Facilita una comunicación asíncrona de uno a muchos. Este
    patrón permite que un publicador envíe mensajes a un "tópico" sin saber quiénes son los
    suscriptores. Se utiliza para que los sensores en el PC1 envíen continuamente sus eventos de
    tráfico sin bloquearse esperando confirmación. El broker enruta eficientemente estos tópicos


```
al Servicio de Analítica en el PC2. A nivel de código, los sensores emplean sockets
ZMQ_PUB y el servicio de analítica se suscribe utilizando ZMQ_SUB.
```
```
Imagen 1 : Visualización del patrón Publish/Subscribe [5]
```
2. **Parallel Pipeline (PUSH/PULL):** Se utiliza para la delegación de tareas asíncronas de una
    vía. Su propósito es distribuir datos hacia nodos que realizan pasos en un flujo de trabajo
    (fan-out). El Servicio de Analítica (PC2) actúa como nodo PUSH para distribuir información
    simultáneamente hacia el Servicio de Control de Semáforos y hacia las Bases de Datos
    (Principal y Réplica). Esto evita que la lógica de detección de congestión se congele mientras
    se escribe en disco o se cambia una luz. Para lograr esto, la Analítica utiliza un socket
    ZMQ_PUSH y los destinatarios utilizan sockets ZMQ_PULL.

```
Imagen 2 : Visualización del patrón Parallel Pipeline [5]
```
3. **Request/Reply (REQ/REP):** Se restringe su uso al Servicio de Monitoreo y consulta en el
    PC3, donde un usuario humano necesita una comunicación síncrona de dos vías para solicitar
    consultas, históricos de la red de semaforización o emitir comandos forzados (priorización
    de ambulancia) y recibir confirmación, aquí es donde se requiere una respuesta inmediata a
    una solicitud puntual. Es el patrón clásico para conectar clientes con servicios, donde el
    módulo de monitoreo emplea un socket ZMQ_REQ (que se bloquea a la espera de la
    información) y la BD o Analítica le responde a través de un socket ZMQ_REP.


```
Imagen 3 : Visualización del patrón Request/Reply [5]
```
**c. Roles en la arquitectura**

El sistema se basa principalmente en el Modelo Cliente-Servidor, con la particularidad de que un
servidor puede actuar como cliente de otro:

- **Servidores:** El Broker (servidor de mensajería), la Analítica (servidor de decisiones para el
    monitoreo) y las Bases de Datos (servidores de persistencia).
- **Clientes:** Los sensores (clientes del broker), el servicio de monitoreo (cliente de la analítica
    y de la BD) y la analítica (cliente de las bases de datos y del control de semáforos).

**d. Ubicación y mapeo**

Los componentes se mapean en nodos físicos estáticos para garantizar la organización de la red:

- **PC1 (10.43.98.198):** Nodo de Ingesta. Mapea procesos sensores y el broker.
- **PC2 (10.43.98.199):** Nodo de Procesamiento y Respaldo. Mapea la lógica de analítica, el
    control físico de semáforos y la BD réplica.
- **PC3 (10.43.99.183):** Nodo de Gestión y Persistencia. Mapea la interfaz de monitoreo y la
    BD principal.

**Para ver esto más detalladamente ver el Diagrama de componente en página 20**

**e. Tipo de Comunicación e Intermediario**

Es muy importante en la arquitectura de sensores la comunicación Indirecta ya que permite:

- **Desacoplamiento Espacial:** Los sensores no conocen la ubicación ni la identidad del
    servicio de analítica.
- **Desacoplamiento Temporal:** Gracias al uso del Broker ZMQ como intermediario (gestor de
    colas), el emisor y el receptor no necesitan estar activos al mismo tiempo para que el mensaje
    se gestione. El bróker centraliza la suscripción de tópicos asociados a los tres tipos de
    sensores (Espiras, Cámaras, GPS).


**f. Variaciones del Modelo**

Se aplica la división de responsabilidades por capas de software (Aplicación, Middleware, SO,
Hardware). Específicamente, se utiliza el concepto de servicios proporcionados por múltiples
servidores, donde el servicio de base de datos se mantiene en copias replicadas en PC3 y PC2 para
garantizar la disponibilidad del sistema.

**g. Flujo de Datos General**

El ciclo de vida de la información en el sistema sigue un flujo unidireccional principal con
ramificaciones para control y persistencia:

1. **Captura (PC1):** Los sensores simulados generan eventos de tráfico (JSON) y los publican
    (PUB) hacia el Broker ZMQ.
2. **Distribución (PC1 a PC2):** El Broker centraliza y reenvía (PUB) estos eventos hacia el
    Servicio de Analítica.
3. **Procesamiento y Control (PC2):** La Analítica evalúa (SUB) las reglas de tráfico. Si amerita
    un cambio, emite un comando (PUSH) al Servicio de Control de Semáforos para alterar el
    estado físico (luz roja/verde).
4. **Persistencia (PC2 a PC3 y PC2 local):** Simultáneamente, la Analítica despacha (PUSH) el
    estado procesado hacia la BD Principal (PC3) y la BD Réplica (PC2) para almacenamiento
    histórico.
5. **Intervención (PC3 a PC2):** De manera bidireccional bajo demanda, el usuario en el Servicio
    de Monitoreo (PC3) puede consultar el estado (REQ/REP) a la base de datos o inyectar un
    comando directo a la Analítica para priorizar una vía.

### 3.2 Modelo de Interacción

El modelo de interacción define las características que afectan el comportamiento individual y
colectivo de los procesos en nuestra plataforma de Gestión Inteligente de Tráfico Urbano, centrándose
en cómo interactúan y cómo se ven afectados por el paso del tiempo y las limitaciones de la red.

**Para ver estas interacciones más detalladamente ver el Diagrama de secuencia en página 21**

**a. Comunicación y Sincronismo del Sistema**

Nuestro sistema no asume un comportamiento temporal perfecto, por lo que se rige principalmente
bajo un modelo asíncrono.

- La generación de datos por parte de los sensores en el PC1 (cada 5 o 10 segundos) y su envío
    a través del Broker ZMQ hacia el Servicio de Analítica (PC2) se realiza de forma asíncrona
    mediante los patrones PUB/SUB y PUSH/PULL. Esto evita cuellos de botella, ya que el PC
    no se bloquea esperando a que el PC2 procese la información.
- Por otro lado, la interacción introduciría un comportamiento síncrono de forma aislada
    únicamente cuando el usuario utiliza el Servicio de Monitoreo en el PC3 para realizar


```
consultas históricas a la Base de Datos o emitir comandos directos, utilizando el patrón
REQ/REP donde sí se espera una respuesta en un tiempo limitado.
```
**b. Impacto de la Latencia y Retardos**

Dado que los componentes están distribuidos en tres máquinas distintas el tiempo que transcurre
desde que un sensor genera un evento hasta que el semáforo cambia o el dato se guarda en la BD se
podría ver afectado por los siguientes retardos:

- **Retardo de Encolamiento:** Es el factor más incidente del proyecto. A medida que se escale
    de 1 a 2 sensores generando datos simultáneamente, los eventos (JSON) se acumularán en
    las colas del Broker ZMQ en el PC1 antes de ser transmitidos.
- **Retardo de Procesamiento (Nodo):** Es el tiempo que le toma al Servicio de Analítica en el
    PC2 evaluar las reglas de tráfico (por ejemplo, si la longitud de cola es Q<5 y la velocidad
    Vp>35) sobre el mensaje recibido para decidir si ordena el cambio de luz a rojo o verde.
- **Retardo de Transmisión y Propagación:** El tiempo físico que tardan los mensajes JSON
    en ser puestos en la red por las tarjetas de red de los equipos y viajar a través del medio local
    (LAN) entre el PC1, PC2 y PC3.

**c. Variables de Rendimiento a Tener en cuenta**

Para los experimentos de rendimiento, el sistema podría considerar el impacto de las siguientes
variables de interacción:

- **Throughput (Tasa de transferencia):** Se media directamente evaluando la cantidad de
    solicitudes almacenadas en la BD del PC3 en un intervalo de tiempo en minutos determinado.
    Representa la capacidad real del sistema para procesar el tráfico de eventos sin descartar
    paquetes.
- **Jitter:** Una variación grande en el retardo de llegada de los eventos desde el PC1 podría
    causar que el servicio de analítica reciba ráfagas de datos desfasados, afectando la toma de
    decisiones en tiempo real sobre los semáforos.
- **Ancho de banda (Bandwidth) y Tasa de Error:** Aunque la red LAN entre los tres PCs tiene
    una capacidad máxima teórica, la saturación por alta generación de datos (cada 5 segundos)
    podría inducir a una mayor tasa de error en la recepción de las tramas.

**d. Temporización y Ordenamiento de Eventos**

El sistema enfrenta el desafío de la tasa de deriva, ya que los relojes del PC1, PC2 y PC3 no están
perfectamente sincronizados.

- Un sensor en el PC1 podría marcar un evento a las 15:10:00Z, pero el PC2 procesarlo con un
    tiempo local distinto. Para mitigar esto, la analítica confía en el timestamp (tiempo lógico y
    físico de origen) incluido dentro del JSON del evento, no en su reloj de recepción.
- **Ordenamiento Lógico de Lamport:** Es vital para la priorización de vías. Si la analítica en
    PC2 está evaluando cambiar un semáforo a rojo por tráfico normal, pero casi
    simultáneamente llega una indicación directa (síncrona) desde el PC3 solicitando prioridad
    para una ambulancia, el sistema debería ordenar estos eventos. Se infiere lógicamente que la


```
orden humana de emergencia del PC3 tiene precedencia (ordenamiento superior) sobre el
ciclo de evaluación automática, aplicando el cambio forzado a verde.
```
**e. Análisis de los Flujos de Interacción**

Para entender las prestaciones y el comportamiento colectivo del sistema, se detallan las interacciones
entre los componentes según su naturaleza temporal y patrones de comunicación:

- **Comunicación de Sensores al Broker (PC1):** Esta interacción es de naturaleza asíncrona y
    utiliza el patrón Publish-Subscribe (PUB/SUB). Los sensores actúan como publicadores
    constantes de eventos (longitud de cola, conteo vehicular, velocidad). La principal
    característica que afecta esta interacción es el retardo de transmisión, ya que los sensores
    deben serializar sus objetos JSON para ponerlos en la línea de comunicación del broker local.
- **Comunicación del Broker a la Analítica (PC1 a PC2):** Es una interacción asíncrona
    distribuida entre dos nodos físicos. El broker reenvía los tópicos a los que el Servicio de
    Analítica está suscrito. Aquí es donde el retardo de encolamiento y el jitter cobran mayor
    importancia ya que si la red está saturada o el broker recibe ráfagas de datos, la analítica
    podría recibir los paquetes con variaciones de tiempo que afecten la precisión de la detección
    de congestión en tiempo real.
- **Comunicación de la Analítica al Controlador de Semáforos (PC2):** Se utiliza una
    interacción asíncrona de una vía mediante el patrón PUSH/PULL. El objetivo es que la
    analítica emita órdenes de cambio de luz (rojo/verde) sin detener su ciclo de procesamiento
    de nuevos datos de tráfico. El retardo de procesamiento en el nodo PC2 es la variable crítica
    aquí, pues define cuánto tiempo pasa desde que se identifica una regla (ej. Q > 5) hasta que
    se genera el comando de control.
- **Comunicación de la Analítica a la Base de Datos (PC2 a PC3/PC2):** Esta interacción es
    asíncrona para garantizar la persistencia sin afectar el desempeño del sistema de control. Al
    utilizar PUSH/PULL, la analítica envía datos tanto a la BD Principal (PC3) como a la Réplica
    (PC2). El modelo podría considerar el ancho de banda disponible, ya que un flujo constante
    de actualizaciones de múltiples sensores hacia dos bases de datos simultáneamente podría
    saturar el throughput de la red.
- **Comunicación del Monitoreo a la Analítica (PC3 a PC2):** A diferencia de las anteriores,
    esta es una interacción síncrona basada en el patrón Request-Reply (REQ/REP). Cuando un
    usuario envía una indicación directa (ej. priorizar una ambulancia), el cliente en PC3 se
    bloquea esperando la confirmación de recepción y ejecución por parte de la analítica. Esta
    comunicación está sujeta a timeouts si el retardo de ida y vuelta (RTT) excede los límites
    definidos debido a la congestión del canal.

### 3.3 Modelo de Fallos.........................................................................................................

En la plataforma de Gestión Inteligente de Tráfico Urbano, tanto los procesos computacionales como
los canales de comunicación a través de la red local están expuestos a posibles anomalías. El modelo
de fallos permite comprender los efectos de estos errores y diseñar estrategias de resiliencia,
garantizando que el sistema no colapse ante la pérdida de un componente [7]][9].


**a. Fallos por Omisión**

Estos fallos ocurren cuando un proceso o canal no consigue realizar una acción esperada. En nuestra
arquitectura, se clasifican de la siguiente manera:

**A. En los Procesos:**

- **Fail-stop (Fallo-parada):** Es el escenario principal de falla contemplado en el diseño del
    proyecto. Ocurre si el nodo de Presentación y Persistencia (PC3) se dompe o se apaga
    repentinamente. Este fallo-parada debe ser detectado por los demás procesos
    (específicamente por el Servicio de Analítica en PC2) mediante un patrón de supervisión,
    como por ejemplo un Health Check periódico (latidos o heartbeats) o métodos de replicación
    en uno o todos los nodos [11].
- **Ruptura:** Podría ocurrir si un proceso de sensor simulado en el PC1 se cuelga
    silenciosamente. A diferencia del fail-stop, el Broker ZMQ en PC1 podría no detectar la
    parada de inmediato, simplemente dejaría de recibir publicaciones (PUB) de ese sensor en
    particular.

**B. En las Comunicaciones (Canales y Buffers):**

- **Omisión de envío y recepción:** Aunque la librería ZeroMQ gestiona los buffers de entrada
    y salida, si el Servicio de Analítica en el PC2 está sobrecargado procesando reglas de tráfico,
    su buffer de recepción podría llenarse. Si el PC1 sigue enviando datos, ocurriría un fallo por
    omisión de recepción, donde el mensaje llega al PC2, pero el proceso no lo recibe a tiempo,
    descartándolo.
- **Omisión del canal:** Un fallo físico en la red local (LAN) que impida que los mensajes de
    control de semáforos (PUSH) viajen del PC2 a los actuadores.

**b. Fallos Arbitrarios (Bizantinos)**

Se presentan cuando un componente omite pasos deseables o realiza acciones no intencionadas sin
un patrón claro.

- **Aplicación al proyecto:** Un sensor en PC1 podría sufrir una falla de software y comenzar a
    enviar datos erróneos en su JSON (ej. vehiculos_contados: -5 o velocidad_promedio: 999).
    Si bien ZeroMQ garantiza que el mensaje viaje, el Servicio de Analítica (PC2) debe estar
    diseñado para manejar estos fallos arbitrarios, filtrando anomalías para que el sistema de
    control de semáforos no tome decisiones desastrosas basadas en información corrupta.

**c. Fallos de Temporización**

Al haber definido el sistema como mayormente asíncrono, las restricciones estrictas de tiempo no
aplican a la ingesta de datos. Sin embargo, sí afectan partes puntuales:

- **Timeouts en la Comunicación Síncrona:** El patrón REQ/REP usado por el usuario en PC
    para hacer consultas directas a la BD es síncrono. Si la consulta toma demasiado tiempo (ej.
    la transmisión toma más del límite permitido por congestión en la red), el proceso en PC
    sufrirá un timeout, impidiendo que la respuesta esté disponible para el cliente en el intervalo
    esperado.


- **Tasa de Deriva del Reloj:** Si el reloj local de un PC excede el límite de su tasa de deriva
    (clock drift), los timestamps de los eventos generados por los sensores perderán su orden
    lógico temporal, dificultando la correlación de datos históricos.

**d. Enmascaramiento de Fallos y Fiabilidad**

El requerimiento más importante de resiliencia del proyecto se basa en el enmascaramiento de fallos
del PC3.

- **Manejo de la falla de la BD Principal:** Conociendo que la Base de Datos Principal en el
    PC3 puede sufrir un Fail-stop, el sistema implementa un patrón de detección (Health Check,
    TimeOuts o mediante algún método de replicación). Si el PC3 deja de responder, el Servicio
    de Analítica (PC2) detecta la caída inmediatamente.

```
Imagen 4 : Esta imagen es un ejemplo de cómo funciona el patrón Health Check de modo que cada
cierto tiempo un cliente te le está enviando a un servidor su estado actual, esta es una forma de
detectar si un componente está presentando fallas. [11]
```
- **Enmascaramiento y uso de la BD Réplica:** Para ocultar este fallo y que no afecte la
    operación, el sistema acude al uso de la Base de Datos Réplica localizada en el PC2. Dado
    que el Servicio de Analítica actualiza esta réplica constantemente de forma asíncrona (patrón
    PUSH/PULL), la base de datos de respaldo cuenta con la información histórica al día.
- **Redirección automática de almacenamiento:** Una vez confirmada la caída del PC3, el
    sistema ejecuta una redirección automática. El almacenamiento de los nuevos eventos
    generados por los sensores, así como las consultas del módulo de monitoreo, pasan a
    ejecutarse exclusivamente contra la BD Réplica en el PC2.
- **Continuidad del sistema:** Toda esta operación de redirección y recuperación es transparente
    para el cliente. Se garantiza la continuidad del sistema, permitiendo que la plataforma de
    tráfico urbano siga evaluando congestiones, cambiando semáforos y guardando métricas de
    forma ininterrumpida.
- **Fiabilidad (Comunicación uno a uno):** Para garantizar la Validez (que el mensaje llegue al
    buffer) y la Integridad (que el JSON del evento no cambie durante el tránsito), el sistema


```
confía en el protocolo TCP subyacente que utiliza ZeroMQ, asegurando que los comandos
críticos (como dar luz verde a una ambulancia) no lleguen corruptos a la intersección.
```
### 3. 4 Modelo de Seguridad (Cubo de McCumber)

El modelo de seguridad se encarga de verificar los procesos y sus interacciones contra posibles
ataques internos o externos. En la plataforma de Gestión Inteligente de Tráfico Urbano, donde se
toman decisiones críticas que afectan la movilidad física (control de semáforos), es importante
comprender las amenazan y definir estrategias para mitigarlas, evaluando el balance costo-beneficio
de su implementación.

**a. Pilares de la Seguridad** [10]

- **Confidencialidad:** Es la garantía de que la información solo sea accesible por entidades
    autorizadas.

```
Se aplica a la confidencialidad de los eventos. Un ejemplo de esto sería para evitar que un
atacante pasivo intercepte la red LAN y lea las métricas de volumen y velocidad generadas
por lo sensores (PC1), los canales de comunicación deben cifrarse. Si los JSON viajan en
texto plano, la confidencialidad se pierde.
```
- **Integridad:** Es la seguridad de que la información no sea alterada, borrada o manipulada de
    manera no autorizada durante su tránsito.

```
Se aplica a la integridad de los datos de tráfico. Por ejemplo, si un atacante intercepta un
mensaje del broker (PC1) y altera el JSON (ej. inyectando "falsa congestión" cambiando la
velocidad a 0 km/h), la analítica en el PC2 tomaría decisiones incorrectas. El sistema debe
garantizar que el paquete que salió del sensor sea exactamente el mismo que recibe la
analítica.
```
- **Disponibilidad:** Es la garantía de que los servicios y datos del sistema estén accesibles para
    los usuarios autorizados cuando se necesiten, incluso bajo ataques o fallos físicos.
    La disponibilidad se garantiza directamente mediante el uso de la BD Réplica en el PC2. Si
    el nodo principal de presentación y persistencia (PC3) sufre un ataque o colapsa, la
    redirección automática hacia la réplica asegura que la ciudad nunca se quede sin registro
    histórico ni control de semaforización.

**b. Amenazas a Procesos y Canales de Comunicación**

Dado que el sistema opera en una red distribuida (PC1, PC2 y PC3), existen vulnerabilidades claras
en la interacción de los componentes [10]:

- **Amenazas a los procesos (Identificación de Cliente/Servidor):** Cualquier proceso en la red
    local diseñado para admitir peticiones (como el Servicio de Analítica en PC2) podría recibir
    un mensaje de un proceso no autorizado. Por ejemplo, un atacante podría crear un script falso
    simulando ser el Servicio de Monitoreo del PC3 y enviar comandos por ZeroMQ para forzar


```
el cambio de todos los semáforos a verde (simulando una falsa indicación de ambulancia), ya
que por defecto los sockets básicos no determinan la identidad real del emisor.
```
- **Amenazas a los canales de comunicación:** Un atacante con acceso a la red local podría
    interceptar, alterar o insertar mensajes en tránsito. Al viajar las métricas de tráfico en formato
    de texto plano (como los JSON de volumen y velocidad generados por los sensores en PC1),
    estas podrían ser alteradas para inyectar datos de falsa congestión, forzando al algoritmo de
    analítica a tomar decisiones incorrectas.

**c. Denegación de Servicio (DoS y DDoS) [10]**

Esta es una de las amenazas más importa para la disponibilidad del sistema.

- Como los sensores envían datos constantemente, un atacante podría saturar el sistema
    mediante un ataque DoS enviando miles de falsos eventos PUB hacia el Broker ZMQ en el
    PC1, o directamente bombardeando el puerto PULL del Servicio de Analítica (PC2).
- Esto desbordaría las colas de mensajes de ZeroMQ, consumiendo los recursos de la máquina
    y provocando fallos de omisión (rechazo de paquetes legítimos), lo que dejaría a la ciudad
    sin control de tráfico.

```
d. Estrategias para Vencer las Amenazas (Canales Seguros)
[10]
```
Aunque para el alcance del proyecto la seguridad debe manejarse de forma que no complique
demasiado la implementación se pueden proponer algunas estrategias para enmascarar y prevenir
estas vulnerabilidades, Una opción podría ser el uso de Canales Seguros mediante la combinación de
Criptografía y Autenticación:

- **Criptografía:** Garantiza que los JSON de los eventos viajen cifrados, evitando que un intruso
    los altere o los lea (mitigando amenazas al canal).
- **Autenticación:** Garantiza que el Servicio de Analítica (PC2) solo acepte instrucciones de
    control provenientes explícitamente de la IP y credenciales del Servicio de Monitoreo (PC3)
    o del Broker (PC1). A nivel de aplicación en el PC3, se debe contemplar la protección de
    objetos mediante derechos de acceso. No cualquier operador de la interfaz de consulta y
    monitoreo debería tener los privilegios para ejecutar operaciones sensibles, como las
    indicaciones directas para priorización de vías. Estas operaciones deben estar restringidas
    mediante roles de usuario dentro de la capa de presentación.
- Nota de implementación: Para estas estrategias la librería ZeroMQ soporta protocolos de
    seguridad como CurveZMQ para establecer estos canales seguros. Para el alcance académico
    de esta primera fase del proyecto, implementar encriptación asimétrica añade una sobrecarga
    computacional que podría afectar las pruebas de rendimiento (throughput y latencia) [12].

## 5. Diseño del Sistema


### 5.1 Diagrama de Despliegue

```
Imagen 5: El diagrama de despliegue representa la distribución de los componentes del sistema de
monitoreo de tráfico necesarios para ejecutar la simulación.
```
El primer nodo, o PC1, es el componente de ingesta del sistema, encargado de simular los sensores
de tráfico y el broker de mensajería. Los sensores generan eventos los cuales son publicados a
través del broker utilizando publish-subscribe para que otros servicios reciban la información sin
tener conexiones directas entre ellos.

El segundo nodo, o PC2, es donde se hace todo el análisis de los eventos generados por los sensores
y de las condiciones de tráfico. A partir de este análisis el componente genera acciones que
modifiquen el estado de semáforos en intersecciones críticas. Además de esto, el PC2 es el
encargado de controlar los semáforos en sí, lo cual abarca ejecutar las decisiones tomadas por la
analítica. También aloja una base de datos réplica que almacena una copia de todos los eventos de


forma simultánea a la base de datos principal. En caso de que la base de datos principal no esté
disponible, el servicio de Monitoreo redirige automáticamente sus consultas a esta réplica.

El tercer nodo, o PC3, es el componente de persistencia y monitoreo. Aquí se encuentra la base de
datos principal, al igual que el servicio de monitoreo, que permite consultar información
almacenada y enviar comandos al sistema para ejecutar acciones de control.

### 4 .2 Diagrama de Clases

```
Imagen 6: Este diagrama describe la estructura interna del sistema, vista desde la perspectiva de
agrupamiento de módulos de Java, utilizando un enfoque netamente estático y procedimental.
```

### 5.3 Diagrama de Componentes

```
Imagen 7: Este diagrama representa la organización del sistema a nivel de módulos de
software.
```
### 4 .4 Diagrama de Secuencia


Imagen 8: Este diagrama describe el flujo COMPLETO de interacción entre los distintos
componentes del sistema durante el procesamiento de un evento de tráfico.


## 6. Inicialización del Sistema

Para un funcionamiento adecuado del sistema se requiere la definición de los recursos iniciales
necesarios que permitan establecer las condiciones sobre las cuales se ejecutarán las simulaciones.

Estos recursos iniciales también garantizan que todos los componentes funcionen de forma
consistente desde el inicio de la ejecución y que logren comunicarse correctamente dentro de la
arquitectura definida.

### 6.1 Matriz del entorno urbano simulado

El sistema simula una ciudad como una **matriz de intersecciones.** Es una matriz de **5x5** donde las
filas están identificadas con letras **A-E** y las columnas con número **1 - 5.**

Cada intersección se identifica con el formato

INT-FilaColumna por ejemplo:

#### INT-A1

#### INT-E5

#### INT-B2

Para las pruebas que realizaremos y para la demostración se utilizan unas intersecciones específicas,
donde se generarán eventos de tráfico y donde pueden existir sistemas de control de semáforos, estas
son:

#### INT-A1

#### INT-B3

#### INT-C5

#### INT-D2

#### INT-E4

### 5. 2 Sensores

Un factor a tener en cuenta dentro de los eventos generados por los sensores es que se incluye un
**TIMESTAMP** o marca de tiempo que indican el momento exacto en el que cada evento fue creado.


Esta información nos es útil ya que la podremos usar para realizar una medición del comportamiento
del sistema y evaluar su desempeño en las métricas, las cuáles serán explicadas y analizadas más
adelante.

Otro factor a tener en cuenta es que los eventos generados por los sensores son representados
utilizando estructuras de datos en **formato JSON (JavaScript Object Notation)** ya que es un
formato altamente utilizado en sistemas distribuidos por su minimalismo y portabilidad [[2].

**5. 2 .1 Cámaras de tráfico**

Las cámaras simulan los sistemas de monitoreo utilizados en la vida real para visualizar el flujo
vehicular en una ciudad.

Estos sensores generan eventos que incluyen la **longitud de cola de vehículos (Q)** y la **velocidad
promedio de los vehículos (Vp).**

La longitud de cola puede tomar valores entre **0 y 30 vehículos** mientras que la velocidad promedio
puede variar entre **0 y 50 km/h.**

Estos valores generados en los eventos son generados aleatoriamente por las funciones
**random.nextInt(31)** para generar el valor entero de Q entre 0 y 30 y
**Math.round(random.nextDouble() *50*10.0) /10.0** para generar el valor de la velocidad promedio
entre 0 y 50 km/h con un solo decimal en uso.

**5. 2 .2 Espiras**

Las espiras representan sensores instalados en el pavimento que permiten controlar el número de
vehículos que pasan sobre un punto específico en la vía.

Estos sensores generan eventos que incluyen el **número de vehículos detectados** y el **intervalo de
medición.**

El valor de vehículos contados es generado aleatoriamente por la función random.nextInt(41), es
decir, que puede variar entre 0 y 40 vehículos contados en un intervalo fijo de medición de 30
segundos, coincidiendo en tiempo con los cambios de semáforo. Adicionalmente, el sensor emite
estos reportes a la red cada 10 segundos (tiempo de emisión que se podrá reducir a 5 segundos según
el escenario de pruebas multihilo).

**5. 2 .3 GPS**

Los sensores GPS simulan datos que vienen de vehículos conectados que estén reportando
información sobre el tráfico.

Estos sensores generan eventos que incluyen la **velocidad promedio de los vehículos, la densidad
del tráfico (vehículos por kilómetro) y el nivel de congestión estimado.**

La velocidad promedio se calcula con la función Math.round(random.nextDouble() * 60 * 10.0) /
10.0, es decir, varía entre 0 y 60km/h. El nivel de congestión es asignado dependiendo primariamente


de la velocidad promedio y este nivel afecta la lectura de la densidad simulada de tráfico de la
siguiente forma:

Si la velocidad es menor a 10 km/h la congestión es “ALTA” (con alta densidad de 40 a 80 vehículos
por km). Si la velocidad está entre 10 y 40 km/h es “NORMAL” (simulando 15 a 45 vehículos por
km). Si la velocidad supera los 40 km/h la congestión es “BAJA” (con baja densidad de 1 a 20
vehículos por km).

### 6.3 Variables de configuración del sistema

Dentro de cada nodo del sistema se tienen ciertas variables de configuración que habilitan la
comunicación entre los servicios de cada máquina.

Estas variables son esencialmente las direcciones IP de los nodos que permite que los nodos se
conozcan entre sí y se comuniquen mediante el protocolo ZeroMQ.

En el **PC1** los sensores definen la variable **BROKER_IP y PUERTO_BROKER** que permitirán
crear el socket de publish para conectarse al broker por su puerto subscribe.

En el **PC2** se crean 3 variables de configuración relevantes dentro de **Analítica.java:**

**BROKER_IP** para suscribirse al broker del PC1 y recibir los eventos de los sensores.

**ANALITICA_IP** para envío de comandos al control de semáforos y datos a la base de datos réplica
ubicada en el PC2.

**BD_PRINCIPAL_IP** para envío de datos a la base de datos principal ubicada en el PC3.

En **BDReplica.java** se definen la IP de la máquina en **REPLICA_IP, PUERTO_PULL** para definir
el puerto de recepción de datos desde Analítica, **PUERTO_REP** para definir el puerto para consultas
del monitoreo y **BD_ARCHIVO** para definir el nombre del archivo sqlite utilizado para la base de
datos.

En **ControlSemaforos.java** se define únicamente la IP de la máquina en **ANALITICA_IP** para la
comunicación entre componentes.

Por último, en el **PC3** se tienen las siguientes variables:

En **BDPrincipal.java** se define la IP de la máquina en **BD_IP, PUERTO_PULL** para la recepción
de datos desde la analítica de **PC2** , **PUERTO_REP** para las consultas de monitoreo que necesite
realizar **BDReplica.java** en el **PC2** y el nombre del archivo sqlite de la base de datos principal en
**BD_ARCHIVO**.

En el archivo **Monitoreo.java** se define la IP del servicio de analítica en **ANALITICA_IP** , la IP de
la base de datos principal en **BD_PRINCIPAL_IP** , la IP de la base de datos réplica en
**BD_REPLICA_IP** , los sockets necesarios y una variable booleana llamada **usandoReplica** que
indica como bandera para informar si se está usando la base de datos replica o no.


## 7. Reglas del Sistema

Dentro del sistema el nodo del **PC2** es el encargado de procesar los eventos generados por los sensores
de tráfico y determinar el estado del tráfico en cada intersección (en este caso, las 5 definidas en la
inicialización del sistema) de la ciudad.

Utiliza 3 variables principales para evaluar las condiciones de los eventos recibidos mediante el patrón
Publish/Subscribe de ZeroMQ:

- **Q:** Es la longitud de cola, o el número de vehículos en espera detectados por las cámaras de
    tráfico.
- **Vp:** Velocidad promedio de los vehículos medida por los sensores GPS o cámaras.
- **D:** Densidad o número ESTIMADO de vehículos por kilómetro obtenido por los sensores
    GPS.

Los comandos generados por la analítica son enviados al control de semáforos posteriormente
utilizando un socket tipo PUSH de ZeroMQ.

### 7.1 Reglas de Congestión

El sistema considera que una intersección está congestionada cuando cumple al menos una de las
siguientes condiciones:

- 𝑄≥ 12 **Vehículo s en cola**
- **Vp<20** Km/h
- 𝐷≥ 30 **Vehículos por kilómetro**

Cuando cualquiera de estas condiciones se cumple, se clasifica el estado de dicha intersección como
**“CONGESTION”.** Esta clasificación de estado la analítica la valida antes que cualquier otra acción
para conocer si hay congestiones o no. Esto se ve dentro de la analítica del PC2 así:

**static String evaluarTrafico(int Q, double Vp, int D) {**

**if (Q >= 12 || Vp < 20 || D >= 30) {**

**return "CONGESTION";**

**}**

**if (Q < 5 && Vp > 35 && D < 20) {**

**return "NORMAL";**

**}**

**return "INTERMEDIO";**


**}**

Cuando se detecta la congestión, el servicio de analítica genera un comando para el sistema de control
de semáforos que tiene como objetivo mejorar el flujo vehicular en esa zona. En caso de haber
CONGESTION, el comando generado solicita extender el tiempo de luz verde en la intersección
afectada.

El comando generado tiene estas características:

- acción = EXTENDER_VERDE
- duración_verde = 30 (segundos)
- Motivo = “Congestión detectada”

### 7.2 Reglas de Tráfico Normal

Una intersección se considera en estado de tráfico normal cuando se cumplen **TODAS** estas
condiciones:

- 𝑄< 5 **Vehículos en cola**
- 𝑉𝑝> 35 **Km/h**
- 𝐷< 20 **Vehículos por kilómetro**

Cuando estas condiciones se cumplen, se clasifica el estado de dicha intersección como
**“NORMAL”.**

Ya que, cabe la redundancia, el estado de dicha intersección es normal, no se necesita intervención
sobre el semáforo y el sistema mantiene el funcionamiento estándar.

El comando enviado al servicio de control de semáforos para que defina el estado de dicha
intersección como NORMAL tiene la siguiente estructura:

- acción = CICLO_NORMAL
- duración_verde = 15 (segundos)
- Motivo = “Tráfico normal”

### 7.3 Reglas de Estado Intermedio

En caso de que las condiciones de tráfico no cumplan los criterios de congestión ni los de tráfico
normal, el sistema se clasifica como **“INTERMEDIO”.**

Puede ser interpretado como casos donde el tráfico comienza a incrementarse, pero todavía no alcanza
niveles de congestión.

Además de esto, el control de semáforos no genera comandos específicos para los casos en el que el
tráfico está en nivel INTERMEDIO.


### 7.4 Reglas de priorización de ambulancias

Además de los 3 estados de tráfico mencionados, se incluye un mecanismo de priorización para
vehículos de emergencia como ambulancias, la cual también puede activada desde el servicio de
monitoreo.

Cuando se recibe una solicitud de tipo **“PRIORIZACION”** se genera un comando especial para el
control de semáforos con la siguiente estructura:

- acción = OLA_VERDE
- duración_verde = 45 (segundos)
- Motivo = “Paso de emergencia”

```
Este comando mantendrá el semáforo en luz verde durante un periodo aún más largo de
tiempo que si fuese una congestión, permitiendo el paso inmediato de los vehículos de
emergencia.
```
### 7.5 Ejemplos de decisiones de sistema

**6 .5.1 Congestión detectada**

```
En la intersección C5 se generan 3 eventos (Cámara, GPS, Espira) que brindan la siguiente
información:
```
- Q = 12
- Vp = 14km/h
- D = 45 vehículos/km

```
Debido a que las reglas de tráfico definen que hay congestión si Q≥12 o Vp<20 o D≥30
```
```
se genera un comando con esta información al controlador de semáforos:
```
```
{
```
```
"interseccion": "INT-C5",
```
```
"timestamp": "2026- 04 - 06T02:18:45Z",
```
```
"accion": "EXTENDER_VERDE",
```
```
"duracion_verde": 30,
```
```
"motivo": "Congestion detectada"
```
```
}
```

**6 .5.2 Tráfico Normal**

```
En la intersección B3 se generan 3 eventos (Cámara, GPS, Espira) que brindan la siguiente
información:
```
- Q = 3
- Vp = 48km/h
- D = 12 vehículos/km

```
Q, Vp y D cumplen SIMULTANEAMENTE las condiciones para que el estado del tráfico
sea considerado normal, por ende, es catalogado como “NORMAL”.
```
```
6 .5.3 Priorización de ambulancia
El operador envía una solicitud desde el servicio de monitoreo para la intersección A1.
El sistema genera el siguiente comando para el controlador de semáforos que permite realizar
el cambio correspondiente:
```
#### {

```
"interseccion": "INT-A1",
```
```
"timestamp": "2026- 04 - 06T02:22:11Z",
```
```
"accion": "OLA_VERDE",
```
```
"duracion_verde": 45,
```
```
"motivo": "Paso de emergencia"
```
```
}
```
## 8. Protocolo de Pruebas

**7.1 Pruebas Funcionales y de Resiliencia**

**Prueba de Tráfico Normal (Caso de Prueba 1)**

**Descripción:** Se inicializan los 3 tipos de sensores (cámara, espira, GPS). Los simuladores operarán
de forma continua inyectando datos que cumplan frecuentemente los parámetros de normalidad (Q<5,
Vp>35 y D<20).


**Objetivo:** Verificar que el Servicio de Analítica identifique un flujo vehicular estable y el actuador
mantenga los ciclos de los semáforos sin alteraciones manuales.

**Métricas:**
Consistencia del Estado retornado: Debe ser siempre "NORMAL".
Impresión asíncrona de los cambios automáticos de semáforo: Debe ser exactamente a los 15
segundos.

**Prueba de Congestión Vehicular (Caso de Prueba 2)**

**Descripción:** Se intervienen temporalmente las variables estáticas de los sensores en una intersección
particular (Ej. INT-C5) para inyectar datos de alta ocupación (Q>=12 o Vp<20).

**Objetivo:** Observar la reacción en cadena asíncrona del sistema cuando se rompe el umbral de
normalidad, forzando la evaluación de la regla preventiva en la Analítica.

**Métricas:**
Disparo del comando inteligente: EXTENDER_VERDE.
Tiempo máximo de la luz de semáforo priorizada: Debe ajustarse a 30 segundos.

**Prueba de Intervención Manual (Ola Verde)**

**Descripción:** Durante la ejecución fluida del tráfico, un operador utilizará el Módulo de Monitoreo
(PC3) para enviar un comando síncrono directo, exigiendo a la Analítica la liberación inmediata de
una vía para el paso de emergencia de una Ambulancia.

**Objetivo:** Comprobar la arquitectura jerárquica del sistema, demostrando que una solicitud manual
síncrona impone precedencia total sobre las reglas autónomas asíncronas.

**Métricas:**
Tiempo medio de respuesta (REQ/REP) entre el usuario y la analítica.
Cambio forzado de la regla del semáforo a 45 segundos.

**Prueba de Enmascaramiento de Fallo por Caída (Fail-stop)**

**Descripción:** Mientras el sistema gestiona un volumen de tráfico alto entre las 3 máquinas físicas, se
forzará la desconexión total (cierre forzado) de la red del nodo PC3 (Base de datos Principal).

**Objetivo:** Validar la capacidad de tolerancia al fallo del servidor, probando que el patrón
ZMQ.NOBLOCK de la Analítica detecte la caída mediante Timeout y asigne la responsabilidad
absoluta de la persistencia a la Base de Datos Réplica (PC2).

**Métricas:**
Generación exitosa de Alerta de Consola informando la falla del nodo en PC3.


Tasa de Errores o Pérdida de Eventos (Drop Rate): Debe ser CERO en la BD Réplica tras revisar los
insertos en SQLite posteriores a la caída.

**7.2 Pruebas de Rendimiento (Performance)**

**Prueba de Rendimiento Base**

**Descripción:** Se ejecutará 1 sensor de cada tipo entregando eventos concurrentes hacia el Broker con
un tiempo de refresco estable de 10 segundos continuos.

**Objetivo:** Establecer una línea base sobre el comportamiento temporal de la red LAN y documentar
la capacidad media que emplea ZeroMQ sin saturación.

**Métricas:**
Throughput (TPS): Número de solicitudes procesadas y almacenadas exitosamente en la BD.
Tiempo de respuesta: Tiempo promedio de latencia al reaccionar.

**Prueba de Escalabilidad y Estrés Máximo**

**Descripción:** Se evaluará ecosistema aumentando agresivamente la carga de simulación: 2 sensores
por cada topología inyectando datos cada 5 segundos de forma simultánea, evaluando cómo se
degrada ante picos masivos.

**Objetivo:** Observar el declive de rendimiento de la aplicación y la latencia I/O al incrementar los
paquetes en ruta, evaluando posteriormente las gráficas frente a la reingeniería de un "Broker
Multihilo".

**Métricas:**
Tasa de variación del Throughput en ventanas de 2 minutos.
Saturación y Uso de la CPU / Memoria RAM general en eventos pico.

## 9. Métricas de Rendimiento

De acuerdo con lo estipulado normativamente en la rúbrica de evaluación (Tabla 1) y en concordancia
teórica con los Indicadores Clave de Rendimiento (KPI) en arquitecturas distribuidas, el sistema
obtendrá las métricas empíricas de la siguiente manera operativa:


**8.1 Rendimiento (Throughput)**

**Definición Teórica (KPI):** La cantidad de unidades de datos procesadas por el sistema durante un
período determinado.

**Métrica a evaluar (Tabla 1):** Cantidad de solicitudes almacenadas en la BD en un intervalo estricto
de tiempo de 2 minutos.

**Metodología Computacional:** Durante el flujo de la prueba de carga, el equipo disparará una
consulta temporal a las bases de datos (SQLite) realizando un conteo exacto de la tabla (COUNT(*))
cuando el cronómetro emulador inicie (T_inicio). Pasados los 2 minutos, se reiterará la consulta
(T_fin). La diferencia aritmética restada de los eventos ratificará el caudal sistémico exacto real y sin
manipulación.

**8.2 Tiempo de Latencia / Respuesta**

**Definición Teórica (KPI):** La duración de tiempo que transcurre entre una solicitud manual de un
evento crítico y la respuesta física del sistema.

**Métrica a evaluar (Tabla 1):** Tiempo desde que el usuario solicita una acción hasta que el semáforo
cambia efectivamente.

Metodología Computacional: Apoyados en la extracción horaria que brinda la librería java.time
(Timestamps en formato ISO-8601), el sistema sellará como T_0 el milisegundo preciso en el que un
operador emite la solicitud de priorización de vía desde el PC3. Paralelamente, una vez el mensaje
PUSH/PULL alcance el PC2 y se materialice en el driver del semáforo, será estampado como T_1.
La resta empírica (T_1 - T_0) generará el promedio de milisegundos perdidos en tráfico de red y
encolamiento.

**8.3 Escalabilidad y Usuarios Simultáneos**

**Definición Teórica (KPI):** La Escalabilidad valora qué tan bien responde la arquitectura a medida
que aumenta desproporcionadamente la carga, permitiendo admitir mayores Usuarios Simultáneos
inyectando ráfagas.

**Metodología Computacional:** Se establecerá un comparativo estructural. Las pruebas estipuladas en
la rúbrica (transitar de un ambiente pasivo de 1x10s hacia uno caótico de 2x5s) forzarán un pico
asimilable a múltiples terminales atacando el Broker de mensajería (usuarios inyectando datos). Las
métricas extraídas en esta escala probarán el éxito de escalar a un diseño Multihilo frente un diseño
secuencial en el nodo de ingesta.


**8.4 Utilización de Recursos y Memoria**

**Definición Teórica (KPI):** La porción de Memoria utilizada por un sistema mientras procesa la
colisión de datos, ligada a la Utilización de Recursos integrales subyacentes como E/S en disco, CPU
y red.

**Metodología Computacional:** De manera transversal, las métricas estarán respaldadas con
administradores visuales base de la máquina virtual (JConsole / VisualVM). Esto buscará discernir
analíticamente si una hipotética falla de Tiempo de Latencia reportada en el punto 8.2 es una
condición nativa de estrangulación en el Heap de Memoria de Java durante la serialización JSON, o
si la contención proviene de los constantes bloqueos sincrónicos de escritura inherentes del uso del
archivo sqlite.db frente a una inyección veloz de bytes por segundo (Ancho de Banda).


# 9. Conclusiones

- Desacoplamiento Efectivo mediante Patrones de Comunicación: La implementación del
    middleware ZeroMQ fundamentó una arquitectura altamente acoplada lógicamente, pero
    desacoplada temporal y espacialmente. El uso del patrón asíncrono Publish/Subscribe
    (PUB/SUB) en el nodo de ingesta (PC1) demostró ser vital para la red de sensores. En la
    evaluación inicial arquitectónica, comprobamos que este patrón previene el estrangulamiento
    de red (cuellos de botella por esperas), permitiendo que los medidores (espiras, cámaras,
    GPS) emitan sin bloquearse, delegando al broker la complejidad del enrutamiento hacia la
    analítica.
- Garantía de Persistencia e Interacciones Paralelas (PUSH/PULL): Al diseñar los modelos de
    interacción del sistema, se concluye que el patrón PUSH/PULL fue la decisión arquitectónica
    más eficiente para manejar tareas colaterales. Gracias a que el PC2 inyecta asíncronamente
    los comandos tanto a la clase actuadora (ControlSemaforos) como a las bases de datos (PC3
    y PC2) mediante este canal paralelo, la analítica jamás frena su hilo principal de validación
    lógica de tráfico. Esto maximiza el throughput del servidor, garantizando los tiempos de
    reacción exigidos para la priorización crítica de emergencias (Ola Verde).
- Resiliencia Basada en Enmascaramiento de Fallos: La concepción arquitectónica de un
    entorno distribuido exige anticipar de caídas de nodo, especialmente abordando modelos de
    Fallo-Parada (Fail-Stop) como los descritos para el PC3 principal. Se concluye que establecer
    una Base de Datos Réplica (PC2) sincronizada y con redirección automática sobre el mismo
    esquema de comunicación es financiera y computacionalmente más eficiente que
    sincronizaciones pesadas dependientes de reloj. Esta decisión previene que el módulo de
    monitoreo experimente latencia irrecuperable ante la ausencia del nodo matriz, demostrando
    la alta disponibilidad de nuestra plataforma urbana.

# 10. Bibliografía

[1] A. S.. Tanenbaum, M. Van. Steen, J. Octavio. García Pérez, and Rodolfo. Navarro Salas,
_Sistemas distribuidos : principios y paradigmas_. Pearson/Prentice-Hall, 2008.
[2] T.Bray, “rfc8259.txt,” no. The JavaScript Object Notation (JSON) Data Interchange Format,
Dec. 2017, doi: 10.17487/RFC8259.
[3] V. Paladino, “A brief introduction to ZeroMQ”, Intelligentproduct.solutions, 22-ene-2026..


[4] “ZeroMQ - synchronous message processing”, Tutorialspoint.com. [En línea]. Disponible
en: https://www.tutorialspoint.com/zeromq/zeromq-synchronous-message-processing.htm.
[Consultado: 05-abr-2026].

[5] H. Powell, “A quick and dirty introduction to ZeroMQ”, Scott Logic. [En línea]. Disponible
en: https://blog.scottlogic.com/2015/03/20/ZeroMQ-Quick-Intro.html. [Consultado: 05-abr-2026].

[6] “Get started”, ZeroMQ. [En línea]. Disponible en: https://zeromq.org/get-started/.
[Consultado: 05-abr-2026].

[7] A. Ashraf, “The Spectrum of Failure Models in distributed systems”, Medium, 30-oct-2024.
[En línea]. Disponible en: https://medium.com/@alameerashraf/the-spectrum-of-failure-models-in-
distributed-systems-1951bdb3ce72. [Consultado: 05-abr-2026].

[8] Universitat Politècnica de València-UPV, “Disponibilidad en sistemas distribuidos | | UPV”.
[En línea]. Disponible en: [http://youtube.com/watch?v=OizF_i-IwQQ&t=173.](http://youtube.com/watch?v=OizF_i-IwQQ&t=173.) [Consultado: 05-abr-
2026].

[9] “Threat modeling for distributed systems”, GeeksforGeeks, 29-ago-2024. [En línea].
Disponible en: https://www.geeksforgeeks.org/ethical-hacking/threat-modeling-for-distributed-
systems/. [Consultado: 05-abr-2026].

[10] “How to build a distributed system?”, GeeksforGeeks, 08-may-2024. [En línea]. Disponible
en: https://www.geeksforgeeks.org/system-design/how-to-build-a-distributed-system/. [Consultado:
05 - abr-2026].

[11] claytonsiemens, “Patrón Health Endpoint Monitoring (supervisión de puntos de conexión
de estado)”, Microsoft.com. [En línea]. Disponible en: https://learn.microsoft.com/es-
es/azure/architecture/patterns/health-endpoint-monitoring. [Consultado: 05-abr-2026].

[12] “23/ZMTP”, Zeromq.org. [En línea]. Disponible en: https://rfc.zeromq.org/spec/23/.
[Consultado: 06-abr-2026].

