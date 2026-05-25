// bdreplica.java - base de datos replica - pc2
//
// este servicio recibe datos procesados de la analitica pull y los guarda en sqlite
// expone un socket rep para consultas e intercambio de datos durante la resincronizacion
//
// autores: miguel angel acuna, juan david acuna, y samuel felipe manrique - sistemas distribuidos 2026-10

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.json.JSONObject;
import org.json.JSONArray;

import java.sql.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public class BdReplica {

    // configuracion de red
    // ============================================================
    static String REPLICA_IP = "10.43.98.199";   // pc2
    static int PUERTO_PULL = 5562;               // puerto para recibir datos de analitica
    static int PUERTO_REP = 5572;                // puerto para consultas de monitoreo y sync

    static String BD_ARCHIVO = "replica.db";     // nombre del archivo sqlite

    static String timestampAhora() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
    }

    // creacion de tablas
    static void crearTablas() {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + BD_ARCHIVO)) {
            Statement stmt = conn.createStatement();

            // tabla para guardar los eventos de trafico procesados
            stmt.execute("CREATE TABLE IF NOT EXISTS eventos_trafico ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "interseccion TEXT, tipo_sensor TEXT, datos_sensor TEXT,"
                    + "estado_trafico TEXT, Q REAL, Vp REAL, D REAL, via TEXT,"
                    + "timestamp_procesado TEXT, timestamp_insercion TEXT)");

            // tabla para acciones de control semaforos/emergencia
            stmt.execute("CREATE TABLE IF NOT EXISTS acciones_control ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "interseccion TEXT, via TEXT, tipo_accion TEXT, detalles TEXT, timestamp TEXT)");

            System.out.println("[BD-REPLICA] Base de datos '" + BD_ARCHIVO + "' lista");
        } catch (SQLException e) {
            System.out.println("[BD-REPLICA] Error creando tablas: " + e.getMessage());
        }
    }

    // guarda un evento procesado
    static int guardarEvento(JSONObject registro) {
        int total = 0;
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + BD_ARCHIVO)) {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO eventos_trafico "
                    + "(interseccion, tipo_sensor, datos_sensor, estado_trafico, Q, Vp, D, via, "
                    + "timestamp_procesado, timestamp_insercion) VALUES (?,?,?,?,?,?,?,?,?,?)");

            ps.setString(1, registro.optString("interseccion", ""));
            ps.setString(2, registro.optString("tipo_sensor", ""));
            ps.setString(3, registro.optJSONObject("datos_sensor") != null
                    ? registro.getJSONObject("datos_sensor").toString() : "{}");
            ps.setString(4, registro.optString("estado_trafico", ""));
            ps.setDouble(5, registro.optDouble("Q", 0));
            ps.setDouble(6, registro.optDouble("Vp", 0));
            ps.setDouble(7, registro.optDouble("D", 0));
            ps.setString(8, registro.optString("via", ""));
            ps.setString(9, registro.optString("timestamp_procesado", ""));
            ps.setString(10, timestampAhora());
            ps.executeUpdate();

            ResultSet rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM eventos_trafico");
            if (rs.next()) total = rs.getInt(1);
        } catch (SQLException e) {
            System.out.println("[BD-REPLICA] Error guardando: " + e.getMessage());
        }
        return total;
    }

    // guarda una accion de control emergencia/ola verde en la bd
    static void guardarAccionControl(JSONObject accion) {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + BD_ARCHIVO)) {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO acciones_control (interseccion, via, tipo_accion, detalles, timestamp) VALUES (?,?,?,?,?)");
            ps.setString(1, accion.optString("interseccion", ""));
            ps.setString(2, accion.optString("via", ""));
            ps.setString(3, accion.optString("tipo_accion", ""));
            ps.setString(4, accion.optString("detalles", ""));
            ps.setString(5, accion.optString("timestamp", timestampAhora()));
            ps.executeUpdate();
            System.out.println("[BD-REPLICA] Accion de control guardada");
        } catch (SQLException e) {
            System.out.println("[BD-REPLICA] Error guardando accion de control: " + e.getMessage());
        }
    }

    // hilo que recibe datos procesados de la analitica pull
    static void hiloRecibirDatos(ZContext contexto) {
        ZMQ.Socket socket = contexto.createSocket(SocketType.PULL);
        socket.bind("tcp://" + REPLICA_IP + ":" + PUERTO_PULL);

        ZMQ.Poller poller = contexto.createPoller(1);
        poller.register(socket, ZMQ.Poller.POLLIN);

        System.out.println("[BD-REPLICA] PULL esperando datos en tcp://" + REPLICA_IP + ":" + PUERTO_PULL);

        while (!Thread.currentThread().isInterrupted()) {
            poller.poll(2000);
            if (!poller.pollin(0)) continue;

            String msg = socket.recvStr();
            if (msg == null) continue;

            try {
                JSONObject registro = new JSONObject(msg);
                int total = guardarEvento(registro);
                String inter = registro.optString("interseccion", "?");
                String via = registro.optString("via", "?");
                String estado = registro.optString("estado_trafico", "?");
                System.out.println("[BD-REPLICA] Guardado PULL: " + inter + " (" + via + ") | " + estado + " | Total: " + total);
            } catch (Exception e) {
                System.out.println("[BD-REPLICA] Error procesando mensaje PULL: " + e.getMessage());
            }
        }
    }

    // hilo para responder consultas de sincronizacion y consultas del monitoreo si pc3 falla
    static void hiloResponderConsultas(ZContext contexto) {
        ZMQ.Socket socket = contexto.createSocket(SocketType.REP);
        socket.bind("tcp://" + REPLICA_IP + ":" + PUERTO_REP);

        System.out.println("[BD-REPLICA] REP esperando consultas/sincronización en tcp://" + REPLICA_IP + ":" + PUERTO_REP);

        while (!Thread.currentThread().isInterrupted()) {
            String msg = socket.recvStr();
            if (msg == null) continue;

            JSONObject respuesta = new JSONObject();
            try {
                JSONObject peticion = new JSONObject(msg);
                String accion = peticion.optString("accion", "");

                System.out.println("[BD-REPLICA] Peticion REP recibida: " + accion);

                if (accion.equals("OBTENER_DESDE")) {
                    String ultimoTimestamp = peticion.optString("ultimo_timestamp", "1970-01-01T00:00:00Z");
                    JSONArray eventos = new JSONArray();

                    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + BD_ARCHIVO)) {
                        PreparedStatement ps = conn.prepareStatement(
                                "SELECT interseccion, tipo_sensor, datos_sensor, estado_trafico, Q, Vp, D, via, timestamp_procesado "
                                + "FROM eventos_trafico WHERE timestamp_procesado > ? ORDER BY id ASC");
                        ps.setString(1, ultimoTimestamp);
                        ResultSet rs = ps.executeQuery();

                        while (rs.next()) {
                            JSONObject registro = new JSONObject();
                            registro.put("interseccion", rs.getString(1));
                            registro.put("tipo_sensor", rs.getString(2));
                            registro.put("datos_sensor", new JSONObject(rs.getString(3)));
                            registro.put("estado_trafico", rs.getString(4));
                            registro.put("Q", rs.getDouble(5));
                            registro.put("Vp", rs.getDouble(6));
                            registro.put("D", rs.getDouble(7));
                            registro.put("via", rs.getString(8));
                            registro.put("timestamp_procesado", rs.getString(9));
                            eventos.put(registro);
                        }
                    }
                    respuesta.put("resultado", "OK");
                    respuesta.put("eventos", eventos);
                    System.out.println("[BD-REPLICA] Registros de sincronización devueltos: " + eventos.length());

                } else if (accion.equals("GUARDAR_ACCION_CONTROL")) {
                    JSONObject control = peticion.getJSONObject("control");
                    guardarAccionControl(control);
                    respuesta.put("resultado", "OK");

                } else if (accion.equals("CONSULTA_HISTORICA")) {
                    String inicio = peticion.optString("inicio", "");
                    String fin = peticion.optString("fin", "");
                    JSONArray eventos = new JSONArray();

                    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + BD_ARCHIVO)) {
                        PreparedStatement ps = conn.prepareStatement(
                                "SELECT interseccion, via, tipo_sensor, estado_trafico, Q, Vp, D, timestamp_procesado "
                                + "FROM eventos_trafico WHERE timestamp_procesado BETWEEN ? AND ?");
                        ps.setString(1, inicio);
                        ps.setString(2, fin);
                        ResultSet rs = ps.executeQuery();

                        while (rs.next()) {
                            JSONObject ev = new JSONObject();
                            ev.put("interseccion", rs.getString(1));
                            ev.put("via", rs.getString(2));
                            ev.put("tipo_sensor", rs.getString(3));
                            ev.put("estado_trafico", rs.getString(4));
                            ev.put("Q", rs.getDouble(5));
                            ev.put("Vp", rs.getDouble(6));
                            ev.put("D", rs.getDouble(7));
                            ev.put("timestamp_procesado", rs.getString(8));
                            eventos.put(ev);
                        }
                    }
                    respuesta.put("resultado", "OK");
                    respuesta.put("eventos", eventos);
                    System.out.println("[BD-REPLICA] Consulta histórica devuelta: " + eventos.length() + " registros");

                } else if (accion.equals("CONSULTA_PUNTUAL")) {
                    String interseccion = peticion.optString("interseccion", "");
                    JSONArray eventos = new JSONArray();

                    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + BD_ARCHIVO)) {
                        PreparedStatement ps = conn.prepareStatement(
                                "SELECT interseccion, via, tipo_sensor, estado_trafico, Q, Vp, D, timestamp_procesado "
                                + "FROM eventos_trafico WHERE interseccion = ? ORDER BY id DESC LIMIT 5");
                        ps.setString(1, interseccion);
                        ResultSet rs = ps.executeQuery();

                        while (rs.next()) {
                            JSONObject ev = new JSONObject();
                            ev.put("interseccion", rs.getString(1));
                            ev.put("via", rs.getString(2));
                            ev.put("tipo_sensor", rs.getString(3));
                            ev.put("estado_trafico", rs.getString(4));
                            ev.put("Q", rs.getDouble(5));
                            ev.put("Vp", rs.getDouble(6));
                            ev.put("D", rs.getDouble(7));
                            ev.put("timestamp_procesado", rs.getString(8));
                            eventos.put(ev);
                        }
                    }
                    respuesta.put("resultado", "OK");
                    respuesta.put("eventos", eventos);
                    System.out.println("[BD-REPLICA] Consulta puntual devuelta: " + eventos.length() + " registros");

                } else {
                    respuesta.put("resultado", "ERROR");
                    respuesta.put("mensaje", "Accion no soportada");
                }
            } catch (Exception e) {
                respuesta.put("resultado", "ERROR");
                respuesta.put("mensaje", e.getMessage());
            }

            socket.send(respuesta.toString());
        }
    }

    public static void main(String[] args) {
        System.out.println("============================================================");
        System.out.println("  BASE DE DATOS REPLICA - PC2");
        System.out.println("============================================================");
        System.out.println("  PULL datos:    tcp://" + REPLICA_IP + ":" + PUERTO_PULL);
        System.out.println("  REP consultas: tcp://" + REPLICA_IP + ":" + PUERTO_REP);
        System.out.println("  Archivo BD:    " + BD_ARCHIVO);
        System.out.println("============================================================");

        crearTablas();

        ZContext contexto = new ZContext();

        // hilo pull para recibir de analitica
        Thread t1 = new Thread(() -> hiloRecibirDatos(contexto));
        t1.setDaemon(true);
        t1.start();

        // hilo rep para consultas de monitoreo y sync
        Thread t2 = new Thread(() -> hiloResponderConsultas(contexto));
        t2.setDaemon(true);
        t2.start();

        System.out.println("[BD-REPLICA] Servicio corriendo. Ctrl+C para detener.\n");

        try {
            while (true) { Thread.sleep(1000); }
        } catch (InterruptedException e) {
            System.out.println("\n[BD-REPLICA] Cerrando...");
            contexto.close();
            System.out.println("[BD-REPLICA] Listo.");
        }
    }
}
