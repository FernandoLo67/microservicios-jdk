package com.fernando.microservicios.resiliencia;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Cortacircuitos clasico de tres estados:
 *
 *   CERRADO      -> deja pasar todo. Tras N fallos seguidos abre.
 *   ABIERTO      -> rechaza sin intentar. Pasado el tiempo de espera pasa a SEMIABIERTO.
 *   SEMIABIERTO  -> deja pasar una llamada de prueba. Si va bien cierra; si falla, reabre.
 *
 * Sirve para no seguir golpeando un servicio que ya sabemos que esta caido.
 */
public class CircuitBreaker {

    public enum Estado { CERRADO, ABIERTO, SEMIABIERTO }

    private final String nombre;
    private final int umbralDeFallos;
    private final long esperaAntesDeReintentarMs;
    private final LongSupplier reloj;

    private final AtomicInteger fallosSeguidos = new AtomicInteger();
    private volatile Estado estado = Estado.CERRADO;
    private volatile long abiertoDesde;

    public CircuitBreaker(String nombre, int umbralDeFallos, long esperaAntesDeReintentarMs, LongSupplier reloj) {
        if (umbralDeFallos <= 0) {
            throw new IllegalArgumentException("El umbral de fallos debe ser mayor a 0");
        }
        if (esperaAntesDeReintentarMs < 0) {
            throw new IllegalArgumentException("La espera no puede ser negativa");
        }
        this.nombre = nombre;
        this.umbralDeFallos = umbralDeFallos;
        this.esperaAntesDeReintentarMs = esperaAntesDeReintentarMs;
        this.reloj = reloj;
    }

    public synchronized Estado estado() {
        if (estado == Estado.ABIERTO && reloj.getAsLong() - abiertoDesde >= esperaAntesDeReintentarMs) {
            estado = Estado.SEMIABIERTO;
        }
        return estado;
    }

    /** true si en este momento se permite intentar la llamada. */
    public boolean permitePasar() {
        return estado() != Estado.ABIERTO;
    }

    public synchronized void registrarExito() {
        fallosSeguidos.set(0);
        estado = Estado.CERRADO;
    }

    public synchronized void registrarFallo() {
        if (estado == Estado.SEMIABIERTO) {
            abrir();
            return;
        }
        if (fallosSeguidos.incrementAndGet() >= umbralDeFallos) {
            abrir();
        }
    }

    private void abrir() {
        estado = Estado.ABIERTO;
        abiertoDesde = reloj.getAsLong();
    }

    public synchronized void reiniciar() {
        fallosSeguidos.set(0);
        estado = Estado.CERRADO;
    }

    public int fallosSeguidos() {
        return fallosSeguidos.get();
    }

    public String getNombre() {
        return nombre;
    }

    /** Ejecuta la accion protegida por el cortacircuitos. */
    public <T> T ejecutar(Accion<T> accion) throws Exception {
        if (!permitePasar()) {
            throw new CircuitoAbiertoException("El circuito de '" + nombre + "' esta abierto");
        }
        try {
            T resultado = accion.ejecutar();
            registrarExito();
            return resultado;
        } catch (Exception e) {
            registrarFallo();
            throw e;
        }
    }

    @FunctionalInterface
    public interface Accion<T> {
        T ejecutar() throws Exception;
    }

    public static class CircuitoAbiertoException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public CircuitoAbiertoException(String mensaje) {
            super(mensaje);
        }
    }
}
