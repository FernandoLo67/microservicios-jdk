package com.fernando.microservicios.registro;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Registro de servicios (lo que en Spring Cloud haria Eureka).
 *
 * Cada instancia se registra y debe mandar latidos. Si deja de latir mas de
 * lo permitido se la considera caida y deja de aparecer en las consultas.
 */
public class RegistroServicios {

    public static final long TIEMPO_DE_VIDA_MS = 10_000;

    private final Map<String, InstanciaServicio> instancias = new ConcurrentHashMap<>();
    private final Map<String, Long> ultimoLatido = new ConcurrentHashMap<>();
    private final LongSupplier reloj;

    public RegistroServicios() {
        this(System::currentTimeMillis);
    }

    /** Reloj inyectable: las pruebas simulan el paso del tiempo sin esperar. */
    public RegistroServicios(LongSupplier reloj) {
        this.reloj = Objects.requireNonNull(reloj);
    }

    public void registrar(InstanciaServicio instancia) {
        instancias.put(instancia.id(), instancia);
        ultimoLatido.put(instancia.id(), reloj.getAsLong());
    }

    public void latir(String instanciaId) {
        if (!instancias.containsKey(instanciaId)) {
            throw new NoSuchElementException("No hay ninguna instancia registrada con id " + instanciaId);
        }
        ultimoLatido.put(instanciaId, reloj.getAsLong());
    }

    public boolean darDeBaja(String instanciaId) {
        ultimoLatido.remove(instanciaId);
        return instancias.remove(instanciaId) != null;
    }

    /** Instancias vivas de un servicio, ordenadas por id para que el orden sea estable. */
    public List<InstanciaServicio> instanciasDe(String nombreServicio) {
        long ahora = reloj.getAsLong();
        return instancias.values().stream()
                .filter(i -> i.nombre().equalsIgnoreCase(nombreServicio))
                .filter(i -> estaViva(i.id(), ahora))
                .sorted(Comparator.comparing(InstanciaServicio::id))
                .collect(java.util.stream.Collectors.toUnmodifiableList());
    }

    public boolean estaViva(String instanciaId) {
        return estaViva(instanciaId, reloj.getAsLong());
    }

    private boolean estaViva(String instanciaId, long ahora) {
        Long latido = ultimoLatido.get(instanciaId);
        return latido != null && (ahora - latido) <= TIEMPO_DE_VIDA_MS;
    }

    /** Elimina del registro las instancias que dejaron de latir. Devuelve cuantas quito. */
    public int limpiarCaidas() {
        long ahora = reloj.getAsLong();
        List<String> caidas = instancias.keySet().stream()
                .filter(id -> !estaViva(id, ahora))
                .collect(java.util.stream.Collectors.toUnmodifiableList());
        caidas.forEach(this::darDeBaja);
        return caidas.size();
    }

    public Set<String> serviciosRegistrados() {
        long ahora = reloj.getAsLong();
        return instancias.values().stream()
                .filter(i -> estaViva(i.id(), ahora))
                .map(InstanciaServicio::nombre)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
    }

    public int totalInstancias() {
        return instancias.size();
    }
}
