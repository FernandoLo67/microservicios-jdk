package com.fernando.microservicios.resiliencia;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CircuitBreakerTest {

    private long[] ahora;
    private CircuitBreaker breaker;

    @BeforeEach
    void prepararCortacircuitos() {
        ahora = new long[]{0L};
        breaker = new CircuitBreaker("catalogo", 3, 5_000, () -> ahora[0]);
    }

    @Test
    void empiezaCerradoYDejandoPasar() {
        assertEquals(CircuitBreaker.Estado.CERRADO, breaker.estado());
        assertTrue(breaker.permitePasar());
    }

    @Test
    void seAbreAlAlcanzarElUmbralDeFallos() {
        breaker.registrarFallo();
        breaker.registrarFallo();
        assertEquals(CircuitBreaker.Estado.CERRADO, breaker.estado(), "Dos fallos aun no bastan");

        breaker.registrarFallo();

        assertEquals(CircuitBreaker.Estado.ABIERTO, breaker.estado());
        assertFalse(breaker.permitePasar());
    }

    @Test
    void unExitoReiniciaLaCuentaDeFallos() {
        breaker.registrarFallo();
        breaker.registrarFallo();
        breaker.registrarExito();
        breaker.registrarFallo();
        breaker.registrarFallo();

        assertEquals(CircuitBreaker.Estado.CERRADO, breaker.estado());
        assertEquals(2, breaker.fallosSeguidos());
    }

    @Test
    void pasadoElTiempoDeEsperaPasaASemiabierto() {
        abrirElCircuito();

        ahora[0] += 5_000;

        assertEquals(CircuitBreaker.Estado.SEMIABIERTO, breaker.estado());
        assertTrue(breaker.permitePasar(), "En semiabierto se deja pasar una llamada de prueba");
    }

    @Test
    void antesDelTiempoDeEsperaSigueAbierto() {
        abrirElCircuito();
        ahora[0] += 4_999;
        assertEquals(CircuitBreaker.Estado.ABIERTO, breaker.estado());
    }

    @Test
    void unExitoEnSemiabiertoCierraElCircuito() {
        abrirElCircuito();
        ahora[0] += 5_000;

        breaker.registrarExito();

        assertEquals(CircuitBreaker.Estado.CERRADO, breaker.estado());
        assertEquals(0, breaker.fallosSeguidos());
    }

    @Test
    void unFalloEnSemiabiertoVuelveAAbrirDeInmediato() {
        abrirElCircuito();
        ahora[0] += 5_000;
        assertEquals(CircuitBreaker.Estado.SEMIABIERTO, breaker.estado());

        breaker.registrarFallo();

        assertEquals(CircuitBreaker.Estado.ABIERTO, breaker.estado(),
                "Un solo fallo en semiabierto reabre, sin esperar al umbral");
    }

    @Test
    void ejecutarDevuelveElResultadoYRegistraElExito() throws Exception {
        assertEquals("ok", breaker.ejecutar(() -> "ok"));
        assertEquals(CircuitBreaker.Estado.CERRADO, breaker.estado());
    }

    @Test
    void ejecutarPropagaLaExcepcionYCuentaElFallo() {
        assertThrows(IllegalStateException.class, () -> breaker.ejecutar(() -> {
            throw new IllegalStateException("fallo");
        }));
        assertEquals(1, breaker.fallosSeguidos());
    }

    @Test
    void conElCircuitoAbiertoEjecutarNiSiquieraIntenta() {
        abrirElCircuito();
        boolean[] seEjecuto = {false};

        assertThrows(CircuitBreaker.CircuitoAbiertoException.class, () -> breaker.ejecutar(() -> {
            seEjecuto[0] = true;
            return "no deberia llegar aqui";
        }));
        assertFalse(seEjecuto[0], "Con el circuito abierto la accion no debe ejecutarse");
    }

    @Test
    void reiniciarDevuelveElCircuitoAEstadoInicial() {
        abrirElCircuito();
        breaker.reiniciar();

        assertEquals(CircuitBreaker.Estado.CERRADO, breaker.estado());
        assertEquals(0, breaker.fallosSeguidos());
    }

    @Test
    void losParametrosInvalidosSonRechazados() {
        assertThrows(IllegalArgumentException.class,
                () -> new CircuitBreaker("x", 0, 1000, () -> 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new CircuitBreaker("x", 3, -1, () -> 0L));
    }

    private void abrirElCircuito() {
        for (int i = 0; i < 3; i++) {
            breaker.registrarFallo();
        }
        assertEquals(CircuitBreaker.Estado.ABIERTO, breaker.estado());
    }
}
