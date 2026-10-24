package com.fernando.microservicios.resiliencia;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Retry con backoff exponencial y jitter.
 *
 * <p>El backoff exponencial evita que todos los clientes reintenten al mismo
 * tiempo despues de una caida (efecto thundering herd). El jitter agrega
 * un componente aleatorio para dispersar los reintentos aun mas.
 *
 * <p>Formula: {@code min(esperaBase * 2^intento + jitter, esperaMaxima)}
 *
 * <p>El reloj es inyectable para que las pruebas no necesiten
 * {@code Thread.sleep}.
 */
public class Reintento {

    private final int maxIntentos;
    private final long esperaBaseMs;
    private final long esperaMaximaMs;
    private final LongSupplier reloj;

    public Reintento(int maxIntentos, long esperaBaseMs, long esperaMaximaMs, LongSupplier reloj) {
        if (maxIntentos < 1) {
            throw new IllegalArgumentException("Debe haber al menos 1 intento");
        }
        if (esperaBaseMs < 0 || esperaMaximaMs < 0) {
            throw new IllegalArgumentException("Las esperas no pueden ser negativas");
        }
        this.maxIntentos = maxIntentos;
        this.esperaBaseMs = esperaBaseMs;
        this.esperaMaximaMs = esperaMaximaMs;
        this.reloj = Objects.requireNonNull(reloj);
    }

    public <T> T ejecutar(CircuitBreaker.Accion<T> accion) throws Exception {
        Exception ultimaExcepcion = null;

        for (int intento = 0; intento < maxIntentos; intento++) {
            try {
                return accion.ejecutar();
            } catch (Exception e) {
                ultimaExcepcion = e;
                if (intento < maxIntentos - 1) {
                    esperar(calcularEspera(intento));
                }
            }
        }

        throw new ReintentosAgotadosException(
                "Se agotaron los " + maxIntentos + " intentos", ultimaExcepcion);
    }

    long calcularEspera(int intento) {
        long espera = esperaBaseMs * (1L << intento);
        long jitter = (long) (espera * 0.2 * Math.random());
        return Math.min(espera + jitter, esperaMaximaMs);
    }

    private void esperar(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Reintento interrumpido", e);
        }
    }

    public int getMaxIntentos() {
        return maxIntentos;
    }

    public static class ReintentosAgotadosException extends Exception {

        private static final long serialVersionUID = 1L;

        public ReintentosAgotadosException(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }
}
