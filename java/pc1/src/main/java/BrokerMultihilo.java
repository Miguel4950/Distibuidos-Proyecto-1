// broker_multihilo.java - broker zeromq para el pc1 (ingesta de datos)
//
// este script actua como intermediario entre los sensores y el
// servicio de analitica.
// usa xsub/xpub para reenviar los mensajes sin procesarlos.
//
// tiene un diseno multihilo:
//   - un hilo para el proxy (reenviar mensajes)
//   - un hilo para contar mensajes (metricas de rendimiento)
//
// autores: miguel angel acuna, juan david acuna, y samuel felipe manrique - sistemas distribuidos 2026-10

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.util.concurrent.locks.ReentrantLock;

public class BrokerMultihilo {

    // ============================================================
    // configuracion de red - cambiar segun la ip del pc1
    // ============================================================
    static String BROKER_IP = "10.43.98.198";
    static int PUERTO_XSUB = 5555;   // aqui se conectan los sensores (pub)
    static int PUERTO_XPUB = 5556;   // aqui se conecta la analitica (sub)

    // variable global para el contador
    static int contadorMensajes = 0;
    static ReentrantLock lockContador = new ReentrantLock();

    // este hilo simplemente imprime el valor del contador
    // global cada 30 segundos.
    static void hiloMetricas() {
        System.out.println("[METRICAS] Hilo de metricas iniciado");

        while (true) {
            try {
                Thread.sleep(30000);
            } catch (InterruptedException e) {
                break;
            }

            lockContador.lock();
            int total = contadorMensajes;
            contadorMensajes = 0;  // reseteo el contador
            lockContador.unlock();

            double tasa = total / 30.0;
            System.out.printf("[METRICAS] Mensajes: %d | Tasa: %.2f msg/s%n", total, tasa);
        }
    }

    // este hilo es el proxy principal del broker.
    // recibe mensajes de los sensores y los reenvia manualmente a
    // la analitica.
    // no usamos zmq.proxy() para poder contar los mensajes explicitamente.
    static void hiloProxy(ZContext contexto) {

        // socket xsub - aqui llegan los mensajes de los sensores
        ZMQ.Socket frontend = contexto.createSocket(SocketType.XSUB);
        frontend.bind("tcp://" + BROKER_IP + ":" + PUERTO_XSUB);
        System.out.println("[BROKER] XSUB esperando sensores en tcp://" + BROKER_IP + ":" + PUERTO_XSUB);

        // socket xpub - desde aqui salen los mensajes hacia la analitica
        ZMQ.Socket backend = contexto.createSocket(SocketType.XPUB);
        backend.bind("tcp://" + BROKER_IP + ":" + PUERTO_XPUB);
        System.out.println("[BROKER] XPUB esperando suscriptores en tcp://" + BROKER_IP + ":" + PUERTO_XPUB);

        System.out.println("[BROKER] Proxy iniciado manual, reenviando mensajes...");

        // uso un poller para manejar ambos sockets
        ZMQ.Poller poller = contexto.createPoller(2);
        poller.register(frontend, ZMQ.Poller.POLLIN);
        poller.register(backend, ZMQ.Poller.POLLIN);

        while (!Thread.currentThread().isInterrupted()) {
            try {
                poller.poll(1000);

                // mensajes del frontend (sensores -> broker)
                if (poller.pollin(0)) {
                    byte[] mensaje = frontend.recv(0);
                    if (mensaje != null) {
                        // sumo 1 a la variable global del contador
                        lockContador.lock();
                        contadorMensajes++;
                        lockContador.unlock();

                        // reenvio el mensaje
                        backend.send(mensaje, 0);
                    }
                }

                // suscripciones del backend (analitica -> sensores)
                if (poller.pollin(1)) {
                    byte[] suscripcion = backend.recv(0);
                    if (suscripcion != null) {
                        frontend.send(suscripcion, 0);
                    }
                }
            } catch (Exception e) {
                System.out.println("[BROKER] Proxy detenido");
                break;
            }
        }

        frontend.close();
        backend.close();
    }

    public static void main(String[] args) {
        System.out.println("============================================================");
        System.out.println("  BROKER MULTIHILO - PC1 (Ingesta de datos)");
        System.out.println("============================================================");
        System.out.println("  Sensores (XSUB) -> tcp://" + BROKER_IP + ":" + PUERTO_XSUB);
        System.out.println("  Analitica (XPUB) -> tcp://" + BROKER_IP + ":" + PUERTO_XPUB);
        System.out.println("============================================================");

        // creo el contexto de zeromq (compartido entre hilos)
        ZContext contexto = new ZContext();

        // inicio el hilo de metricas (daemon para que muera con el programa)
        Thread t1 = new Thread(BrokerMultihilo::hiloMetricas);
        t1.setDaemon(true);
        t1.start();

        // inicio el hilo del proxy
        Thread t2 = new Thread(() -> hiloProxy(contexto));
        t2.setDaemon(true);
        t2.start();

        System.out.println("[BROKER] Broker corriendo. Presione Ctrl+C para detener.\n");

        // mantengo el programa vivo hasta que el usuario lo detenga
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[BROKER] Cerrando broker...");
            contexto.close();
            System.out.println("[BROKER] Listo, broker cerrado.");
        }));

        try {
            while (true) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            // nada
        }
    }
}
