// monitoreo.java - servicio de monitoreo y consulta - pc3
//
// este es el servicio que usa el usuario (operador) para interactuar
// con el sistema de trafico. tiene un menu por consola con estas opciones:
//
//   1. ver estado actual de una interseccion (pregunta a analitica)
//   2. consulta historica entre fechas (pregunta a bd principal)
//   3. ver resumen de estados de congestion (pregunta a bd)
//   4. forzar cambio de semaforo - ambulancia (envia a analitica)
//   5. ver throughput del sistema (pregunta a bd)
//   6. consultar datos de interseccion en bd
//
// tolerancia a fallos:
// si la bd principal (pc3) no responde, este servicio cambia
// automaticamente para consultar a la bd replica (pc2).
// el programa no se cierra, sigue funcionando normal.
//
// autores: miguel angel acuna, juan david acuna, y samuel felipe manrique - sistemas distribuidos 2026-10

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.json.JSONObject;
import org.json.JSONArray;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Scanner;

public class Monitoreo {

    // ============================================================
    // configuracion de red - cambiar segun las ips
    // ============================================================
    static String ANALITICA_IP = "10.43.98.199";      // pc2 - servicio de analitica
    static String BD_PRINCIPAL_IP = "10.43.99.183";   // pc3 - bd principal (esta maquina)
    static String BD_REPLICA_IP = "10.43.98.199";     // pc2 - bd replica (fallback)

    static int TIMEOUT = 5000;  // timeout en milisegundos (5 segundos)

    // esta variable indica si estamos usando la replica por fallo
    static boolean usandoReplica = false;

    // sockets y contexto como variables estaticas para poder modificarlos en funciones
    static ZContext contexto;
    static ZMQ.Socket socketAnalitica;
    static ZMQ.Socket socketBd;
    static String endpointBd;

    // crea un socket zmq simple con timeout configurado.
    static ZMQ.Socket crearSocket(SocketType tipo, String endpoint) {
        ZMQ.Socket socket = contexto.createSocket(tipo);
        socket.setReceiveTimeOut(TIMEOUT);
        socket.setSendTimeOut(TIMEOUT);
        socket.setLinger(0);
        socket.connect(endpoint);
        return socket;
    }

    // envia una consulta a la bd y maneja el failover automatico.
    //
    // si la bd principal no responde, cierro el socket viejo,
    // creo uno nuevo conectado a la bd replica y reintento.
    // el programa nunca se cierra por esto.
    static JSONObject consultarBd(JSONObject consulta) {
        try {
            // intento enviar y recibir la consulta
            socketBd.send(consulta.toString());
            String respStr = socketBd.recvStr();
            if (respStr != null) {
                return new JSONObject(respStr);
            }
            throw new Exception("timeout");
        } catch (Exception error) {
            // la bd no respondio a tiempo
            if (!usandoReplica) {
                // ====================================================
                // enmascaramiento de fallos transparente:
                // la bd principal no responde, me reconecto a la replica.
                // el usuario puede seguir usando el sistema normal.
                // ====================================================
                System.out.println("\n[MONITOREO] !!! BD Principal no responde: " + error.getMessage());
                System.out.println("[MONITOREO] !!! Cambiando a BD Replica (PC2)...");

                // cierro el socket viejo y creo uno nuevo a la replica
                try { socketBd.close(); } catch (Exception e) { /* nada */ }

                endpointBd = "tcp://" + BD_REPLICA_IP + ":5564";
                socketBd = crearSocket(SocketType.REQ, endpointBd);
                usandoReplica = true;

                // reintento la consulta en la replica
                try {
                    socketBd.send(consulta.toString());
                    String respStr = socketBd.recvStr();
                    if (respStr != null) {
                        System.out.println("[MONITOREO] Consulta exitosa en BD Replica");
                        return new JSONObject(respStr);
                    }
                    throw new Exception("timeout replica");
                } catch (Exception error2) {
                    System.out.println("[MONITOREO] BD Replica tampoco responde: " + error2.getMessage());
                    // recreo el socket para la proxima vez
                    try { socketBd.close(); } catch (Exception e) { /* nada */ }
                    socketBd = crearSocket(SocketType.REQ, endpointBd);
                    JSONObject err = new JSONObject();
                    err.put("status", "ERROR");
                    err.put("mensaje", "Ninguna BD disponible");
                    return err;
                }
            } else {
                // ya estamos en la replica y tampoco responde
                System.out.println("[MONITOREO] BD Replica no responde: " + error.getMessage());
                try { socketBd.close(); } catch (Exception e) { /* nada */ }
                socketBd = crearSocket(SocketType.REQ, "tcp://" + BD_REPLICA_IP + ":5564");
                JSONObject err = new JSONObject();
                err.put("status", "ERROR");
                err.put("mensaje", "BD Replica no disponible");
                return err;
            }
        }
    }

