// bd_replica.java - base de datos replica - pc2
//
// este servicio hace dos cosas:
//   1. recibe datos procesados de la analitica (pull) y los guarda en sqlite
//   2. responde consultas del monitoreo (rep) cuando el pc3 esta caido
//
// la bd replica es identica a la bd principal. si el pc3 falla,
// el monitoreo se conecta aqui automaticamente (enmascaramiento de fallos).
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

    // ============================================================
    // configuracion de red
    // ============================================================
    static String REPLICA_IP = "10.43.98.199";   // pc2 - esta maquina
    static int PUERTO_PULL = 5562;               // puerto para recibir datos de analitica
    static int PUERTO_REP = 5564;                // puerto para consultas del monitoreo (failover)

    static String BD_ARCHIVO = "replica.db";     // nombre del archivo sqlite

    static String timestampAhora() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
    }

    // creo las tablas en sqlite si no existen.
    // es el mismo esquema que la bd principal para mantener consistencia.
    static void crearTablas() {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + BD_ARCHIVO)) {
            Statement stmt = conn.createStatement();

            // tabla para guardar los eventos de trafico procesados
            stmt.execute("CREATE TABLE IF NOT EXISTS eventos_trafico ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "interseccion TEXT, tipo_sensor TEXT, datos_sensor TEXT,"
                    + "estado_trafico TEXT, Q REAL, Vp REAL, D REAL,"
                    + "timestamp_procesado TEXT, timestamp_insercion TEXT)");

            // tabla para guardar el historial de estados de semaforos
            stmt.execute("CREATE TABLE IF NOT EXISTS estados_semaforo ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "interseccion TEXT, estado_anterior TEXT, estado_nuevo TEXT,"
                    + "duracion_verde INTEGER, motivo TEXT, timestamp TEXT)");

            // tabla para guardar acciones de control
            stmt.execute("CREATE TABLE IF NOT EXISTS acciones_control ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "interseccion TEXT, tipo_accion TEXT, detalles TEXT, timestamp TEXT)");

            System.out.println("[BD-REPLICA] Base de datos '" + BD_ARCHIVO + "' lista");
        } catch (SQLException e) {
            System.out.println("[BD-REPLICA] Error creando tablas: " + e.getMessage());
        }
    }

    // guarda un evento procesado en la tabla eventos_trafico.
    // uso sql crudo con preparedstatement directamente.
    static int guardarEvento(JSONObject registro) {
        int total = 0;
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + BD_ARCHIVO)) {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO eventos_trafico "
                    + "(interseccion, tipo_sensor, datos_sensor, estado_trafico, Q, Vp, D, "
                    + "timestamp_procesado, timestamp_insercion) VALUES (?,?,?,?,?,?,?,?,?)");

            ps.setString(1, registro.optString("interseccion", ""));
            ps.setString(2, registro.optString("tipo_sensor", ""));
            ps.setString(3, registro.optJSONObject("datos_sensor") != null
                    ? registro.getJSONObject("datos_sensor").toString() : "{}");
            ps.setString(4, registro.optString("estado_trafico", ""));
            ps.setDouble(5, registro.optDouble("Q", 0));
            ps.setDouble(6, registro.optDouble("Vp", 0));
            ps.setDouble(7, registro.optDouble("D", 0));
            ps.setString(8, registro.optString("timestamp_procesado", ""));
            ps.setString(9, timestampAhora());
            ps.executeUpdate();

            ResultSet rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM eventos_trafico");
            if (rs.next()) total = rs.getInt(1);
        } catch (SQLException e) {
            System.out.println("[BD-REPLICA] Error guardando: " + e.getMessage());
        }
        return total;
    }

    // hilo que recibe datos procesados de la analitica (pull).
    // cada dato que llega lo guardo en la bd sqlite.
    static void hiloRecibirDatos(ZContext contexto) {
        ZMQ.Socket socket = contexto.createSocket(SocketType.PULL);
        socket.bind("tcp://" + REPLICA_IP + ":" + PUERTO_PULL);

        // uso poller para no quedarme bloqueado
        ZMQ.Poller poller = contexto.createPoller(1);
        poller.register(socket, ZMQ.Poller.POLLIN);

        System.out.println("[BD-REPLICA] PULL esperando datos en tcp://" + REPLICA_IP + ":" + PUERTO_PULL);

        while (true) {
            poller.poll(2000);
            if (!poller.pollin(0)) continue;

            // recibo el registro (ya viene como json)
            String msg = socket.recvStr();
            if (msg == null) continue;
            JSONObject registro = new JSONObject(msg);

            // lo guardo en la bd
            int total = guardarEvento(registro);

            String inter = registro.optString("interseccion", "?");
            String estado = registro.optString("estado_trafico", "?");
            System.out.println("[BD-REPLICA] Guardado: " + inter + " | " + estado + " | Total: " + total);
        }
    }

    // hilo rep que responde consultas del monitoreo cuando el pc3 esta caido.
    // el monitoreo se conecta aqui automaticamente si el pc3 no responde.
    //
    // tipos de consulta:
    //   - consulta_historica: eventos entre dos fechas
    //   - consulta_interseccion: datos de una interseccion
    //   - consulta_estados: resumen de cuantos eventos por estado
    //   - consulta_throughput: total de registros en la bd
    static void hiloConsultas(ZContext contexto) {
        ZMQ.Socket socket = contexto.createSocket(SocketType.REP);
        socket.bind("tcp://" + REPLICA_IP + ":" + PUERTO_REP);

        ZMQ.Poller poller = contexto.createPoller(1);
        poller.register(socket, ZMQ.Poller.POLLIN);

        System.out.println("[BD-REPLICA] REP esperando consultas en tcp://" + REPLICA_IP + ":" + PUERTO_REP);

        while (true) {
            poller.poll(2000);
            if (!poller.pollin(0)) continue;

            String msg = socket.recvStr();
            if (msg == null) continue;
            JSONObject consulta = new JSONObject(msg);
            String tipo = consulta.optString("tipo", "");

            System.out.println("[BD-REPLICA] Consulta recibida: " + tipo);

            JSONObject respuesta = new JSONObject();

            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + BD_ARCHIVO)) {
                if (tipo.equals("CONSULTA_HISTORICA")) {
                    String inicio = consulta.optString("fecha_inicio", "");
                    String fin = consulta.optString("fecha_fin", "");
                    PreparedStatement ps = conn.prepareStatement(
                            "SELECT interseccion, tipo_sensor, estado_trafico, Q, Vp, D, timestamp_procesado "
                            + "FROM eventos_trafico WHERE timestamp_procesado BETWEEN ? AND ? "
                            + "ORDER BY timestamp_procesado DESC LIMIT 100");
                    ps.setString(1, inicio);
                    ps.setString(2, fin);
                    ResultSet rs = ps.executeQuery();
                    JSONArray resultados = new JSONArray();
                    while (rs.next()) {
                        JSONObject r = new JSONObject();
                        r.put("interseccion", rs.getString(1));
                        r.put("tipo_sensor", rs.getString(2));
                        r.put("estado_trafico", rs.getString(3));
                        r.put("Q", rs.getDouble(4));
                        r.put("Vp", rs.getDouble(5));
                        r.put("D", rs.getDouble(6));
                        r.put("timestamp", rs.getString(7));
                        resultados.put(r);
                    }
                    respuesta.put("status", "OK");
                    respuesta.put("fuente", "BD_REPLICA");
                    respuesta.put("total", resultados.length());
                    respuesta.put("resultados", resultados);

                } else if (tipo.equals("CONSULTA_INTERSECCION")) {
                    String inter = consulta.optString("interseccion", "");
                    PreparedStatement ps = conn.prepareStatement(
                            "SELECT tipo_sensor, estado_trafico, Q, Vp, D, timestamp_procesado "
                            + "FROM eventos_trafico WHERE interseccion = ? "
                            + "ORDER BY timestamp_procesado DESC LIMIT 10");
                    ps.setString(1, inter);
                    ResultSet rs = ps.executeQuery();
                    JSONArray resultados = new JSONArray();
                    while (rs.next()) {
                        JSONObject r = new JSONObject();
                        r.put("tipo_sensor", rs.getString(1));
                        r.put("estado_trafico", rs.getString(2));
                        r.put("Q", rs.getDouble(3));
                        r.put("Vp", rs.getDouble(4));
                        r.put("D", rs.getDouble(5));
                        r.put("timestamp", rs.getString(6));
                        resultados.put(r);
                    }
                    respuesta.put("status", "OK");
                    respuesta.put("fuente", "BD_REPLICA");
                    respuesta.put("interseccion", inter);
                    respuesta.put("total", resultados.length());
                    respuesta.put("resultados", resultados);

                } else if (tipo.equals("CONSULTA_ESTADOS")) {
                    ResultSet rs = conn.createStatement().executeQuery(
                            "SELECT estado_trafico, COUNT(*) FROM eventos_trafico "
                            + "GROUP BY estado_trafico ORDER BY COUNT(*) DESC");
                    JSONObject resumen = new JSONObject();
                    while (rs.next()) {
                        resumen.put(rs.getString(1), rs.getInt(2));
                    }
                    respuesta.put("status", "OK");
                    respuesta.put("fuente", "BD_REPLICA");
                    respuesta.put("resumen_estados", resumen);

                } else if (tipo.equals("CONSULTA_THROUGHPUT")) {
                    ResultSet rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM eventos_trafico");
                    int totalReg = rs.next() ? rs.getInt(1) : 0;
                    respuesta.put("status", "OK");
                    respuesta.put("fuente", "BD_REPLICA");
                    respuesta.put("total_registros", totalReg);

                } else {
                    respuesta.put("status", "ERROR");
                    respuesta.put("mensaje", "No entiendo: " + tipo);
                }
            } catch (SQLException e) {
                respuesta.put("status", "ERROR");
                respuesta.put("mensaje", e.getMessage());
            }

            socket.send(respuesta.toString());
            System.out.println("[BD-REPLICA] Respuesta enviada");
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

        // creo las tablas si no existen
        crearTablas();

        ZContext contexto = new ZContext();

        // hilo para recibir datos de la analitica
        Thread t1 = new Thread(() -> hiloRecibirDatos(contexto));
        t1.setDaemon(true);
        t1.start();

        // hilo para atender consultas del monitoreo (failover)
        Thread t2 = new Thread(() -> hiloConsultas(contexto));
        t2.setDaemon(true);
        t2.start();

        System.out.println("[BD-REPLICA] Servicio corriendo. Ctrl+C para detener.\n");

        try {
            while (true) { Thread.sleep(1000); }
        } catch (InterruptedException e) {
            System.out.println("\n[BD-REPLICA] Cerrando...");
            System.out.println("[BD-REPLICA] Listo.");
        }
    }
}
