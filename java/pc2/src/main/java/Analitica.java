// analitica.java - servicio de analitica de trafico - pc2 (cerebro)
//
// este es el componente principal de procesamiento. hace lo siguiente:
//   1. se suscribe al broker y recibe los eventos de los sensores (sub)
//   2. evalua las reglas de trafico con las variables d, vp, q
//   3. envia los datos procesados a la bd principal (push) y a la replica (push)
//   4. envia comandos al control de semaforos (push)
//   5. atiende comandos del monitoreo como priorizacion de ambulancias (rep)
//
// si la bd principal (pc3) no responde, se activa el enmascaramiento de fallos
// y los datos solo se guardan en la bd replica (pc2).
//
// autores: miguel angel acuna, juan david acuna, y samuel felipe manrique - sistemas distribuidos 2026-10

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.concurrent.locks.ReentrantLock;

public class Analitica {

    // ============================================================
    // configuracion de red - cambiar segun las ips de cada pc
    // ============================================================
    static String BROKER_IP = "10.43.98.198";         // pc1 - donde esta el broker
    static String ANALITICA_IP = "10.43.98.199";      // pc2 - esta maquina
    static String BD_PRINCIPAL_IP = "10.43.99.183";   // pc3 - bd principal

    // guardo el estado de cada interseccion aqui
    // es un hashmap: {"INT-A1": {"Q": 0, "Vp": 50, "D": 0, "estado": "NORMAL"}, ...}
    static HashMap<String, HashMap<String, Object>> datosIntersecciones = new HashMap<>();

    // esta variable me dice si el pc3 esta funcionando o no
    static boolean pc3EstaVivo = true;

    // lock para que los hilos no se pisen al modificar datosIntersecciones
    static ReentrantLock lock = new ReentrantLock();

