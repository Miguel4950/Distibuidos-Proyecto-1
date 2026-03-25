// bd_principal.java - base de datos principal - pc3 (persistencia)
//
// este servicio es la bd principal del sistema. hace dos cosas:
//   1. recibe datos procesados de la analitica (pull) y los guarda en sqlite
//   2. responde consultas del servicio de monitoreo (rep)
//
// si este servicio se cae, la analitica activa el enmascaramiento de fallos
// y el monitoreo se conecta automaticamente a la bd replica en pc2.
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

public class BdPrincipal {

    // ============================================================
    // configuracion de red
    // ============================================================
    static String BD_IP = "10.43.99.183";   // pc3 - esta maquina
    static int PUERTO_PULL = 5570;          // puerto para recibir datos de analitica
    static int PUERTO_REP = 5571;           // puerto para consultas del monitoreo

    static String BD_ARCHIVO = "trafico.db";   // nombre del archivo sqlite

    static String timestampAhora() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
    }

    // creo las tablas de la bd si no existen.
    // son las mismas tablas que en la bd replica.
    static void crearTablas() {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + BD_ARCHIVO)) {
            Statement stmt = conn.createStatement();

            // tabla principal de eventos de trafico
            stmt.execute("CREATE TABLE IF NOT EXISTS eventos_trafico ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "interseccion TEXT, tipo_sensor TEXT, datos_sensor TEXT,"
                    + "estado_trafico TEXT, Q REAL, Vp REAL, D REAL,"
                    + "timestamp_procesado TEXT, timestamp_insercion TEXT)");

            // tabla de historial de semaforos
            stmt.execute("CREATE TABLE IF NOT EXISTS estados_semaforo ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "interseccion TEXT, estado_anterior TEXT, estado_nuevo TEXT,"
                    + "duracion_verde INTEGER, motivo TEXT, timestamp TEXT)");

            // tabla de acciones de control
            stmt.execute("CREATE TABLE IF NOT EXISTS acciones_control ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "interseccion TEXT, tipo_accion TEXT, detalles TEXT, timestamp TEXT)");

            System.out.println("[BD-PRINCIPAL] Base de datos '" + BD_ARCHIVO + "' lista");
        } catch (SQLException e) {
            System.out.println("[BD-PRINCIPAL] Error creando tablas: " + e.getMessage());
        }
    }

    // guarda un evento procesado en la bd usando sql crudo.
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
            System.out.println("[BD-PRINCIPAL] Error guardando: " + e.getMessage());
        }
        return total;
    }

    // este hilo recibe datos procesados de la analitica (pull).
    // cada dato que llega lo guardo en la bd sqlite.
    static void hiloRecibirDatos(ZContext contexto) {
        ZMQ.Socket socket = contexto.createSocket(SocketType.PULL);
        socket.bind("tcp://" + BD_IP + ":" + PUERTO_PULL);

        ZMQ.Poller poller = contexto.createPoller(1);
        poller.register(socket, ZMQ.Poller.POLLIN);

        System.out.println("[BD-PRINCIPAL] PULL esperando datos en tcp://" + BD_IP + ":" + PUERTO_PULL);

        while (true) {
            // espero hasta 2 segundos
            poller.poll(2000);
            if (!poller.pollin(0)) continue;

            // recibo el registro como json
            String msg = socket.recvStr();
            if (msg == null) continue;
            JSONObject registro = new JSONObject(msg);

            // lo guardo en la bd
            int total = guardarEvento(registro);

            String inter = registro.optString("interseccion", "?");
            String estado = registro.optString("estado_trafico", "?");
            String tipo = registro.optString("tipo_sensor", "?");
            System.out.println("[BD-PRINCIPAL] Guardado: " + inter + " | " + tipo + " | " + estado + " | Total: " + total);
        }
    }

    // este hilo responde consultas del servicio de monitoreo (rep).
    // hace selects directos a la bd sqlite y devuelve los resultados.
    static void hiloConsultas(ZContext contexto) {
        ZMQ.Socket socket = contexto.createSocket(SocketType.REP);
        socket.bind("tcp://" + BD_IP + ":" + PUERTO_REP);

        ZMQ.Poller poller = contexto.createPoller(1);
        poller.register(socket, ZMQ.Poller.POLLIN);

        System.out.println("[BD-PRINCIPAL] REP esperando consultas en tcp://" + BD_IP + ":" + PUERTO_REP);

        while (true) {
            poller.poll(2000);
            if (!poller.pollin(0)) continue;

            // recibo la consulta del monitoreo
            String msg = socket.recvStr();
            if (msg == null) continue;
            JSONObject consulta = new JSONObject(msg);
            String tipo = consulta.optString("tipo", "");

            System.out.println("[BD-PRINCIPAL] Consulta: " + tipo);

            JSONObject respuesta = new JSONObject();

            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + BD_ARCHIVO)) {
                if (tipo.equals("CONSULTA_HISTORICA")) {
                    // busco eventos entre dos fechas (util para horas pico)
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
                    respuesta.put("fuente", "BD_PRINCIPAL");
                    respuesta.put("total", resultados.length());
                    respuesta.put("resultados", resultados);

                } else if (tipo.equals("CONSULTA_INTERSECCION")) {
                    // busco los ultimos datos de una interseccion especifica
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
                    respuesta.put("fuente", "BD_PRINCIPAL");
                    respuesta.put("interseccion", inter);
                    respuesta.put("total", resultados.length());
                    respuesta.put("resultados", resultados);

                } else if (tipo.equals("CONSULTA_ESTADOS")) {
                    // resumen: cuantos eventos hay por cada estado de trafico
                    ResultSet rs = conn.createStatement().executeQuery(
                            "SELECT estado_trafico, COUNT(*) FROM eventos_trafico "
                            + "GROUP BY estado_trafico ORDER BY COUNT(*) DESC");
                    JSONObject resumen = new JSONObject();
                    while (rs.next()) {
                        resumen.put(rs.getString(1), rs.getInt(2));
                    }
                    respuesta.put("status", "OK");
                    respuesta.put("fuente", "BD_PRINCIPAL");
                    respuesta.put("resumen_estados", resumen);

                } else if (tipo.equals("CONSULTA_THROUGHPUT")) {
                    // total de registros en la bd (para medir rendimiento)
                    ResultSet rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM eventos_trafico");
                    int totalReg = rs.next() ? rs.getInt(1) : 0;
                    respuesta.put("status", "OK");
                    respuesta.put("fuente", "BD_PRINCIPAL");
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
            System.out.println("[BD-PRINCIPAL] Respuesta enviada");
        }
    }

    public static void main(String[] args) {
        System.out.println("============================================================");
        System.out.println("  BASE DE DATOS PRINCIPAL - PC3 (Persistencia)");
        System.out.println("============================================================");
        System.out.println("  PULL datos:    tcp://" + BD_IP + ":" + PUERTO_PULL);
        System.out.println("  REP consultas: tcp://" + BD_IP + ":" + PUERTO_REP);
        System.out.println("  Archivo BD:    " + BD_ARCHIVO);
        System.out.println("============================================================");

        // creo las tablas si no existen
        crearTablas();

        ZContext contexto = new ZContext();

        // hilo para recibir datos de la analitica
        Thread t1 = new Thread(() -> hiloRecibirDatos(contexto));
        t1.setDaemon(true);
        t1.start();

        // hilo para atender consultas del monitoreo
        Thread t2 = new Thread(() -> hiloConsultas(contexto));
        t2.setDaemon(true);
        t2.start();

        System.out.println("[BD-PRINCIPAL] Servicio corriendo. Ctrl+C para detener.\n");

        try {
            while (true) { Thread.sleep(1000); }
        } catch (InterruptedException e) {
            System.out.println("\n[BD-PRINCIPAL] Cerrando...");
            System.out.println("[BD-PRINCIPAL] Listo.");
        }
    }
}
