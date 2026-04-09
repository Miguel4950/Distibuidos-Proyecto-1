// control_semaforos.java - control de semaforos - pc2
//
// este servicio recibe comandos de la analitica (pull) y cambia el estado
// de los semaforos en cada interseccion.
//
// los semaforos alternan entre verde y rojo:
//   - ciclo normal: 15 segundos verde, 15 segundos rojo
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

    // ============================================================
    // configuracion de red
    // ============================================================
    static String ANALITICA_IP = "10.43.98.199";  // pc2 - esta maquina

    // diccionario con el estado de cada semaforo
    // {"INT-A1": {"luz": "ROJO", "verde_seg": 15, "rojo_seg": 15, "ultimo_cambio": timestamp}}
    static HashMap<String, HashMap<String, Object>> semaforos = new HashMap<>();
    static ReentrantLock lock = new ReentrantLock();

    static String timestampAhora() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
    }

    // este hilo cambia automaticamente los semaforos de verde a rojo
    // y viceversa cuando se cumple el tiempo configurado.
    static void hiloCicloAutomatico() {
        while (true) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }  // reviso cada segundo

            lock.lock();
            try {
                for (String inter : semaforos.keySet()) {
                    HashMap<String, Object> datos = semaforos.get(inter);
                    double tiempo = System.currentTimeMillis() / 1000.0 - (double) datos.get("ultimo_cambio");

                    if (datos.get("luz").equals("VERDE") && tiempo >= (int) datos.get("verde_seg")) {
                        // se acabo el verde, cambio a rojo
                        datos.put("luz", "ROJO");
                        datos.put("ultimo_cambio", System.currentTimeMillis() / 1000.0);
                        System.out.println("[SEMAFORO] " + inter + ": VERDE -> ROJO (automatico, " + datos.get("verde_seg") + "s)");

                    } else if (datos.get("luz").equals("ROJO") && tiempo >= (int) datos.get("rojo_seg")) {
                        // se acabo el rojo, cambio a verde
                        datos.put("luz", "VERDE");
                        datos.put("ultimo_cambio", System.currentTimeMillis() / 1000.0);
                        System.out.println("[SEMAFORO] " + inter + ": ROJO -> VERDE (automatico, " + datos.get("rojo_seg") + "s)");
                    }
                }
            } finally {
                lock.unlock();
            }
        }
    }

    // este hilo recibe comandos de la analitica usando pull.
    // los comandos pueden ser:
    //   - ciclo_normal: volver al ciclo de 15s
    static void hiloRecibirComandos(ZContext contexto) {
        // socket pull para recibir comandos
        ZMQ.Socket socket = contexto.createSocket(SocketType.PULL);
        socket.connect("tcp://" + ANALITICA_IP + ":5563");

        // uso un poller para no quedarme bloqueado
        ZMQ.Poller poller = contexto.createPoller(1);
        poller.register(socket, ZMQ.Poller.POLLIN);

        System.out.println("[SEMAFOROS] Esperando comandos en tcp://" + ANALITICA_IP + ":5563");

        while (true) {
            // espero hasta 2 segundos
            poller.poll(2000);

            if (!poller.pollin(0)) {
                continue;
            }

            // recibo el comando
            String msg = socket.recvStr();
            if (msg == null) continue;
            JSONObject comando = new JSONObject(msg);

            String interseccion = comando.optString("interseccion", "?");
            String accion = comando.optString("accion", "");
            int verdeSeg = comando.optInt("duracion_verde", 15);
            String motivo = comando.optString("motivo", "");

            System.out.println("\n[SEMAFOROS] Comando recibido: " + accion + " para " + interseccion);

            lock.lock();
            try {
                // si es la primera vez que veo esta interseccion, la creo
                if (!semaforos.containsKey(interseccion)) {
                    HashMap<String, Object> nuevo = new HashMap<>();
                    nuevo.put("luz", "ROJO");
                    nuevo.put("verde_seg", 15);
                    nuevo.put("rojo_seg", 15);
                    nuevo.put("ultimo_cambio", System.currentTimeMillis() / 1000.0);
                    semaforos.put(interseccion, nuevo);
                }

                HashMap<String, Object> sem = semaforos.get(interseccion);

                if (accion.equals("CICLO_NORMAL")) {
                    // mantengo ciclo normal
                    sem.put("verde_seg", 15);
                    sem.put("rojo_seg", 15);
                } else {
                    System.out.println("[SEMAFOROS] Accion desconocida: " + accion);
                }
            } finally {
                lock.unlock();
            }
        }
    }

    public static void main(String[] args) {
        System.out.println("============================================================");
        System.out.println("  CONTROL DE SEMAFOROS - PC2");
        System.out.println("============================================================");
        System.out.println("  PULL desde analitica: tcp://" + ANALITICA_IP + ":5563");
        System.out.println("  Ciclos: Normal=15s");
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
