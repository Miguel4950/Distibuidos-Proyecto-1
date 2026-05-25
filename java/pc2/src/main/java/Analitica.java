// analitica.java - servicio de analitica de trafico - pc2
//
// este es el componente principal de procesamiento hace lo siguiente:
//   1. se suscribe al broker y recibe los eventos de los sensores sub
//   2. evalua las reglas de trafico con las variables d, vp, q correlacion de eventos
//   3. envia los datos procesados a la bd principal push y a la replica push
//   4. envia comandos al control de semaforos push
//   5. atiende comandos del monitoreo como priorizacion de ambulancias rep con hmac-sha256
//   6. realiza la sincronizacion automatica de bds tras la recuperacion del pc3
//
// autores: miguel angel acuna, juan david acuna, y samuel felipe manrique - sistemas distribuidos 2026-10

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.json.JSONObject;
import org.json.JSONArray;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.concurrent.locks.ReentrantLock;

public class Analitica {

    // configuracion de red - cambiar segun las ips de cada pc
    // ============================================================
    static String BROKER_IP = "10.43.98.198";         // pc1
    static String ANALITICA_IP = "10.43.98.199";      // pc2
    static String BD_PRINCIPAL_IP = "10.43.99.183";   // pc3

    // clave secreta compartida para validar firmas hmac de comandos de monitoreo
    static final String SECRETO = "clave_secreta_transito_2026";

    // guardo el estado de cada interseccion y via aqui
    // clave: "int-c5_carrera" o "int-c5_calle"
    static HashMap<String, HashMap<String, Object>> datosIntersecciones = new HashMap<>();

    // esta variable me dice si el pc3 esta funcionando o no
    static volatile boolean pc3EstaVivo = true;

    // lock para que los hilos no se pisen al modificar datosIntersecciones
    static ReentrantLock lock = new ReentrantLock();

