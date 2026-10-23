package com.fernando.microservicios.servicio;

import com.fernando.microservicios.registro.InstanciaServicio;
import com.fernando.microservicios.registro.RegistroServicios;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Un microservicio: un HttpServer propio que se registra en el registro y
 * expone sus rutas. Cada instancia corre en su propio puerto.
 */
public class MicroServicio implements AutoCloseable {

    private final InstanciaServicio instancia;
    private final HttpServer servidor;
    private final ExecutorService pool;
    private final RegistroServicios registro;
    private final Map<String, Function<String, String>> rutas = new ConcurrentHashMap<>();
    private volatile boolean sano = true;

    public MicroServicio(String nombre, String id, RegistroServicios registro) throws IOException {
        this.registro = registro;
        this.servidor = HttpServer.create(new InetSocketAddress(0), 0);
        this.instancia = new InstanciaServicio(id, nombre, "127.0.0.1", servidor.getAddress().getPort());
        this.pool = Executors.newFixedThreadPool(4, tarea -> {
            Thread hilo = new Thread(tarea, "servicio-" + id);
            hilo.setDaemon(true);
            return hilo;
        });
        this.servidor.setExecutor(pool);
        this.servidor.createContext("/", this::atender);
    }

    public MicroServicio ruta(String camino, Function<String, String> manejador) {
        rutas.put(normalizar(camino), manejador);
        return this;
    }

    public void iniciar() {
        servidor.start();
        registro.registrar(instancia);
    }

    public InstanciaServicio getInstancia() {
        return instancia;
    }

    public int getPuerto() {
        return instancia.puerto();
    }

    /** Permite simular una caida sin apagar el proceso: responde 503. */
    public void marcarComoEnfermo() {
        this.sano = false;
    }

    public void marcarComoSano() {
        this.sano = true;
    }

    public boolean estaSano() {
        return sano;
    }

    private void atender(HttpExchange intercambio) throws IOException {
        String camino = normalizar(intercambio.getRequestURI().getPath());

        if (camino.equals("/salud")) {
            responder(intercambio, sano ? 200 : 503,
                    "{\"instancia\":\"" + instancia.id() + "\",\"sano\":" + sano + "}");
            return;
        }
        if (!sano) {
            responder(intercambio, 503, "{\"error\":\"servicio no disponible\"}");
            return;
        }

        Function<String, String> manejador = rutas.get(camino);
        if (manejador == null) {
            responder(intercambio, 404, "{\"error\":\"ruta no encontrada: " + camino + "\"}");
            return;
        }
        try {
            String cuerpo = new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            responder(intercambio, 200, manejador.apply(cuerpo));
        } catch (RuntimeException e) {
            responder(intercambio, 500, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private void responder(HttpExchange intercambio, int codigo, String cuerpo) throws IOException {
        byte[] datos = cuerpo.getBytes(StandardCharsets.UTF_8);
        intercambio.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        intercambio.sendResponseHeaders(codigo, datos.length);
        try (OutputStream salida = intercambio.getResponseBody()) {
            salida.write(datos);
        }
    }

    private static String normalizar(String camino) {
        if (camino == null || camino.isBlank()) {
            return "/";
        }
        String limpio = camino.trim();
        while (limpio.length() > 1 && limpio.endsWith("/")) {
            limpio = limpio.substring(0, limpio.length() - 1);
        }
        return limpio;
    }

    @Override
    public void close() {
        registro.darDeBaja(instancia.id());
        servidor.stop(0);
        pool.shutdownNow();
    }
}
