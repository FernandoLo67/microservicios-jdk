package com.fernando.microservicios.resiliencia;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ReintentoTest {

    @Test
    void devuelveElResultadoAlPrimerIntento() throws Exception {
        Reintento reintento = crearReintento(3, 100, 5_000);

        String resultado = reintento.ejecutar(() -> "exito");

        assertEquals("exito", resultado);
    }

    @Test
    void reintentaHastaQueLoLogra() throws Exception {
        AtomicInteger intentos = new AtomicInteger(0);
        Reintento reintento = crearReintento(3, 10, 1_000);

        String resultado = reintento.ejecutar(() -> {
            if (intentos.incrementAndGet() < 3) {
                throw new RuntimeException("fallo temporal");
            }
            return "recuperado";
        });

        assertEquals("recuperado", resultado);
        assertEquals(3, intentos.get());
    }

    @Test
    void lanzaReintentosAgotadosCuandoTodosFallan() {
        Reintento reintento = crearReintento(2, 10, 1_000);

        Reintento.ReintentosAgotadosException ex = assertThrows(
                Reintento.ReintentosAgotadosException.class,
                () -> reintento.ejecutar(() -> {
                    throw new RuntimeException("siempre falla");
                })
        );

        assertNotNull(ex.getCause());
        assertTrue(ex.getMessage().contains("2 intentos"));
    }

    @Test
    void conservaLaUltimaExcepcionComoCausa() {
        Reintento reintento = crearReintento(3, 10, 1_000);
        AtomicInteger contador = new AtomicInteger(0);

        Reintento.ReintentosAgotadosException ex = assertThrows(
                Reintento.ReintentosAgotadosException.class,
                () -> reintento.ejecutar(() -> {
                    throw new IllegalStateException("fallo #" + contador.incrementAndGet());
                })
        );

        assertEquals("fallo #3", ex.getCause().getMessage());
    }

    @Test
    void elBackoffCreceExponencialmente() {
        Reintento reintento = crearReintento(5, 100, 10_000);

        long espera0 = reintento.calcularEspera(0);
        long espera1 = reintento.calcularEspera(1);
        long espera2 = reintento.calcularEspera(2);

        assertTrue(espera0 >= 100 && espera0 <= 120, "Base ~100ms + hasta 20% jitter");
        assertTrue(espera1 >= 200 && espera1 <= 240, "2^1 * 100 = 200ms + jitter");
        assertTrue(espera2 >= 400 && espera2 <= 480, "2^2 * 100 = 400ms + jitter");
    }

    @Test
    void laEsperaNuncaSuperaElMaximo() {
        Reintento reintento = crearReintento(10, 100, 500);

        long espera = reintento.calcularEspera(8);

        assertTrue(espera <= 500, "Con cap en 500ms, nunca debe superar ese valor");
    }

    @Test
    void noEjecutaElSleepSiTriunfaAlPrimerIntento() throws Exception {
        long inicio = System.currentTimeMillis();
        Reintento reintento = crearReintento(3, 1_000, 10_000);

        reintento.ejecutar(() -> "rapido");

        long duracion = System.currentTimeMillis() - inicio;
        assertTrue(duracion < 500, "Sin reintentos no deberia haber espera");
    }

    @Test
    void rechazaMaxIntentosInvalido() {
        assertThrows(IllegalArgumentException.class,
                () -> crearReintento(0, 100, 1_000));
    }

    @Test
    void rechazaEsperasNegativas() {
        assertThrows(IllegalArgumentException.class,
                () -> crearReintento(3, -1, 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> crearReintento(3, 100, -1));
    }

    @Test
    void rechazaRelojNulo() {
        assertThrows(NullPointerException.class,
                () -> new Reintento(3, 100, 1_000, null));
    }

    private Reintento crearReintento(int maxIntentos, long esperaBaseMs, long esperaMaximaMs) {
        return new Reintento(maxIntentos, esperaBaseMs, esperaMaximaMs, System::currentTimeMillis);
    }
}
