package com.fernando.microservicios.gateway;

import com.fernando.microservicios.registro.BalanceadorRoundRobin;
import com.fernando.microservicios.registro.InstanciaServicio;
import com.fernando.microservicios.registro.RegistroServicios;
import com.fernando.microservicios.resiliencia.CircuitBreaker;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;

/**
 * Puerta de entrada unica. Traduce /servicio/{nombre}/{resto} a una llamada
 * a una instancia viva de ese servicio, eligiendola por round-robin,
 * reintentando en otra instancia si la primera falla, y protegiendo cada
 * servicio con su propio cortacircuitos.
 */
public class Gateway implements AutoCloseable {

    public static final int UMBRAL_DE_FALLOS = 3;
    public static final long ESPERA_REINTENTO_MS = 5_000;

    private final RegistroServicios registro;
    private final BalanceadorRoundRobin balanceador;
    private final HttpClient http;
    private final Map<String, CircuitBreaker> cortacircuitos = new ConcurrentHashMap<>();
    private final LongSupplier reloj;

    private HttpServer servidor;
    private ExecutorService pool;

    public Gateway(RegistroServicios registro) {
        this(registro, System::currentTimeMillis);
    }

    public Gateway(RegistroServicios registro, LongSupplier reloj) {
        this.registro = registro;
        this.reloj = reloj;
        this.balanceador = new BalanceadorRoundRobin(registro);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    public CircuitBreaker cortacircuitoDe(String servicio) {
        return cortacircuitos.computeIfAbsent(servicio.toLowerCase(),
                nombre -> new CircuitBreaker(nombre, UMBRAL_DE_FALLOS, ESPERA_REINTENTO_MS, reloj));
    }

    /**
     * Enruta una llamada. Prueba las instancias vivas en orden round-robin
     * hasta que una responda bien: eso es el failover.
     */
    public RespuestaGateway enrutar(String servicio, String camino, String metodo, String cuerpo) {
        CircuitBreaker breaker = cortacircuitoDe(servicio);
        if (!breaker.permitePasar()) {
            return new RespuestaGateway(503,
                    "{\"error\":\"circuito abierto para " + servicio + "\"}", null);
        }

        List<InstanciaServicio> candidatas = balanceador.ordenDeIntento(servicio);
        if (candidatas.isEmpty()) {
            breaker.registrarFallo();
            return new RespuestaGateway(503,
                    "{\"error\":\"no hay instancias vivas de " + servicio + "\"}", null);
        }

        String ultimoError = "";
        for (InstanciaServicio instancia : candidatas) {
            try {
                HttpResponse<String> respuesta = llamar(instancia, camino, metodo, cuerpo);
                if (respuesta.statusCode() < 500) {
                    breaker.registrarExito();
                    return new RespuestaGateway(respuesta.statusCode(), respuesta.body(), instancia.id());
                }
                ultimoError = "la instancia " + instancia.id() + " respondio " + respuesta.statusCode();
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                ultimoError = "no se pudo contactar a " + instancia.id() + ": " + e.getMessage();
            }
        }

        breaker.registrarFallo();
        return new RespuestaGateway(503,
                "{\"error\":\"todas las instancias de " + servicio + " fallaron: " + ultimoError + "\"}", null);
    }

    private HttpResponse<String> llamar(InstanciaServicio instancia, String camino, String metodo, String cuerpo)
            throws IOException, InterruptedException {
        HttpRequest.BodyPublisher publicador = (cuerpo == null || cuerpo.isEmpty())
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(cuerpo);

        HttpRequest peticion = HttpRequest.newBuilder(URI.create(instancia.urlBase() + camino))
                .timeout(Duration.ofSeconds(3))
                .method(metodo, publicador)
                .build();
        return http.send(peticion, HttpResponse.BodyHandlers.ofString());
    }

    /** Extrae el nombre del servicio y el camino restante de /servicio/{nombre}/{resto}. */
    static String[] partirRuta(String ruta) {
        String limpia = ruta == null ? "" : ruta.trim();
        if (!limpia.startsWith("/servicio/")) {
            return null;
        }
        String resto = limpia.substring("/servicio/".length());
        int barra = resto.indexOf('/');
        if (barra < 0) {
            return resto.isBlank() ? null : new String[]{resto, "/"};
        }
        String nombre = resto.substring(0, barra);
        String camino = resto.substring(barra);
        return nombre.isBlank() ? null : new String[]{nombre, camino};
    }

    public void iniciar(int puerto) throws IOException {
        servidor = HttpServer.create(new InetSocketAddress(puerto), 0);
        pool = Executors.newFixedThreadPool(8, tarea -> {
            Thread hilo = new Thread(tarea, "gateway");
            hilo.setDaemon(true);
            return hilo;
        });
        servidor.setExecutor(pool);
        servidor.createContext("/", this::atender);
        servidor.start();
    }

    public int getPuerto() {
        return servidor == null ? -1 : servidor.getAddress().getPort();
    }

    private void atender(HttpExchange intercambio) throws IOException {
        String ruta = intercambio.getRequestURI().getPath();

        if (ruta.equals("/gateway/servicios")) {
            responder(intercambio, 200, "{\"servicios\":" + comoJson(registro.serviciosRegistrados()) + "}");
            return;
        }

        String[] partes = partirRuta(ruta);
        if (partes == null) {
            responder(intercambio, 404,
                    "{\"error\":\"usa /servicio/{nombre}/{camino}\"}");
            return;
        }
        String cuerpo = new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        RespuestaGateway respuesta = enrutar(partes[0], partes[1], intercambio.getRequestMethod(), cuerpo);

        if (respuesta.instanciaAtendio() != null) {
            intercambio.getResponseHeaders().add("X-Instancia", respuesta.instanciaAtendio());
        }
        responder(intercambio, respuesta.codigo(), respuesta.cuerpo());
    }

    private static String comoJson(java.util.Collection<String> valores) {
        return "[" + valores.stream().map(v -> "\"" + v + "\"")
                .reduce((a, b) -> a + "," + b).orElse("") + "]";
    }

    private void responder(HttpExchange intercambio, int codigo, String cuerpo) throws IOException {
        byte[] datos = cuerpo.getBytes(StandardCharsets.UTF_8);
        intercambio.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        intercambio.sendResponseHeaders(codigo, datos.length);
        try (OutputStream salida = intercambio.getResponseBody()) {
            salida.write(datos);
        }
    }

    @Override
    public void close() {
        if (servidor != null) {
            servidor.stop(0);
        }
        if (pool != null) {
            pool.shutdownNow();
        }
    }
}
