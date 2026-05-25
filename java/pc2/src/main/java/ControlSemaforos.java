// controlsemaforos.java - control de semaforos - pc2
//
// este servicio recibe comandos de la analitica pull y cambia el estado
// de los semaforos en cada interseccion
//
// los semaforos alternan entre verde y rojo:
//   - ciclo normal: 15 segundos carrera calle en rojo, 15 segundos calle carrera en rojo
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

public class ControlSemaforos {

    // configuracion de red
    // ============================================================
    static String ANALITICA_IP = "10.43.98.199";  // pc2

    // diccionario con el estado de cada semaforo por interseccion
    // {"int-a1": {"luz_carrera": "verde", "luz_calle": "rojo", "verde_carrera_seg": 15, "verde_calle_seg": 15, "ultimo_cambio": timestamp}}
    static HashMap<String, HashMap<String, Object>> semaforos = new HashMap<>();
    static ReentrantLock lock = new ReentrantLock();

    static String timestampAhora() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
    }

    // inicializa una interseccion si no existe en el mapa
    static void inicializarInterseccion(String interseccion) {
        if (!semaforos.containsKey(interseccion)) {
            HashMap<String, Object> nuevo = new HashMap<>();
            nuevo.put("luz_carrera", "VERDE");
            nuevo.put("luz_calle", "ROJO");
            nuevo.put("verde_carrera_seg", 15);
            nuevo.put("verde_calle_seg", 15);
            nuevo.put("ultimo_cambio", System.currentTimeMillis() / 1000.0);
            semaforos.put(interseccion, nuevo);
            System.out.println("[SEMAFORO] " + interseccion + " inicializado: CARRERA=VERDE, CALLE=ROJO");
        }
    }

    // este hilo cambia automaticamente los semaforos de verde a rojo
    // y viceversa cuando se cumple el tiempo configurado
    static void hiloCicloAutomatico() {
        while (true) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }  // reviso cada segundo

            lock.lock();
            try {
                for (String inter : semaforos.keySet()) {
                    HashMap<String, Object> datos = semaforos.get(inter);
                    double tiempoActual = System.currentTimeMillis() / 1000.0;
                    double tiempoTranscurrido = tiempoActual - (double) datos.get("ultimo_cambio");

                    String luzCarrera = (String) datos.get("luz_carrera");
                    int limiteCarrera = (int) datos.get("verde_carrera_seg");
                    int limiteCalle = (int) datos.get("verde_calle_seg");

                    if (luzCarrera.equals("VERDE") && tiempoTranscurrido >= limiteCarrera) {
                        // se acabo el verde de la carrera cambio a verde de calle
                        datos.put("luz_carrera", "ROJO");
                        datos.put("luz_calle", "VERDE");
                        datos.put("ultimo_cambio", tiempoActual);
                        System.out.println("[SEMAFORO] " + inter + " (Automatico): CARRERA (VERDE -> ROJO) | CALLE (ROJO -> VERDE) despues de " + limiteCarrera + "s");

                    } else if (luzCarrera.equals("ROJO") && tiempoTranscurrido >= limiteCalle) {
                        // se acabo el verde de la calle cambio a verde de carrera
                        datos.put("luz_carrera", "VERDE");
                        datos.put("luz_calle", "ROJO");
                        datos.put("ultimo_cambio", tiempoActual);
                        System.out.println("[SEMAFORO] " + inter + " (Automatico): CARRERA (ROJO -> VERDE) | CALLE (VERDE -> ROJO) despues de " + limiteCalle + "s");
                    }
                }
            } finally {
                lock.unlock();
            }
        }
    }

    // este hilo recibe comandos de la analitica usando pull
    static void hiloRecibirComandos(ZContext contexto) {
        // socket pull para recibir comandos
        ZMQ.Socket socket = contexto.createSocket(SocketType.PULL);
        socket.connect("tcp://" + ANALITICA_IP + ":5563");

        // uso un poller para no quedarme bloqueado
        ZMQ.Poller poller = contexto.createPoller(1);
        poller.register(socket, ZMQ.Poller.POLLIN);

        System.out.println("[SEMAFOROS] Esperando comandos en tcp://" + ANALITICA_IP + ":5563");

        while (true) {
            poller.poll(2000);

            if (!poller.pollin(0)) {
                continue;
            }

            // recibo el comando
            String msg = socket.recvStr();
            if (msg == null) continue;
            
            try {
                JSONObject comando = new JSONObject(msg);
                String interseccion = comando.optString("interseccion", "?");
                String accion = comando.optString("accion", "");
                int duracionVerde = comando.optInt("duracion_verde", 15);
                String motivo = comando.optString("motivo", "");

                System.out.println("\n[SEMAFOROS] Comando recibido: " + accion + " para " + interseccion + " (" + motivo + ")");

                lock.lock();
                try {
                    inicializarInterseccion(interseccion);
                    HashMap<String, Object> sem = semaforos.get(interseccion);
                    double tiempoActual = System.currentTimeMillis() / 1000.0;

                    switch (accion) {
                        case "CICLO_NORMAL":
                            sem.put("verde_carrera_seg", 15);
                            sem.put("verde_calle_seg", 15);
                            System.out.println("[SEMAFOROS] " + interseccion + ": Retorno a ciclo normal (15s/15s)");
                            break;

                        case "CONGESTION_CARRERA":
                            sem.put("verde_carrera_seg", duracionVerde);
                            sem.put("verde_calle_seg", 15); // calle normal
                            System.out.println("[SEMAFOROS] " + interseccion + ": Congestión detectada. Carrera Verde extendido a " + duracionVerde + "s");
                            break;

                        case "CONGESTION_CALLE":
                            sem.put("verde_carrera_seg", 15); // carrera normal
                            sem.put("verde_calle_seg", duracionVerde);
                            System.out.println("[SEMAFOROS] " + interseccion + ": Congestión detectada. Calle Verde extendido a " + duracionVerde + "s");
                            break;

                        case "EMERGENCIA_CARRERA":
                            // forzar carrera verde inmediatamente calle rojo
                            sem.put("luz_carrera", "VERDE");
                            sem.put("luz_calle", "ROJO");
                            sem.put("verde_carrera_seg", duracionVerde); // tiempo de la prioridad
                            sem.put("ultimo_cambio", tiempoActual);
                            System.out.println("[SEMAFOROS] " + interseccion + " !!! EMERGENCIA !!!: CARRERA forzado a VERDE por " + duracionVerde + "s");
                            break;

                        case "EMERGENCIA_CALLE":
                            // forzar calle verde inmediatamente carrera rojo
                            sem.put("luz_carrera", "ROJO");
                            sem.put("luz_calle", "VERDE");
                            sem.put("verde_calle_seg", duracionVerde); // tiempo de la prioridad
                            sem.put("ultimo_cambio", tiempoActual);
                            System.out.println("[SEMAFOROS] " + interseccion + " !!! EMERGENCIA !!!: CALLE forzado a VERDE por " + duracionVerde + "s");
                            break;

                        default:
                            System.out.println("[SEMAFOROS] Accion desconocida: " + accion);
                    }
                } finally {
                    lock.unlock();
                }
            } catch (Exception e) {
                System.out.println("[SEMAFOROS] Error al procesar comando: " + e.getMessage());
            }
        }
    }

    public static void main(String[] args) {
        System.out.println("============================================================");
        System.out.println("  CONTROL DE SEMAFOROS - PC2");
        System.out.println("============================================================");
        System.out.println("  PULL desde analitica: tcp://" + ANALITICA_IP + ":5563");
        System.out.println("  Sincronización: Doble semáforo alternado (CARRERA / CALLE)");
        System.out.println("============================================================");

        ZContext contexto = new ZContext();

        // hilo que recibe los comandos de la analitica
        Thread t1 = new Thread(() -> hiloRecibirComandos(contexto));
        t1.setDaemon(true);
        t1.start();

        // hilo que hace el ciclo automatico de los semaforos
        Thread t2 = new Thread(ControlSemaforos::hiloCicloAutomatico);
        t2.setDaemon(true);
        t2.start();

        System.out.println("[SEMAFOROS] Servicio corriendo. Ctrl+C para detener.\n");

        try {
            while (true) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            System.out.println("\n[SEMAFOROS] Cerrando...");
            System.out.println("[SEMAFOROS] Listo.");
        }
    }
}
