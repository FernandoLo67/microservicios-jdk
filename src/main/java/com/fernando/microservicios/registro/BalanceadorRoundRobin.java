package com.fernando.microservicios.registro;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Balanceo round-robin por servicio: reparte las llamadas entre las instancias
 * vivas, una tras otra, volviendo al principio al llegar al final.
 */
public class BalanceadorRoundRobin {

    private final RegistroServicios registro;
    private final Map<String, AtomicInteger> contadores = new ConcurrentHashMap<>();

    public BalanceadorRoundRobin(RegistroServicios registro) {
        this.registro = registro;
    }

    public Optional<InstanciaServicio> siguiente(String nombreServicio) {
        List<InstanciaServicio> vivas = registro.instanciasDe(nombreServicio);
        if (vivas.isEmpty()) {
            return Optional.empty();
        }
        AtomicInteger contador = contadores.computeIfAbsent(
                nombreServicio.toLowerCase(), clave -> new AtomicInteger());
        // Math.floorMod evita el indice negativo cuando el contador desborda.
        int indice = Math.floorMod(contador.getAndIncrement(), vivas.size());
        return Optional.of(vivas.get(indice));
    }

    /** Devuelve todas las instancias vivas empezando por la siguiente del turno. */
    public List<InstanciaServicio> ordenDeIntento(String nombreServicio) {
        List<InstanciaServicio> vivas = registro.instanciasDe(nombreServicio);
        if (vivas.isEmpty()) {
            return List.of();
        }
        AtomicInteger contador = contadores.computeIfAbsent(
                nombreServicio.toLowerCase(), clave -> new AtomicInteger());
        int inicio = Math.floorMod(contador.getAndIncrement(), vivas.size());

        return java.util.stream.IntStream.range(0, vivas.size())
                .mapToObj(i -> vivas.get((inicio + i) % vivas.size()))
                .collect(java.util.stream.Collectors.toUnmodifiableList());
    }
}
