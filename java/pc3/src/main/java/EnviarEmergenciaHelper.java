// EnviarEmergenciaHelper.java - Helper para pruebas de latencia y seguridad
//
// autores: miguel angel acuna, juan david acuna, y samuel felipe manrique - sistemas distribuidos 2026-10

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.json.JSONObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public class EnviarEmergenciaHelper {

    static final String SECRETO = "clave_secreta_transito_2026";

    static String timestampAhora() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
    }

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

    public static void main(String[] args) {
        if (args.length < 5) {
            System.out.println("Uso: EnviarEmergenciaHelper <analitica_ip> <interseccion> <via> <accion> <duracion>");
            return;
        }

        String analiticaIp = args[0];
        String interseccion = args[1];
        String via = args[2];
        String accion = args[3];
        int duracion = Integer.parseInt(args[4]);

        ZContext contexto = new ZContext();
        ZMQ.Socket socket = contexto.createSocket(SocketType.REQ);
        socket.connect("tcp://" + analiticaIp + ":5566");
        socket.setSendTimeOut(3000);
        socket.setReceiveTimeOut(3000);

        String timestamp = timestampAhora();
        String datosFirma = interseccion + ":" + via + ":" + accion + ":" + timestamp;
        String firma = calcularHMAC(datosFirma, SECRETO);

        JSONObject req = new JSONObject();
        req.put("interseccion", interseccion);
        req.put("via", via);
        req.put("accion", accion);
        req.put("duracion_verde", duracion);
        req.put("timestamp", timestamp);
        req.put("firma", firma);

        long tInicio = System.nanoTime();
        boolean enviado = socket.send(req.toString());
        String respuesta = null;
        if (enviado) {
            respuesta = socket.recvStr();
        }
        long tFin = System.nanoTime();

        socket.close();
        contexto.close();

        if (respuesta != null) {
            double latenciaMs = (tFin - tInicio) / 1000000.0;
            System.out.printf("[LATENCIA-TEST] Latencia: %.3f ms | Respuesta: %s%n", latenciaMs, respuesta);
        } else {
            System.out.println("[LATENCIA-TEST] ERROR: Timeout al conectar con Analitica");
        }
    }
}