    static String timestampAhora() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
    }

    // calcular firma hmac-sha256 para validacion de mensajes
    static String calcularHMAC(String datos, String clave) {
        try {
            SecretKeySpec secretKey = new SecretKeySpec(clave.getBytes(), "HmacSHA256");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(secretKey);
            byte[] bytes = mac.doFinal(datos.getBytes());
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            return "";
        }
    }

    // correlacion de eventos
    // evalua el estado del trafico usando las 3 variables
    //   q  = longitud de cola
    //   vp = velocidad promedio
    //   d  = densidad de trafico
    static String evaluarTrafico(int Q, double Vp, int D) {
        // regla normal: q < 5 and vp > 35 and d < 20
        if (Q < 5 && Vp > 35 && D < 20) {
            return "NORMAL";
        }
        // regla congestion: q >= 12 or vp < 20 or d >= 30
        if (Q >= 12 || Vp < 20 || D >= 30) {
            return "CONGESTION";
        }
        // en cualquier otro caso
        return "INTERMEDIO";
    }

    // obtiene la estrategia de control combinando los estados de carrera y calle
    static String obtenerEstrategia(String estadoCarrera, String estadoCalle) {
        if (estadoCarrera.equals("CONGESTION") && !estadoCalle.equals("CONGESTION")) {
            return "CONGESTION_CARRERA";
        } else if (estadoCalle.equals("CONGESTION") && !estadoCarrera.equals("CONGESTION")) {
            return "CONGESTION_CALLE";
        }
        return "CICLO_NORMAL"; // en caso de equilibrio o que ambos esten en el mismo estado
    }

    static void inicializarVia(String claveVia) {
        if (!datosIntersecciones.containsKey(claveVia)) {
            HashMap<String, Object> nuevo = new HashMap<>();
            nuevo.put("Q", 0);
            nuevo.put("Vp", 50.0);
            nuevo.put("D", 0);
            nuevo.put("estado", "NORMAL");
            datosIntersecciones.put(claveVia, nuevo);
        }
    }

    // este hilo se suscribe al broker y recibe los eventos de los sensores
    static void hiloRecibirSensores(ZContext contexto) {

        ZMQ.Socket socketSub = contexto.createSocket(SocketType.SUB);
        socketSub.connect("tcp://" + BROKER_IP + ":5556");
        socketSub.subscribe("camara");
        socketSub.subscribe("espira");
        socketSub.subscribe("gps");

        // socket push para enviar datos a la bd principal (pc3)
        ZMQ.Socket pushPrincipal = contexto.createSocket(SocketType.PUSH);
        pushPrincipal.setSendTimeOut(2000);
        pushPrincipal.setLinger(0);
        pushPrincipal.connect("tcp://" + BD_PRINCIPAL_IP + ":5570");

        // socket push para enviar datos a la bd replica (pc2)
        ZMQ.Socket pushReplica = contexto.createSocket(SocketType.PUSH);
        pushReplica.connect("tcp://" + ANALITICA_IP + ":5562");

        // socket push para enviar comandos al control de semaforos
        ZMQ.Socket pushSemaforos = contexto.createSocket(SocketType.PUSH);
        pushSemaforos.bind("tcp://" + ANALITICA_IP + ":5563");

        ZMQ.Poller poller = contexto.createPoller(1);
        poller.register(socketSub, ZMQ.Poller.POLLIN);

        System.out.println("[ANALITICA] Escuchando eventos de sensores...");
        System.out.println("[ANALITICA] Conectado al broker en tcp://" + BROKER_IP + ":5556");

        while (!Thread.currentThread().isInterrupted()) {
            poller.poll(2000);

            if (!poller.pollin(0)) continue;

            String mensaje = socketSub.recvStr();
            if (mensaje == null) continue;

            String[] partes = mensaje.split(" ", 2);
            if (partes.length != 2) continue;

            String topico = partes[0];
            String jsonStr = partes[1];

            JSONObject evento;
            try {
                evento = new JSONObject(jsonStr);
            } catch (Exception e) {
                continue;
            }

            String interseccion = evento.optString("interseccion", "DESCONOCIDA");
            String via = evento.optString("via", "CARRERA"); // CARRERA o CALLE

            String claveEstaVia = interseccion + "_" + via;
            String claveOtraVia = interseccion + "_" + (via.equals("CARRERA") ? "CALLE" : "CARRERA");

            int Q; double Vp; int D;
            String estadoNuevoVia;
            String estrategiaNueva;
            String estrategiaAnterior;

            lock.lock();
            try {
                inicializarVia(claveEstaVia);
                inicializarVia(claveOtraVia);

                HashMap<String, Object> datosEstaVia = datosIntersecciones.get(claveEstaVia);
                HashMap<String, Object> datosOtraVia = datosIntersecciones.get(claveOtraVia);

                // obtener estrategia anterior antes de actualizar
                String estadoAntCarrera = via.equals("CARRERA") ? (String) datosEstaVia.get("estado") : (String) datosOtraVia.get("estado");
                String estadoAntCalle = via.equals("CALLE") ? (String) datosEstaVia.get("estado") : (String) datosOtraVia.get("estado");
                estrategiaAnterior = obtenerEstrategia(estadoAntCarrera, estadoAntCalle);

                // actualizo las variables segun el tipo de sensor
                if (topico.equals("camara")) {
                    datosEstaVia.put("Q", evento.optInt("volumen", 0));
                    datosEstaVia.put("Vp", evento.optDouble("velocidad_promedio", 50));
                } else if (topico.equals("gps")) {
                    datosEstaVia.put("D", evento.optInt("densidad", 0));
                    double vpGps = evento.optDouble("velocidad_promedio", -1);
                    if (vpGps >= 0) {
                        datosEstaVia.put("Vp", ((double) datosEstaVia.get("Vp") + vpGps) / 2.0);
                    }
                }

                // evaluo las reglas de trafico para ESTA via
                Q = (int) datosEstaVia.get("Q");
                Vp = (double) datosEstaVia.get("Vp");
                D = (int) datosEstaVia.get("D");
                estadoNuevoVia = evaluarTrafico(Q, Vp, D);
                datosEstaVia.put("estado", estadoNuevoVia);

                // obtener estrategia nueva
                String estadoNueCarrera = via.equals("CARRERA") ? estadoNuevoVia : (String) datosOtraVia.get("estado");
                String estadoNueCalle = via.equals("CALLE") ? estadoNuevoVia : (String) datosOtraVia.get("estado");
                estrategiaNueva = obtenerEstrategia(estadoNueCarrera, estadoNueCalle);

            } finally {
                lock.unlock();
            }

            // Impresión de logs de correlación
            System.out.printf("[ANALITICA] Evento %s en %s (%s) | Q=%d, Vp=%.1f, D=%d -> Estado Via: %s%n",
                    topico, interseccion, via, Q, Vp, D, estadoNuevoVia);

            // el envio de comandos ocurre siempre que cambie
            // la estrategia del semaforo independientemente del estado de la bd
            if (!estrategiaNueva.equals(estrategiaAnterior)) {
                System.out.println("[ANALITICA] ** ESTRATEGIA CAMBIO: " + estrategiaAnterior + " -> " + estrategiaNueva + " **");

                JSONObject comando = new JSONObject();
                comando.put("interseccion", interseccion);
                comando.put("accion", estrategiaNueva);
                comando.put("duracion_verde", estrategiaNueva.contains("CONGESTION") ? 30 : 15);
                comando.put("motivo", "Actualizacion por flujo de trafico (" + estrategiaNueva + ")");

                try {
                    pushSemaforos.send(comando.toString());
                    System.out.println("[ANALITICA] -> Comando semaforo enviado: " + estrategiaNueva);
                } catch (Exception e) {
                    System.out.println("[ANALITICA] Error enviando comando a semaforos: " + e.getMessage());
                }
            }

            // preparo el registro para guardar en las bases de datos
            JSONObject registro = new JSONObject();
            registro.put("interseccion", interseccion);
            registro.put("tipo_sensor", topico);
            registro.put("datos_sensor", evento);
            registro.put("estado_trafico", estadoNuevoVia);
            registro.put("Q", Q);
            registro.put("Vp", Math.round(Vp * 10.0) / 10.0);
            registro.put("D", D);
            registro.put("via", via);
            registro.put("timestamp_procesado", timestampAhora());

            // envio a la bd replica (siempre se envia)
            try {
                pushReplica.send(registro.toString());
            } catch (Exception e) {
                // ignorar
            }

            // enmascaramiento de fallos: solo intentamos push si pc3estavivo es true
            if (pc3EstaVivo) {
                boolean enviado = pushPrincipal.send(registro.toString(), ZMQ.NOBLOCK);
                if (!enviado) {
                    System.out.println("[ANALITICA] !!! Fallo al enviar PUSH a PC3 (Buffer lleno) !!!");
                }
            }
        }

        pushPrincipal.close();
        pushReplica.close();
        pushSemaforos.close();
        socketSub.close();
    }

    // hilo que expone el puerto de monitoreo rep y revisa la reconexion del pc3
    static void hiloMonitoreoYSincronizacion(ZContext contexto) {
        ZMQ.Socket socketRep = contexto.createSocket(SocketType.REP);
        socketRep.bind("tcp://" + ANALITICA_IP + ":5566");

        ZMQ.Poller poller = contexto.createPoller(1);
        poller.register(socketRep, ZMQ.Poller.POLLIN);

        long ultimoHeartbeat = System.currentTimeMillis();

        System.out.println("[ANALITICA] Atendiendo Monitoreo en tcp://" + ANALITICA_IP + ":5566");

        while (!Thread.currentThread().isInterrupted()) {
            poller.poll(500);

            if (poller.pollin(0)) {
                String msg = socketRep.recvStr();
                if (msg != null) {
                    JSONObject respuesta = new JSONObject();
                    try {
                        JSONObject peticion = new JSONObject(msg);
                        String interseccion = peticion.optString("interseccion", "");
                        String via = peticion.optString("via", "");
                        String accion = peticion.optString("accion", ""); // EMERGENCIA_CARRERA, EMERGENCIA_CALLE, CICLO_NORMAL
                        int duracionVerde = peticion.optInt("duracion_verde", 45);
                        String timestamp = peticion.optString("timestamp", "");
                        String firma = peticion.optString("firma", "");

                        // validacion criptografica hmac-sha256 mccumber integridad y autenticidad
                        String datosFirma = interseccion + ":" + via + ":" + accion + ":" + timestamp;
                        String firmaEsperada = calcularHMAC(datosFirma, SECRETO);

                        if (!firmaEsperada.equals(firma)) {
                            respuesta.put("resultado", "ERROR");
                            respuesta.put("mensaje", "[SEGURIDAD] Firma digital HMAC no valida. Rechazado.");
                            System.out.println("[ANALITICA-SEGURIDAD] Alerta: Firma no valida en comando de " + interseccion);
                        } else {
                            System.out.println("[ANALITICA-MONITOREO] Comando manual validado exitosamente para " + interseccion);

                            // enviar comando al controlador de semaforos mediante un socket push local
                            ZMQ.Socket pushSemaforoLocal = contexto.createSocket(SocketType.PUSH);
                            pushSemaforoLocal.connect("tcp://" + ANALITICA_IP + ":5563");

                            JSONObject cmdSemaforo = new JSONObject();
                            cmdSemaforo.put("interseccion", interseccion);
                            cmdSemaforo.put("accion", accion);
                            cmdSemaforo.put("duracion_verde", duracionVerde);
                            cmdSemaforo.put("motivo", "EMERGENCIA MANUAL POR OPERADOR");
                            
                            pushSemaforoLocal.send(cmdSemaforo.toString());
                            pushSemaforoLocal.close();

                            // registrar la accion de control en las bases de datos para persistencia e historico
                            JSONObject controlLog = new JSONObject();
                            controlLog.put("interseccion", interseccion);
                            controlLog.put("via", via);
                            controlLog.put("tipo_accion", accion);
                            controlLog.put("detalles", "Operador activo prioridad por " + duracionVerde + " segundos.");
                            controlLog.put("timestamp", timestampAhora());

                            // guardar en replica
                            try {
                                ZMQ.Socket pushReplicaLocal = contexto.createSocket(SocketType.PUSH);
                                pushReplicaLocal.connect("tcp://" + ANALITICA_IP + ":5562");
                                JSONObject msgReplica = new JSONObject();
                                msgReplica.put("accion", "GUARDAR_ACCION_CONTROL");
                                msgReplica.put("control", controlLog);
                                pushReplicaLocal.send(msgReplica.toString());
                                pushReplicaLocal.close();
                            } catch (Exception e) {
                                // ignorar
                            }

                            // guardar en bd principal si esta activa
                            if (pc3EstaVivo) {
                                try (ZMQ.Socket reqPrincipal = contexto.createSocket(SocketType.REQ)) {
                                    reqPrincipal.connect("tcp://" + BD_PRINCIPAL_IP + ":5571");
                                    reqPrincipal.setSendTimeOut(1500);
                                    reqPrincipal.setReceiveTimeOut(1500);

                                    JSONObject msgPrincipal = new JSONObject();
                                    msgPrincipal.put("accion", "GUARDAR_ACCION_CONTROL");
                                    msgPrincipal.put("control", controlLog);
                                    reqPrincipal.send(msgPrincipal.toString());
                                    reqPrincipal.recvStr();
                                } catch (Exception e) {
                                    System.out.println("[ANALITICA] Error guardando accion de control en PC3: " + e.getMessage());
                                }
                            }

                            respuesta.put("resultado", "OK");
                            respuesta.put("mensaje", "Accion ejecutada en semaforos de " + interseccion);
                        }
                    } catch (Exception e) {
                        respuesta.put("resultado", "ERROR");
                        respuesta.put("mensaje", e.getMessage());
                    }
                    socketRep.send(respuesta.toString());
                }
            }

            // heartbeat y sincronizacion: chequeo de salud cada 5 segundos
            long ahora = System.currentTimeMillis();
            if (ahora - ultimoHeartbeat >= 5000) {
                ultimoHeartbeat = ahora;

                // ping activo a la bd principal mediante req
                ZMQ.Socket reqPing = contexto.createSocket(SocketType.REQ);
                reqPing.connect("tcp://" + BD_PRINCIPAL_IP + ":5571");
                reqPing.setSendTimeOut(1500);
                reqPing.setReceiveTimeOut(1500);
                reqPing.setLinger(0);

                JSONObject ping = new JSONObject();
                ping.put("accion", "ULTIMO_REGISTRO");

                boolean enviado = reqPing.send(ping.toString());
                String respuestaPing = null;
                if (enviado) {
                    respuestaPing = reqPing.recvStr();
                }
                reqPing.close();

                if (respuestaPing != null) {
                    // si el pc3 responde y estaba marcado como muerto:
                    if (!pc3EstaVivo) {
                        System.out.println("\n[DETECCION-FALLAS] PC3 (BD Principal) ha RESUCITADO. Iniciando protocolo de sincronizacion...");

                        try {
                            JSONObject respJson = new JSONObject(respuestaPing);
                            String ultimoTimestampPrincipal = respJson.optString("ultimo_timestamp", "1970-01-01T00:00:00Z");

                            // 1. consultar a la replica pc2 todos los eventos posteriores a esa fecha
                            ZMQ.Socket reqReplicaSync = contexto.createSocket(SocketType.REQ);
                            reqReplicaSync.connect("tcp://" + ANALITICA_IP + ":5572");
                            reqReplicaSync.setSendTimeOut(3000);
                            reqReplicaSync.setReceiveTimeOut(3000);

                            JSONObject syncReq = new JSONObject();
                            syncReq.put("accion", "OBTENER_DESDE");
                            syncReq.put("ultimo_timestamp", ultimoTimestampPrincipal);

                            reqReplicaSync.send(syncReq.toString());
                            String respSyncStr = reqReplicaSync.recvStr();
                            reqReplicaSync.close();

                            if (respSyncStr != null) {
                                JSONObject syncRes = new JSONObject(respSyncStr);
                                JSONArray eventosFaltantes = syncRes.getJSONArray("eventos");

                                int cantidad = eventosFaltantes.length();
                                System.out.println("[SINCRONIZACION] Sincronizando " + cantidad + " registros acumulados en replica...");

                                if (cantidad > 0) {
                                    // 2. insertarlos en la bd principal pc3 uno a uno usando el socket req
                                    ZMQ.Socket reqPrincipalSync = contexto.createSocket(SocketType.REQ);
                                    reqPrincipalSync.connect("tcp://" + BD_PRINCIPAL_IP + ":5571");
                                    reqPrincipalSync.setSendTimeOut(2000);
                                    reqPrincipalSync.setReceiveTimeOut(2000);

                                    for (int i = 0; i < cantidad; i++) {
                                        JSONObject ev = eventosFaltantes.getJSONObject(i);
                                        JSONObject syncMsg = new JSONObject();
                                        syncMsg.put("accion", "INSERTAR_REGISTRO");
                                        syncMsg.put("registro", ev);

                                        reqPrincipalSync.send(syncMsg.toString());
                                        reqPrincipalSync.recvStr(); // esperar respuesta
                                    }
                                    reqPrincipalSync.close();
                                }
                                System.out.println("[SINCRONIZACION] Sincronización finalizada con éxito. PC3 al día.");
                            }
                        } catch (Exception e) {
                            System.out.println("[SINCRONIZACION] Error en sincronización: " + e.getMessage());
                        }
                        pc3EstaVivo = true;
                    }
                } else {
                    // si el pc3 no responde y estaba marcado como vivo:
                    if (pc3EstaVivo) {
                        System.out.println("\n[DETECCION-FALLAS] Fallo-Parada (Crash-stop) en PC3. Redireccionando ingesta temporalmente.");
                        pc3EstaVivo = false;
                    }
                }
            }
        }
        socketRep.close();
    }

    public static void main(String[] args) {
        System.out.println("============================================================");
        System.out.println("  SERVICIO DE ANALITICA - PC2 (Cerebro con Reglas Separadas)");
        System.out.println("============================================================");
        System.out.println("  Broker:       tcp://" + BROKER_IP + ":5556");
        System.out.println("  BD Principal: tcp://" + BD_PRINCIPAL_IP + ":5570");
        System.out.println("  BD Replica:   tcp://" + ANALITICA_IP + ":5562");
        System.out.println("  Semaforos:    tcp://" + ANALITICA_IP + ":5563");
        System.out.println("  Monitoreo:    tcp://" + ANALITICA_IP + ":5566");
        System.out.println("============================================================");

        ZContext contexto = new ZContext();

        // hilo que recibe los eventos
        Thread t1 = new Thread(() -> hiloRecibirSensores(contexto));
        t1.setDaemon(true);
        t1.start();

        // hilo para el monitoreo y sincronizacion
        Thread t2 = new Thread(() -> hiloMonitoreoYSincronizacion(contexto));
        t2.setDaemon(true);
        t2.start();

        System.out.println("[ANALITICA] Servicio iniciado. Ctrl+C para detener.\n");

        try {
            while (true) { Thread.sleep(1000); }
        } catch (InterruptedException e) {
            System.out.println("\n[ANALITICA] Cerrando...");
            contexto.close();
            System.out.println("[ANALITICA] Listo.");
        }
    }
}
