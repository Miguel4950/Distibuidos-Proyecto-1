// monitoreoconsulta.java - servicio de monitoreo y consulta - pc3
//
// permite a los operadores de transito hacer consultas y enviar comandos de prioridad
//
// patrones
//   - req/rep con analitica pc2 para cambiar semaforos con firmas hmac-sha256
//   - req/rep con bdprincipal pc3 para consultas con failover a bdreplica pc2
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
import java.util.Scanner;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public class MonitoreoConsulta {

    // configuracion de red - cambiar segun las ips
    // ============================================================
    static String ANALITICA_IP = "10.43.98.199";      // pc2
    static String BD_PRINCIPAL_IP = "10.43.99.183";   // pc3

    static final String SECRETO = "clave_secreta_transito_2026";

    static String timestampAhora() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
    }

    // calcular firma hmac-sha256
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

    // enviar una consulta a la bd principal con failover transparente a la replica
    static JSONObject realizarConsultaBD(ZContext contexto, JSONObject peticion) {
        JSONObject respuesta = null;

        // intentar conectar a bd principal pc3
        ZMQ.Socket reqBD = contexto.createSocket(SocketType.REQ);
        reqBD.connect("tcp://" + BD_PRINCIPAL_IP + ":5571");
        reqBD.setSendTimeOut(2000);
        reqBD.setReceiveTimeOut(2000);
        reqBD.setLinger(0);

        boolean enviado = reqBD.send(peticion.toString());
        String respStr = null;
        if (enviado) {
            respStr = reqBD.recvStr();
        }
        reqBD.close();

        if (respStr != null) {
            try {
                respuesta = new JSONObject(respStr);
                respuesta.put("origen", "BD_PRINCIPAL (PC3)");
            } catch (Exception e) {
                // ignorar error y forzar failover
            }
        }

        // failover transparente: si no responde, conectar a la replica pc2
        if (respuesta == null) {
            System.out.println("[MONITOREO-FALLOVER] PC3 no responde. Conectando con BD Replica en PC2 de forma transparente...");
            
            ZMQ.Socket reqReplica = contexto.createSocket(SocketType.REQ);
            reqReplica.connect("tcp://" + ANALITICA_IP + ":5572");
            reqReplica.setSendTimeOut(2000);
            reqReplica.setReceiveTimeOut(2000);
            reqReplica.setLinger(0);

            boolean enviadoReplica = reqReplica.send(peticion.toString());
            String respReplicaStr = null;
            if (enviadoReplica) {
                respReplicaStr = reqReplica.recvStr();
            }
            reqReplica.close();

            if (respReplicaStr != null) {
                try {
                    respuesta = new JSONObject(respReplicaStr);
                    respuesta.put("origen", "BD_REPLICA (PC2)");
                } catch (Exception e) {
                    System.out.println("[MONITOREO-ERROR] Tampoco se pudo conectar con la BD Replica.");
                }
            }
        }

        return respuesta;
    }

    // envia una peticion de prioridad emergencia a la analitica pc2
    static void enviarEmergencia(ZContext contexto, String interseccion, String via, String accion, int duracion) {
        ZMQ.Socket reqSemaforo = contexto.createSocket(SocketType.REQ);
        reqSemaforo.connect("tcp://" + ANALITICA_IP + ":5566");
        reqSemaforo.setSendTimeOut(3000);
        reqSemaforo.setReceiveTimeOut(3000);

        String timestamp = timestampAhora();

        // calcular firma digital hmac-sha256 cubo de mccumber autenticacion y no repudio
        String datosFirma = interseccion + ":" + via + ":" + accion + ":" + timestamp;
        String firma = calcularHMAC(datosFirma, SECRETO);

        JSONObject req = new JSONObject();
        req.put("interseccion", interseccion);
        req.put("via", via);
        req.put("accion", accion);
        req.put("duracion_verde", duracion);
        req.put("timestamp", timestamp);
        req.put("firma", firma);

        System.out.println("[MONITOREO] Enviando comando de prioridad a Analitica...");
        reqSemaforo.send(req.toString());

        String respStr = reqSemaforo.recvStr();
        reqSemaforo.close();

        if (respStr != null) {
            JSONObject resp = new JSONObject(respStr);
            String resultado = resp.optString("resultado", "ERROR");
            String mensaje = resp.optString("mensaje", "Sin respuesta detallada");
            if (resultado.equals("OK")) {
                System.out.println("[MONITOREO-EXITO] Comando aceptado: " + mensaje);
            } else {
                System.out.println("[MONITOREO-RECHAZADO] Comando denegado por analítica: " + mensaje);
            }
        } else {
            System.out.println("[MONITOREO-ERROR] No se pudo conectar con el servicio de analitica.");
        }
    }

    public static void main(String[] args) {
        ZContext contexto = new ZContext();
        Scanner scanner = new Scanner(System.in);

        System.out.println("============================================================");
        System.out.println("  SERVICIO DE MONITOREO Y CONSULTA - PC3");
        System.out.println("============================================================");

        boolean salir = false;
        while (!salir) {
            System.out.println("\n------------------------------------------------------------");
            System.out.println(" M E N U   D E   M O N I T O R E O");
            System.out.println("------------------------------------------------------------");
            System.out.println("1. Consultar estado en tiempo real (Puntual de intersección)");
            System.out.println("2. Consultar historial de tráfico (Periodos de tiempo)");
            System.out.println("3. Activar Ola Verde / Paso de Emergencia (Ambulancia)");
            System.out.println("4. Restaurar ciclo normal en semáforos");
            System.out.println("5. Salir del sistema");
            System.out.println("------------------------------------------------------------");
            System.out.print("Seleccione una opción: ");

            String opcion = scanner.nextLine().trim();

            switch (opcion) {
                case "1":
                    System.out.print("Ingrese el ID de la intersección (ej: INT-C5): ");
                    String interPuntual = scanner.nextLine().trim().toUpperCase();

                    JSONObject reqPuntual = new JSONObject();
                    reqPuntual.put("accion", "CONSULTA_PUNTUAL");
                    reqPuntual.put("interseccion", interPuntual);

                    JSONObject respPuntual = realizarConsultaBD(contexto, reqPuntual);
                    if (respPuntual != null && respPuntual.optString("resultado").equals("OK")) {
                        System.out.println("\n--- Datos más recientes (Origen: " + respPuntual.getString("origen") + ") ---");
                        JSONArray eventos = respPuntual.getJSONArray("eventos");
                        if (eventos.length() == 0) {
                            System.out.println("No se encontraron registros para esta intersección.");
                        } else {
                            System.out.printf("%-10s | %-12s | %-12s | %-3s | %-5s | %-3s | %-20s%n",
                                    "Vía", "Sensor", "Tránsito", "Q", "Vp", "D", "Timestamp");
                            System.out.println("-----------------------------------------------------------------------------------------");
                            for (int i = 0; i < eventos.length(); i++) {
                                JSONObject ev = eventos.getJSONObject(i);
                                System.out.printf("%-10s | %-12s | %-12s | %-3.1f | %-5.1f | %-3.1f | %-20s%n",
                                        ev.optString("via", "-"),
                                        ev.optString("tipo_sensor", "-"),
                                        ev.optString("estado_trafico", "-"),
                                        ev.optDouble("Q", 0),
                                        ev.optDouble("Vp", 0),
                                        ev.optDouble("D", 0),
                                        ev.optString("timestamp_procesado", "-"));
                            }
                        }
                    } else {
                        System.out.println("[MONITOREO] Error al realizar la consulta.");
                    }
                    break;

                case "2":
                    System.out.print("Ingrese fecha inicio (ISO 8601, ej: 2026-02-09T15:00:00Z): ");
                    String inicio = scanner.nextLine().trim();
                    System.out.print("Ingrese fecha fin (ISO 8601, ej: 2026-02-09T16:00:00Z): ");
                    String fin = scanner.nextLine().trim();

                    JSONObject reqHist = new JSONObject();
                    reqHist.put("accion", "CONSULTA_HISTORICA");
                    reqHist.put("inicio", inicio);
                    reqHist.put("fin", fin);

                    JSONObject respHist = realizarConsultaBD(contexto, reqHist);
                    if (respHist != null && respHist.optString("resultado").equals("OK")) {
                        System.out.println("\n--- Historial de Tráfico (Origen: " + respHist.getString("origen") + ") ---");
                        JSONArray eventos = respHist.getJSONArray("eventos");
                        if (eventos.length() == 0) {
                            System.out.println("No se encontraron registros en este periodo.");
                        } else {
                            System.out.printf("%-8s | %-10s | %-10s | %-12s | %-3s | %-5s | %-3s | %-20s%n",
                                    "Inter.", "Vía", "Sensor", "Tránsito", "Q", "Vp", "D", "Timestamp");
                            System.out.println("-------------------------------------------------------------------------------------------------");
                            for (int i = 0; i < eventos.length(); i++) {
                                JSONObject ev = eventos.getJSONObject(i);
                                System.out.printf("%-8s | %-10s | %-10s | %-12s | %-3.1f | %-5.1f | %-3.1f | %-20s%n",
                                        ev.optString("interseccion", "-"),
                                        ev.optString("via", "-"),
                                        ev.optString("tipo_sensor", "-"),
                                        ev.optString("estado_trafico", "-"),
                                        ev.optDouble("Q", 0),
                                        ev.optDouble("Vp", 0),
                                        ev.optDouble("D", 0),
                                        ev.optString("timestamp_procesado", "-"));
                            }
                        }
                    } else {
                        System.out.println("[MONITOREO] Error al realizar la consulta histórica.");
                    }
                    break;

                case "3":
                    System.out.print("Ingrese ID de la intersección (ej: INT-C5): ");
                    String interEmergencia = scanner.nextLine().trim().toUpperCase();
                    System.out.print("Ingrese la vía a priorizar (CARRERA o CALLE): ");
                    String viaEmergencia = scanner.nextLine().trim().toUpperCase();
                    System.out.print("Ingrese duración en segundos de luz verde de emergencia (ej: 45): ");
                    int duracion = Integer.parseInt(scanner.nextLine().trim());

                    String accion = viaEmergencia.equals("CARRERA") ? "EMERGENCIA_CARRERA" : "EMERGENCIA_CALLE";

                    enviarEmergencia(contexto, interEmergencia, viaEmergencia, accion, duracion);
                    break;

                case "4":
                    System.out.print("Ingrese ID de la intersección a restaurar (ej: INT-C5): ");
                    String interRestaurar = scanner.nextLine().trim().toUpperCase();

                    enviarEmergencia(contexto, interRestaurar, "AMBAS", "CICLO_NORMAL", 15);
                    break;

                case "5":
                    salir = true;
                    System.out.println("Saliendo del Monitoreo...");
                    break;

                default:
                    System.out.println("Opción no válida. Intente de nuevo.");
            }
        }

        scanner.close();
        contexto.close();
    }
}
