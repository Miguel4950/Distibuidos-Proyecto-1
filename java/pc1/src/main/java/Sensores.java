// sensores.java - simulacion de sensores de trafico - pc1
//
// este script simula los 3 tipos de sensores de trafico que hay
// en la ciudad:
//   1. camaras de trafico -> miden longitud de cola (q) y velocidad (vp)
//   2. espiras inductivas -> cuentan vehiculos que pasan (cv)
//   3. sensores gps -> miden densidad (d) y velocidad promedio (vp)
//
// la ciudad es una cuadricula de 5x5 (filas a-e, columnas 1-5).
// cada sensor genera un evento json cada cierto tiempo y lo publica
// al broker usando pub/sub de zeromq.
//
// autores: miguel angel acuna, juan david acuna, y samuel felipe manrique - sistemas distribuidos 2026-10

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Random;

public class Sensores {

    // ============================================================
    // configuracion - cambiar la ip del broker (pc1)
    // ============================================================
    static String BROKER_IP = "10.43.98.198";
    static int PUERTO_BROKER = 5555;  // puerto xsub del broker

    static int INTERVALO = 10;  // segundos entre cada evento generado

    // cuadricula de la ciudad 5x5
    static String[] FILAS = {"A", "B", "C", "D", "E"};
    static int[] COLUMNAS = {1, 2, 3, 4, 5};

    // intersecciones que vamos a usar para la demo
    static String[][] INTERSECCIONES = {
        {"A", "1"}, {"B", "3"}, {"C", "5"}, {"D", "2"}, {"E", "4"}
    };

    static Random random = new Random();

    // devuelve la fecha y hora actual en formato iso 8601.
    static String timestampAhora() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
    }

    // genera un evento de camara de trafico.
    // mide la longitud de cola (q) y velocidad promedio (vp).
    static JSONObject generarEventoCamara(String fila, int col) {
        JSONObject evento = new JSONObject();
        evento.put("sensor_id", "CAM-" + fila + col);
        evento.put("tipo_sensor", "camara");
        evento.put("interseccion", "INT-" + fila + col);
        evento.put("volumen", random.nextInt(31));                    // q - vehiculos en cola
        evento.put("velocidad_promedio", Math.round(random.nextDouble() * 50 * 10.0) / 10.0);  // vp - km/h
        evento.put("timestamp", timestampAhora());
        return evento;
    }

    // genera un evento de espira inductiva.
    // cuenta cuantos vehiculos pasaron en un intervalo de
    // 30 segundos.
    static JSONObject generarEventoEspira(String fila, int col) {
        JSONObject evento = new JSONObject();
        evento.put("sensor_id", "ESP-" + fila + col);
        evento.put("tipo_sensor", "espira_inductiva");
        evento.put("interseccion", "INT-" + fila + col);
        evento.put("vehiculos_contados", random.nextInt(41));
        evento.put("intervalo_segundos", 30);
        evento.put("timestamp_inicio", timestampAhora());
        evento.put("timestamp_fin", timestampAhora());
        return evento;
    }

    // genera un evento del sensor gps.
    // mide velocidad promedio (vp) y densidad de trafico (d).
    // el nivel de congestion se determina por la velocidad.
    static JSONObject generarEventoGps(String fila, int col) {
        double velocidad = Math.round(random.nextDouble() * 60 * 10.0) / 10.0;

        // determino el nivel de congestion segun la velocidad
        String nivel;
        int densidad;
        if (velocidad < 10) {
            nivel = "ALTA";
            densidad = 40 + random.nextInt(41);
        } else if (velocidad <= 40) {
            nivel = "NORMAL";
            densidad = 15 + random.nextInt(31);
        } else {
            nivel = "BAJA";
            densidad = 1 + random.nextInt(20);
        }

        JSONObject evento = new JSONObject();
        evento.put("sensor_id", "GPS-" + fila + col);
        evento.put("tipo_sensor", "gps");
        evento.put("interseccion", "INT-" + fila + col);
        evento.put("nivel_congestion", nivel);
        evento.put("velocidad_promedio", velocidad);
        evento.put("densidad", densidad);
        evento.put("timestamp", timestampAhora());
        return evento;
    }

    // funcion que ejecuta un sensor individual en un hilo.
    // cada sensor genera eventos periodicamente y los publica al broker.
    //
    // el mensaje se envia como: "topico {json}"
    // donde topico es: camara, espira, o gps
    static void ejecutarSensor(String tipo, String fila, int col, ZContext contexto) {
        // creo el socket pub y me conecto al broker
        ZMQ.Socket socket = contexto.createSocket(SocketType.PUB);
        socket.connect("tcp://" + BROKER_IP + ":" + PUERTO_BROKER);

        String nombre = tipo.substring(0, 3).toUpperCase() + "-" + fila + col;
        System.out.println("[SENSOR] " + nombre + " conectado al broker");

        // espero un poco para que la conexion se estabilice
        try { Thread.sleep(1000); } catch (InterruptedException e) { return; }

        while (true) {
            // genero el evento segun el tipo de sensor
            JSONObject evento;
            String topico;
            if (tipo.equals("camara")) {
                evento = generarEventoCamara(fila, col);
                topico = "camara";
            } else if (tipo.equals("espira")) {
                evento = generarEventoEspira(fila, col);
                topico = "espira";
            } else {
                evento = generarEventoGps(fila, col);
                topico = "gps";
            }

            // marshalling: convierto el objeto a json (representacion externa)
            String mensajeJson = evento.toString();

            // envio el mensaje al broker: "topico json"
            socket.send(topico + " " + mensajeJson);

            System.out.println("[SENSOR] " + nombre + " -> " + topico + " | " + evento.getString("interseccion"));

            // espero el intervalo antes de generar otro evento
            try { Thread.sleep(INTERVALO * 1000L); } catch (InterruptedException e) { return; }
        }
    }

    public static void main(String[] args) {
        System.out.println("============================================================");
        System.out.println("  SENSORES DE TRAFICO - PC1");
        System.out.println("============================================================");
        System.out.println("  Broker: tcp://" + BROKER_IP + ":" + PUERTO_BROKER);
        System.out.println("  Intervalo: " + INTERVALO + " segundos");
        System.out.println("  Intersecciones: " + INTERSECCIONES.length);
        System.out.println("============================================================");

        ZContext contexto = new ZContext();

        // creo un hilo por cada sensor en cada interseccion
        for (String[] inter : INTERSECCIONES) {
            String fila = inter[0];
            int col = Integer.parseInt(inter[1]);
            for (String tipo : new String[]{"camara", "espira", "gps"}) {
                Thread t = new Thread(() -> ejecutarSensor(tipo, fila, col, contexto));
                t.setDaemon(true);
                t.start();
            }
        }

        int total = INTERSECCIONES.length * 3;
        System.out.println("\n[SENSORES] " + total + " sensores iniciados");
        System.out.println("[SENSORES] Presione Ctrl+C para detener\n");

        try {
            while (true) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            System.out.println("\n[SENSORES] Cerrando sensores...");
            System.out.println("[SENSORES] Listo.");
        }
    }
}