    static String timestampAhora() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
    }

    // evalua el estado del trafico usando las 3 variables:
    //   q  = longitud de cola (vehiculos en espera) - viene de la camara
    //   vp = velocidad promedio (km/h) - viene de camara y gps
    //   d  = densidad de trafico (veh/km) - viene del gps
    static String evaluarTrafico(int Q, double Vp, int D) {
        if (Q >= 10 || Vp <= 15 || D >= 40) {
            return "CONGESTION";
        }
        if (Q < 5 && Vp > 35 && D < 20) {
            return "NORMAL";
        }
        return "INTERMEDIO";
    }


    // crea un comando para enviar al servicio de semaforos.
    // devuelve null si el estado es INTERMEDIO.
    static JSONObject crearComandoSemaforo(String interseccion, String estado) {
        if (estado.equals("INTERMEDIO")) return null;

        JSONObject cmd = new JSONObject();
        cmd.put("interseccion", interseccion);
        cmd.put("timestamp", timestampAhora());

        if (estado.equals("CONGESTION")) {
            cmd.put("accion", "EXTENDER_VERDE");
            cmd.put("duracion_verde", 30);
            cmd.put("motivo", "Congestion detectada");
        } else if (estado.equals("NORMAL")) {
            cmd.put("accion", "CICLO_NORMAL");
            cmd.put("duracion_verde", 15);
            cmd.put("motivo", "Trafico normal");
        } else if (estado.equals("OLA_VERDE")) {
            cmd.put("accion", "OLA_VERDE");
            cmd.put("duracion_verde", 45);
            cmd.put("motivo", "Paso de emergencia");
        }
        
        return cmd;
    }

    // este hilo se suscribe al broker y recibe los eventos de los sensores.
    // por cada evento:
    //   1. lo deserializo (unmarshalling del json)
    //   2. actualizo las variables q, vp, d de la interseccion
    //   3. evaluo las reglas de trafico
    //   4. envio los datos a las dos bds
    //   5. si cambio el estado, envio comando al semaforo
    static void hiloRecibirSensores(ZContext contexto) {

        // me suscribo al broker para recibir los eventos
        ZMQ.Socket socketSub = contexto.createSocket(SocketType.SUB);
        socketSub.connect("tcp://" + BROKER_IP + ":5556");
        socketSub.subscribe("camara");
        socketSub.subscribe("espira");
        socketSub.subscribe("gps");

        // socket push para enviar datos a la bd principal (pc3)
        ZMQ.Socket pushPrincipal = contexto.createSocket(SocketType.PUSH);
        pushPrincipal.setSendTimeOut(3000);  // timeout explicito de envio
        pushPrincipal.setReceiveTimeOut(3000);
        pushPrincipal.setLinger(0);
        pushPrincipal.connect("tcp://" + BD_PRINCIPAL_IP + ":5570");

        // socket push para enviar datos a la bd replica (pc2)
        ZMQ.Socket pushReplica = contexto.createSocket(SocketType.PUSH);
        pushReplica.connect("tcp://" + ANALITICA_IP + ":5562");

        // socket push para enviar comandos al control de semaforos
        ZMQ.Socket pushSemaforos = contexto.createSocket(SocketType.PUSH);
        pushSemaforos.bind("tcp://" + ANALITICA_IP + ":5563");

        // uso un poller para no quedarme bloqueado esperando mensajes
        ZMQ.Poller poller = contexto.createPoller(1);
        poller.register(socketSub, ZMQ.Poller.POLLIN);

        System.out.println("[ANALITICA] Escuchando eventos de sensores...");
        System.out.println("[ANALITICA] Conectado al broker en tcp://" + BROKER_IP + ":5556");

        while (true) {
            // espero hasta 2 segundos a que llegue un mensaje
            poller.poll(2000);

            if (!poller.pollin(0)) {
                // no llego nada, sigo esperando
                continue;
            }

            // recibo el mensaje del broker
            String mensaje = socketSub.recvStr();
            if (mensaje == null) continue;

            // separo el topico del json: "camara {json}"
            String[] partes = mensaje.split(" ", 2);
            if (partes.length != 2) continue;

            String topico = partes[0];
            String jsonStr = partes[1];

            // unmarshalling: convierto el json de vuelta a objeto
            JSONObject evento;
            try {
                evento = new JSONObject(jsonStr);
            } catch (Exception e) {
                System.out.println("[ANALITICA] Error leyendo JSON, lo ignoro");
                continue;
            }

            String interseccion = evento.optString("interseccion", "DESCONOCIDA");

            // actualizo los datos de la interseccion
            int Q; double Vp; int D; String estadoNuevo; String estadoAnterior;

            lock.lock();
            try {
                if (!datosIntersecciones.containsKey(interseccion)) {
                    HashMap<String, Object> nuevo = new HashMap<>();
                    nuevo.put("Q", 0);
                    nuevo.put("Vp", 50.0);
                    nuevo.put("D", 0);
                    nuevo.put("estado", "NORMAL");
                    datosIntersecciones.put(interseccion, nuevo);
                }

                HashMap<String, Object> datos = datosIntersecciones.get(interseccion);

                // actualizo las variables segun el tipo de sensor
                if (topico.equals("camara")) {
                    datos.put("Q", evento.optInt("volumen", 0));
                    datos.put("Vp", evento.optDouble("velocidad_promedio", 50));
                } else if (topico.equals("gps")) {
                    datos.put("D", evento.optInt("densidad", 0));
                    double vpGps = evento.optDouble("velocidad_promedio", -1);
                    if (vpGps >= 0) {
                        datos.put("Vp", ((double) datos.get("Vp") + vpGps) / 2);
                    }
                }

                // evaluo las reglas de trafico
                Q = (int) datos.get("Q");
                Vp = (double) datos.get("Vp");
                D = (int) datos.get("D");
                estadoNuevo = evaluarTrafico(Q, Vp, D);
                estadoAnterior = (String) datos.get("estado");
                datos.put("estado", estadoNuevo);
            } finally {
                lock.unlock();
            }

            // imprimo el resultado por pantalla
            System.out.println("\n[ANALITICA] Sensor: " + topico + " | Interseccion: " + interseccion);
            System.out.printf("[ANALITICA] Q=%d | Vp=%.1f | D=%d%n", Q, Vp, D);
            System.out.println("[ANALITICA] Estado: " + estadoNuevo);

            if (!estadoNuevo.equals(estadoAnterior)) {
                System.out.println("[ANALITICA] ** CAMBIO: " + estadoAnterior + " -> " + estadoNuevo + " **");
            }

            // preparo el registro para guardar en la bd
            JSONObject registro = new JSONObject();
            registro.put("interseccion", interseccion);
            registro.put("tipo_sensor", topico);
            registro.put("datos_sensor", evento);
            registro.put("estado_trafico", estadoNuevo);
            registro.put("Q", Q);
            registro.put("Vp", Math.round(Vp * 10.0) / 10.0);
            registro.put("D", D);
            registro.put("timestamp_procesado", timestampAhora());

            // envio a la bd replica (siempre se envia)
            try {
                pushReplica.send(registro.toString());
                System.out.println("[ANALITICA] -> Enviado a BD replica (PC2)");
            } catch (Exception e) {
                System.out.println("[ANALITICA] Error enviando a replica: " + e.getMessage());
            }

            // envio a la bd principal (pc3) - aqui puede fallar
            boolean enviado = pushPrincipal.send(registro.toString(), ZMQ.NOBLOCK);
            if (enviado) {
                if (!pc3EstaVivo) {
                    System.out.println("[ANALITICA] PC3 se recupero!");
                    pc3EstaVivo = true;
                } else {
                    System.out.println("[ANALITICA] -> Enviado a BD principal (PC3)");
                }
            } else {
                // ================================================
                // enmascaramiento de fallos:
                // si no responde la bd principal, guardo en la replica
                // el sistema no se detiene, sigue funcionando
                // ================================================
                if (pc3EstaVivo) {
                    System.out.println("[ANALITICA] !!! FALLO: PC3 no responde !!!");
                    System.out.println("[ANALITICA] !!! ENMASCARAMIENTO DE FALLOS ACTIVADO !!!");
                    System.out.println("[ANALITICA] !!! Datos guardados SOLO en BD replica !!!");
                    pc3EstaVivo = false;
                } else {
                    System.out.println("[ANALITICA] PC3 sigue caido, datos en replica");
                }
                        // verifico si hubo un cambio de estado
                    if (!estadoNuevo.equals(estadoAnterior)) {
                        datosIntersecciones.get(interseccion).put("estado", estadoNuevo);

                        // envio un comando al equipo de semaforos local en pc2
                        JSONObject comando = crearComandoSemaforo(interseccion, estadoNuevo);
                        if (comando != null) {
                            try {
                                pushSemaforos.send(comando.toString());
                                System.out.println("[ANALITICA] -> Comando semaforo enviado: " + comando.getString("accion"));
                            } catch (Exception e) {
                                // no se pudo enviar, continuo
                            }
                        }
                    }
            }
        }
    }


    public static void main(String[] args) {
        System.out.println("============================================================");
        System.out.println("  SERVICIO DE ANALITICA - PC2 (Cerebro)");
        System.out.println("============================================================");
        System.out.println("  Broker:       tcp://" + BROKER_IP + ":5556");
        System.out.println("  BD Principal: tcp://" + BD_PRINCIPAL_IP + ":5570");
        System.out.println("  BD Replica:   tcp://" + ANALITICA_IP + ":5562");
        System.out.println("  Semaforos:    tcp://" + ANALITICA_IP + ":5563");
        System.out.println("============================================================");
        System.out.println("  Reglas de trafico:");
        System.out.println("    NORMAL:     Q < 5  AND Vp > 35 AND D < 20");
        System.out.println("============================================================");

        ZContext contexto = new ZContext();

        // inicio el hilo que recibe eventos de sensores
        Thread t1 = new Thread(() -> hiloRecibirSensores(contexto));
        t1.setDaemon(true);
        t1.start();

        System.out.println("\n[ANALITICA] Servicio corriendo. Ctrl+C para detener.\n");

        try {
            while (true) { Thread.sleep(1000); }
        } catch (InterruptedException e) {
            System.out.println("\n[ANALITICA] Cerrando...");
            System.out.println("[ANALITICA] Listo.");
        }
    }
}