    // muestra el menu principal.
    static void mostrarMenu() {
        System.out.println("\n=======================================================");
        System.out.println("   MONITOREO Y CONSULTA - Sistema de Trafico");
        System.out.println("=======================================================");
        System.out.println("   1. Estado actual de una interseccion");
        System.out.println("   2. Consulta historica (rango de fechas)");
        System.out.println("   3. Resumen de estados de congestion");
        System.out.println("   4. Forzar semaforo (ambulancia)");
        System.out.println("   5. Ver throughput del sistema");
        System.out.println("   6. Consultar interseccion en BD");
        System.out.println("   0. Salir");
        System.out.println("=======================================================");
        if (usandoReplica) {
            System.out.println("   !! MODO FAILOVER: Usando BD Replica (PC2)");
        } else {
            System.out.println("   BD Principal (PC3) conectada");
        }
        System.out.println();
    }

    public static void main(String[] args) {
        System.out.println("=======================================================");
        System.out.println("  SERVICIO DE MONITOREO - PC3");
        System.out.println("=======================================================");
        System.out.println("  Analitica:    tcp://" + ANALITICA_IP + ":5560");
        System.out.println("  BD Principal: tcp://" + BD_PRINCIPAL_IP + ":5571");
        System.out.println("  BD Replica:   tcp://" + BD_REPLICA_IP + ":5564 (failover)");
        System.out.println("=======================================================");

        contexto = new ZContext();

        // socket req para hablar con la analitica (comandos directos)
        socketAnalitica = crearSocket(SocketType.REQ, "tcp://" + ANALITICA_IP + ":5560");
        System.out.println("[MONITOREO] Conectado a analitica");

        // socket req para consultas a la bd principal
        endpointBd = "tcp://" + BD_PRINCIPAL_IP + ":5571";
        socketBd = crearSocket(SocketType.REQ, endpointBd);
        System.out.println("[MONITOREO] Conectado a BD principal");

        System.out.println("[MONITOREO] Servicio listo.\n");

        Scanner scanner = new Scanner(System.in);

        while (true) {
            try {
                mostrarMenu();
                System.out.print("   Opcion: ");
                String opcion = scanner.nextLine().trim();

                // ============================================
                // opcion 1: estado actual (pregunta a analitica)
                // ============================================
                if (opcion.equals("1")) {
                    System.out.print("   Interseccion (ej. INT-C5): ");
                    String inter = scanner.nextLine().trim().toUpperCase();
                    if (inter.isEmpty()) { System.out.println("   Interseccion invalida"); continue; }

                    System.out.println("\n[MONITOREO] Consultando estado de " + inter + "...");
                    try {
                        JSONObject req = new JSONObject();
                        req.put("tipo", "ESTADO_ACTUAL");
                        req.put("interseccion", inter);
                        socketAnalitica.send(req.toString());
                        String respStr = socketAnalitica.recvStr();
                        if (respStr == null) throw new Exception("timeout");
                        JSONObject resp = new JSONObject(respStr);

                        if (resp.optString("status").equals("OK") && resp.has("Q")) {
                            System.out.println("\n   Interseccion: " + resp.optString("interseccion"));
                            System.out.println("   Q (Cola):       " + resp.opt("Q"));
                            System.out.println("   Vp (Velocidad): " + resp.opt("Vp") + " km/h");
                            System.out.println("   D (Densidad):   " + resp.opt("D") + " veh/km");
                            System.out.println("   Estado:         " + resp.optString("estado_trafico"));
                        } else {
                            System.out.println("   " + resp.optString("mensaje", "Sin datos"));
                        }
                    } catch (Exception e) {
                        System.out.println("   Error: analitica no responde (" + e.getMessage() + ")");
                        try { socketAnalitica.close(); } catch (Exception ex) { /* nada */ }
                        socketAnalitica = crearSocket(SocketType.REQ, "tcp://" + ANALITICA_IP + ":5560");
                    }

                // ============================================
                // opcion 2: consulta historica (pregunta a bd)
                // ============================================
                } else if (opcion.equals("2")) {
                    System.out.println("   Formato: YYYY-MM-DDTHH:MM:SSZ");
                    System.out.print("   Fecha inicio: ");
                    String inicio = scanner.nextLine().trim();
                    System.out.print("   Fecha fin:    ");
                    String fin = scanner.nextLine().trim();
                    if (inicio.isEmpty() || fin.isEmpty()) { System.out.println("   Fechas invalidas"); continue; }

                    JSONObject consulta = new JSONObject();
                    consulta.put("tipo", "CONSULTA_HISTORICA");
                    consulta.put("fecha_inicio", inicio);
                    consulta.put("fecha_fin", fin);

                    JSONObject resp = consultarBd(consulta);
                    System.out.println("\n   Fuente: " + resp.optString("fuente", "?"));
                    if (resp.optString("status").equals("OK")) {
                        int total = resp.optInt("total", 0);
                        System.out.println("   Registros: " + total);
                        JSONArray resultados = resp.optJSONArray("resultados");
                        if (resultados != null) {
                            int limit = Math.min(10, resultados.length());
                            for (int i = 0; i < limit; i++) {
                                JSONObject r = resultados.getJSONObject(i);
                                System.out.println("   | " + r.optString("timestamp") + " | " + r.optString("interseccion")
                                        + " | " + r.optString("estado_trafico") + " | Q=" + r.opt("Q")
                                        + " Vp=" + r.opt("Vp") + " D=" + r.opt("D"));
                            }
                            if (total > 10) System.out.println("   ... (" + total + " total, mostrando 10)");
                        }
                    } else {
                        System.out.println("   Error: " + resp.optString("mensaje"));
                    }

                // ============================================
                // opcion 3: resumen de estados (pregunta a bd)
                // ============================================
                } else if (opcion.equals("3")) {
                    JSONObject consulta = new JSONObject();
                    consulta.put("tipo", "CONSULTA_ESTADOS");
                    JSONObject resp = consultarBd(consulta);
                    System.out.println("\n   Fuente: " + resp.optString("fuente", "?"));
                    if (resp.optString("status").equals("OK")) {
                        JSONObject estados = resp.optJSONObject("resumen_estados");
                        if (estados != null) {
                            for (String key : estados.keySet()) {
                                int cantidad = estados.getInt(key);
                                String barra = "#".repeat(Math.min(cantidad, 50));
                                System.out.printf("   %-15s | %5d | %s%n", key, cantidad, barra);
                            }
                        }
                    } else {
                        System.out.println("   Error: " + resp.optString("mensaje"));
                    }

                // ============================================
                // opcion 4: priorizacion / ambulancia
                // ============================================
                } else if (opcion.equals("4")) {
                    System.out.print("   Interseccion (ej. INT-C5): ");
                    String inter = scanner.nextLine().trim().toUpperCase();
                    System.out.print("   Motivo (ej. Ambulancia): ");
                    String motivo = scanner.nextLine().trim();
                    if (inter.isEmpty()) { System.out.println("   Interseccion invalida"); continue; }
                    if (motivo.isEmpty()) motivo = "Priorizacion por operador";

                    // mido el tiempo para calcular la latencia
                    long tiempoInicio = System.currentTimeMillis();

                    System.out.println("\n[MONITOREO] Enviando priorizacion para " + inter + "...");
                    try {
                        JSONObject req = new JSONObject();
                        req.put("tipo", "PRIORIZACION");
                        req.put("interseccion", inter);
                        req.put("motivo", motivo);
                        req.put("timestamp_solicitud", timestampAhora());
                        socketAnalitica.send(req.toString());
                        String respStr = socketAnalitica.recvStr();
                        if (respStr == null) throw new Exception("timeout");
                        JSONObject resp = new JSONObject(respStr);

                        // calculo la latencia (tiempo de respuesta del semaforo)
                        double latenciaMs = System.currentTimeMillis() - tiempoInicio;

                        if (resp.optString("status").equals("OK")) {
                            System.out.println("   " + resp.optString("mensaje"));
                            JSONObject cmd = resp.optJSONObject("comando");
                            if (cmd != null) {
                                System.out.println("   Accion: " + cmd.optString("accion"));
                                System.out.println("   Verde: " + cmd.optInt("duracion_verde") + "s");
                            }
                            System.out.printf("   Latencia: %.1f ms%n", latenciaMs);
                        } else {
                            System.out.println("   Error: " + resp.optString("mensaje"));
                        }
                    } catch (Exception e) {
                        System.out.println("   Error: analitica no responde (" + e.getMessage() + ")");
                        try { socketAnalitica.close(); } catch (Exception ex) { /* nada */ }
                        socketAnalitica = crearSocket(SocketType.REQ, "tcp://" + ANALITICA_IP + ":5560");
                    }

                // ============================================
                // opcion 5: throughput (pregunta a bd)
                // ============================================
                } else if (opcion.equals("5")) {
                    JSONObject consulta = new JSONObject();
                    consulta.put("tipo", "CONSULTA_THROUGHPUT");
                    JSONObject resp = consultarBd(consulta);
                    System.out.println("\n   Fuente: " + resp.optString("fuente", "?"));
                    if (resp.optString("status").equals("OK")) {
                        System.out.println("   Total registros en BD: " + resp.optInt("total_registros"));
                    } else {
                        System.out.println("   Error: " + resp.optString("mensaje"));
                    }

                // ============================================
                // opcion 6: consultar interseccion en bd
                // ============================================
                } else if (opcion.equals("6")) {
                    System.out.print("   Interseccion (ej. INT-C5): ");
                    String inter = scanner.nextLine().trim().toUpperCase();
                    if (inter.isEmpty()) { System.out.println("   Interseccion invalida"); continue; }

                    JSONObject consulta = new JSONObject();
                    consulta.put("tipo", "CONSULTA_INTERSECCION");
                    consulta.put("interseccion", inter);
                    JSONObject resp = consultarBd(consulta);
                    System.out.println("\n   Fuente: " + resp.optString("fuente", "?"));
                    if (resp.optString("status").equals("OK")) {
                        int total = resp.optInt("total", 0);
                        System.out.println("   Registros: " + total);
                        JSONArray resultados = resp.optJSONArray("resultados");
                        if (resultados != null) {
                            for (int i = 0; i < resultados.length(); i++) {
                                JSONObject r = resultados.getJSONObject(i);
                                System.out.println("   | " + r.optString("timestamp") + " | " + r.optString("tipo_sensor")
                                        + " | " + r.optString("estado_trafico") + " | Q=" + r.opt("Q")
                                        + " Vp=" + r.opt("Vp") + " D=" + r.opt("D"));
                            }
                        }
                    } else {
                        System.out.println("   Error: " + resp.optString("mensaje"));
                    }

                // ============================================
                // opcion 0: salir
                // ============================================
                } else if (opcion.equals("0")) {
                    System.out.println("\n[MONITOREO] Cerrando...");
                    break;
                } else {
                    System.out.println("   Opcion invalida");
                }

            } catch (Exception e) {
                System.out.println("\n\n[MONITOREO] Cerrando...");
                break;
            }
        }

        // cierro todo
        socketAnalitica.close();
        socketBd.close();
        contexto.close();
        System.out.println("[MONITOREO] Listo.");
    }

    static String timestampAhora() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
    }
}
