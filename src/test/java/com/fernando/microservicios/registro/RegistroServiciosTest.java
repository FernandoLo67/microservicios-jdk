package com.fernando.microservicios.registro;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

class RegistroServiciosTest {

    private long[] ahora;
    private RegistroServicios registro;

    @BeforeEach
    void prepararRegistro() {
        ahora = new long[]{1_000_000L};
        registro = new RegistroServicios(() -> ahora[0]);
    }

    private InstanciaServicio instancia(String id, String nombre, int puerto) {
        return new InstanciaServicio(id, nombre, "127.0.0.1", puerto);
    }

    @Test
    void unaInstanciaRecienRegistradaEstaViva() {
        registro.registrar(instancia("c-1", "catalogo", 9001));

        assertTrue(registro.estaViva("c-1"));
        assertEquals(1, registro.instanciasDe("catalogo").size());
    }

    @Test
    void devuelveTodasLasInstanciasDeUnServicio() {
        registro.registrar(instancia("c-1", "catalogo", 9001));
        registro.registrar(instancia("c-2", "catalogo", 9002));
        registro.registrar(instancia("p-1", "pedidos", 9003));

        assertEquals(2, registro.instanciasDe("catalogo").size());
        assertEquals(1, registro.instanciasDe("pedidos").size());
        assertTrue(registro.instanciasDe("inventado").isEmpty());
    }

    @Test
    void laBusquedaPorNombreIgnoraMayusculas() {
        registro.registrar(instancia("c-1", "catalogo", 9001));
        assertEquals(1, registro.instanciasDe("CATALOGO").size());
    }

    @Test
    void unaInstanciaQueDejaDeLatirSeConsideraCaida() {
        registro.registrar(instancia("c-1", "catalogo", 9001));

        ahora[0] += RegistroServicios.TIEMPO_DE_VIDA_MS + 1;

        assertFalse(registro.estaViva("c-1"));
        assertTrue(registro.instanciasDe("catalogo").isEmpty(),
                "Una instancia caida no debe aparecer en las consultas");
    }

    @Test
    void enElLimiteExactoDelTiempoDeVidaSigueViva() {
        registro.registrar(instancia("c-1", "catalogo", 9001));
        ahora[0] += RegistroServicios.TIEMPO_DE_VIDA_MS;
        assertTrue(registro.estaViva("c-1"));
    }

    @Test
    void unLatidoRenuevaElTiempoDeVida() {
        registro.registrar(instancia("c-1", "catalogo", 9001));

        ahora[0] += RegistroServicios.TIEMPO_DE_VIDA_MS - 1;
        registro.latir("c-1");
        ahora[0] += RegistroServicios.TIEMPO_DE_VIDA_MS - 1;

        assertTrue(registro.estaViva("c-1"));
    }

    @Test
    void latirPorUnaInstanciaDesconocidaFalla() {
        assertThrows(NoSuchElementException.class, () -> registro.latir("fantasma"));
    }

    @Test
    void darDeBajaQuitaLaInstancia() {
        registro.registrar(instancia("c-1", "catalogo", 9001));

        assertTrue(registro.darDeBaja("c-1"));
        assertFalse(registro.darDeBaja("c-1"));
        assertTrue(registro.instanciasDe("catalogo").isEmpty());
    }

    @Test
    void limpiarCaidasEliminaSoloLasQueDejaronDeLatir() {
        registro.registrar(instancia("c-1", "catalogo", 9001));
        ahora[0] += RegistroServicios.TIEMPO_DE_VIDA_MS + 1;
        registro.registrar(instancia("c-2", "catalogo", 9002));

        assertEquals(1, registro.limpiarCaidas());
        assertEquals(1, registro.totalInstancias());
        assertEquals("c-2", registro.instanciasDe("catalogo").get(0).id());
    }

    @Test
    void listaLosNombresDeServiciosVivosOrdenados() {
        registro.registrar(instancia("p-1", "pedidos", 9003));
        registro.registrar(instancia("c-1", "catalogo", 9001));

        assertIterableEquals(java.util.List.of("catalogo", "pedidos"), registro.serviciosRegistrados());
    }

    @Test
    void laInstanciaValidaSusDatos() {
        assertThrows(IllegalArgumentException.class, () -> instancia("", "catalogo", 9001));
        assertThrows(IllegalArgumentException.class, () -> instancia("c-1", "  ", 9001));
        assertThrows(IllegalArgumentException.class, () -> instancia("c-1", "catalogo", 0));
        assertThrows(IllegalArgumentException.class, () -> instancia("c-1", "catalogo", 70000));
    }

    @Test
    void laUrlBaseSeArmaConHostYPuerto() {
        assertEquals("http://127.0.0.1:9001", instancia("c-1", "catalogo", 9001).urlBase());
    }
}
