// broker_monohilo.java - broker mono-hilo para el pc1 ingesta de datos
//
// este componente realiza la misma tarea de reenvio que el broker multihilo
// pero lo ejecuta secuencialmente en un unico hilo
// se utiliza para medir el impacto de la concurrencia y la escalabilidad
//
// autores: miguel angel acuna, juan david acuna, y samuel felipe manrique - sistemas distribuidos 2026-10

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

public class BrokerMonohilo {

    // configuracion de red - debe coincidir con el pc1
    static String BROKER_IP = "10.43.98.198";
    static int PUERTO_SUB = 5555;   // aqui se conectan los sensores pub
    static int PUERTO_PUB = 5556;   // aqui se conecta la analitica sub

    public static void main(String[] args) {
        System.out.println("============================================================");
        System.out.println("  BROKER MONOHILO - PC1 (Ingesta de datos)");
        System.out.println("============================================================");
        System.out.println("  Sensores (SUB) -> tcp://" + BROKER_IP + ":" + PUERTO_SUB);
        System.out.println("  Analitica (PUB) -> tcp://" + BROKER_IP + ":" + PUERTO_PUB);
        System.out.println("============================================================");

        ZContext contexto = new ZContext();

        // socket sub - frontend de los sensores
        ZMQ.Socket frontend = contexto.createSocket(SocketType.SUB);
        frontend.subscribe("".getBytes(ZMQ.CHARSET)); // suscribirse a todo
        frontend.bind("tcp://" + BROKER_IP + ":" + PUERTO_SUB);
        System.out.println("[BROKER-MONO] SUB esperando sensores...");

        // socket pub - backend hacia la analitica
        ZMQ.Socket backend = contexto.createSocket(SocketType.PUB);
        backend.bind("tcp://" + BROKER_IP + ":" + PUERTO_PUB);
        System.out.println("[BROKER-MONO] PUB esperando analitica...");

        System.out.println("[BROKER-MONO] Broker corriendo en un solo hilo. Ctrl+C para detener.\n");

        int contadorMensajes = 0;
        long ultimoReporte = System.currentTimeMillis();

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // recibo y reenvio en el mismo hilo de forma secuencial y bloqueante
                byte[] mensaje = frontend.recv(0);
                if (mensaje != null) {
                    contadorMensajes++;
                    backend.send(mensaje, 0);
                }

                // impresion de metricas en el mismo hilo (añade sobrecarga síncrona)
                long ahora = System.currentTimeMillis();
                if (ahora - ultimoReporte >= 30000) {
                    double tasa = contadorMensajes / 30.0;
                    System.out.printf("[METRICAS-MONO] Mensajes: %d | Tasa: %.2f msg/s%n", contadorMensajes, tasa);
                    contadorMensajes = 0;
                    ultimoReporte = ahora;
                }
            } catch (Exception e) {
                System.out.println("[BROKER-MONO] Error: " + e.getMessage());
                break;
            }
        }

        frontend.close();
        backend.close();
        contexto.close();
        System.out.println("[BROKER-MONO] Listo, broker cerrado.");
    }
}
