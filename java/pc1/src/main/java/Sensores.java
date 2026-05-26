// sensores.java - simulacion de sensores de trafico - pc1
//
// este script simula los 3 tipos de sensores de trafico que hay
// en la ciudad:
//   1. camaras de trafico -> miden longitud de cola q y velocidad vp
//   2. espiras inductivas -> cuentan vehiculos que pasan cv
//   3. sensores gps -> miden densidad d y velocidad promedio vp
//
// la ciudad es una cuadricula de 5x5 filas a-e columnas 1-5
// cada interseccion tiene sensores separados para la carrera y la calle
// cada sensor genera un evento json cada cierto tiempo y lo publica al broker
//
// soporta simulacion de fallos por linea de comandos:
//   -ruptura: desactiva el 20% de los sensores
//   -omision x: omite el x% de los envios de red
//   -temporizacion y: retarda el envio y milisegundos
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

    // configuracion - cambiar la ip del broker pc1
    static String BROKER_IP = "10.43.98.198";
    static int PUERTO_BROKER = 5555;  // puerto sub del broker

    static int INTERVALO = 10;  // segundos entre cada evento generado

    // intersecciones que vamos a usar para la demo
    static String[][] INTERSECCIONES = {
        {"A", "1"}, {"B", "3"}, {"C", "5"}, {"D", "2"}, {"E", "4"}
    };

    // parametros de simulacion de fallos
    static boolean simularRuptura = false;
    static int tasaOmision = 0;       // porcentaje de 0 a 100
    static int retardoMili = 0;       // milisegundos de retraso

    static Random random = new Random();

    // devuelve la fecha y hora actual en formato iso 8601
    static String timestampAhora() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
    }

    // genera un evento de camara de trafico
    static JSONObject generarEventoCamara(String fila, int col, String via) {
        JSONObject evento = new JSONObject();
        evento.put("sensor_id", "CAM-" + fila + col + "-" + via);
        evento.put("tipo_sensor", "camara");
        evento.put("interseccion", "INT-" + fila + col);
        evento.put("via", via);
        
        // simular trafico congestionado de vez en cuando el resto normal
        boolean esNormal = random.nextInt(100) < 75;
        int q;
        double vp;
        
        if (esNormal) {
            q = random.nextInt(5); // 0-4 vehiculos en cola
            vp = 36.0 + random.nextDouble() * 14.0; // 36-50 km/h
        } else {
            q = 12 + random.nextInt(15); // 12-26 vehiculos
            vp = 5.0 + random.nextDouble() * 15.0; // 5-20 km/h
        }
        
        evento.put("volumen", q);
        evento.put("velocidad_promedio", Math.round(vp * 10.0) / 10.0);
        evento.put("timestamp", timestampAhora());
        return evento;
    }

    // genera un evento de espira inductiva
    static JSONObject generarEventoEspira(String fila, int col, String via) {
        JSONObject evento = new JSONObject();
        evento.put("sensor_id", "ESP-" + fila + col + "-" + via);
        evento.put("tipo_sensor", "espira_inductiva");
        evento.put("interseccion", "INT-" + fila + col);
        evento.put("via", via);
        evento.put("vehiculos_contados", random.nextInt(41));
        evento.put("intervalo_segundos", 30);
        evento.put("timestamp_inicio", timestampAhora());
        evento.put("timestamp_fin", timestampAhora());
        return evento;
    }

    // genera un evento del sensor gps
    static JSONObject generarEventoGps(String fila, int col, String via) {
        boolean esNormal = random.nextInt(100) < 75;
        double velocidad;
        String nivel;
        int densidad;
        
        if (esNormal) {
            velocidad = 40.0 + random.nextDouble() * 20.0; // 40-60 km/h
            nivel = "BAJA";
            densidad = 1 + random.nextInt(18); // d < 20
        } else {
            velocidad = random.nextDouble() * 15.0; // <15 km/h
            nivel = "ALTA";
            densidad = 30 + random.nextInt(25); // d > 30
        }
        velocidad = Math.round(velocidad * 10.0) / 10.0;

        JSONObject evento = new JSONObject();
        evento.put("sensor_id", "GPS-" + fila + col + "-" + via);
        evento.put("tipo_sensor", "gps");
        evento.put("interseccion", "INT-" + fila + col);
        evento.put("via", via);
        evento.put("nivel_congestion", nivel);
        evento.put("velocidad_promedio", velocidad);
        evento.put("densidad", densidad);
        evento.put("timestamp", timestampAhora());
        return evento;
    }

    // funcion que ejecuta un sensor individual en un hilo
    static void ejecutarSensor(String tipo, String fila, int col, String via, ZContext contexto) {
        String nombre = tipo.substring(0, 3).toUpperCase() + "-" + fila + col + "-" + via;

        // simulacion de fallo por ruptura fisica apaga el 20% de los sensores si esta activo
        if (simularRuptura && random.nextInt(100) < 20) {
            System.out.println("[SIMULACION-FALLO] Sensor " + nombre + " sufrio una RUPTURA (desactivado)");
            return;
        }

        ZMQ.Socket socket = contexto.createSocket(SocketType.PUB);
        socket.connect("tcp://" + BROKER_IP + ":" + PUERTO_BROKER);

        System.out.println("[SENSOR] " + nombre + " conectado al broker");

        try { Thread.sleep(1000); } catch (InterruptedException e) { return; }  // espera inicial

        while (!Thread.currentThread().isInterrupted()) {
            JSONObject evento;
            String topico;
            if (tipo.equals("camara")) {
                evento = generarEventoCamara(fila, col, via);
                topico = "camara";
            } else if (tipo.equals("espira")) {
                evento = generarEventoEspira(fila, col, via);
                topico = "espira";
            } else {
                evento = generarEventoGps(fila, col, via);
                topico = "gps";
            }

            // marshalling json
            String mensajeJson = evento.toString();

            // simulacion de fallo por temporizacion latencia
            if (retardoMili > 0) {
                try { Thread.sleep(retardoMili); } catch (InterruptedException e) { break; }
            }

            // simulacion de fallo por omision perdida de red
            if (tasaOmision > 0 && random.nextInt(100) < tasaOmision) {
                System.out.println("[SIMULACION-FALLO] Sensor " + nombre + " -> mensaje OMITIDO (simulando perdida de canal)");
            } else {
                socket.send(topico + " " + mensajeJson);
                System.out.println("[SENSOR] " + nombre + " -> enviado | " + topico + " en " + via);
            }

            // intervalo de tiempo
            try { Thread.sleep(INTERVALO * 1000L); } catch (InterruptedException e) { break; }
        }
        socket.close();
    }

    public static void main(String[] args) {
        // parsear argumentos de simulacion de fallas y configuracion
        for (int i = 0; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("-ruptura")) {
                simularRuptura = true;
            } else if (args[i].equalsIgnoreCase("-omision") && i + 1 < args.length) {
                tasaOmision = Integer.parseInt(args[i + 1]);
                i++;
            } else if (args[i].equalsIgnoreCase("-temporizacion") && i + 1 < args.length) {
                retardoMili = Integer.parseInt(args[i + 1]);
                i++;
            } else if (args[i].equalsIgnoreCase("-intervalo") && i + 1 < args.length) {
                INTERVALO = Integer.parseInt(args[i + 1]);
                i++;
            }
        }

        System.out.println("============================================================");
        System.out.println("  SENSORES DE TRAFICO - PC1 (Carrera y Calle)");
        System.out.println("============================================================");
        System.out.println("  Broker:    tcp://" + BROKER_IP + ":" + PUERTO_BROKER);
        System.out.println("  Intervalo: " + INTERVALO + " segundos");
        System.out.println("  Simulaciones de Fallos:");
        System.out.println("    - Ruptura fisica:      " + (simularRuptura ? "ACTIVADO" : "DESACTIVADO"));
        System.out.println("    - Omision de envio:    " + (tasaOmision > 0 ? tasaOmision + "% de perdida" : "DESACTIVADO"));
        System.out.println("    - Temporizacion delay: " + (retardoMili > 0 ? retardoMili + "ms" : "DESACTIVADO"));
        System.out.println("============================================================");

        ZContext contexto = new ZContext();

        // lanzar hilos para carrera y calle en cada interseccion
        for (String[] inter : INTERSECCIONES) {
            String fila = inter[0];
            int col = Integer.parseInt(inter[1]);
            for (String via : new String[]{"CARRERA", "CALLE"}) {
                for (String tipo : new String[]{"camara", "espira", "gps"}) {
                    Thread t = new Thread(() -> ejecutarSensor(tipo, fila, col, via, contexto));
                    t.setDaemon(true);
                    t.start();
                }
            }
        }

        int total = INTERSECCIONES.length * 2 * 3;
        System.out.println("\n[SENSORES] " + total + " sensores simulados iniciados");
        System.out.println("[SENSORES] Presione Ctrl+C para detener\n");

        try {
            while (true) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            System.out.println("\n[SENSORES] Cerrando...");
            contexto.close();
            System.out.println("[SENSORES] Listo.");
        }
    }
}
