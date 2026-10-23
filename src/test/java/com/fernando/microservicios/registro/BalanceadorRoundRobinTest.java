package com.fernando.microservicios.registro;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BalanceadorRoundRobinTest {

    private long[] ahora;
    private RegistroServicios registro;
    private BalanceadorRoundRobin balanceador;

    @BeforeEach
    void prepararBalanceador() {
        ahora = new long[]{1_000_000L};
        registro = new RegistroServicios(() -> ahora[0]);
        balanceador = new BalanceadorRoundRobin(registro);
        registro.registrar(new InstanciaServicio("c-1", "catalogo", "127.0.0.1", 9001));
        registro.registrar(new InstanciaServicio("c-2", "catalogo", "127.0.0.1", 9002));
        registro.registrar(new InstanciaServicio("c-3", "catalogo", "127.0.0.1", 9003));
    }

    @Test
    void reparteLasLlamadasEnCicloEntreLasInstancias() {
        assertEquals("c-1", balanceador.siguiente("catalogo").orElseThrow().id());
        assertEquals("c-2", balanceador.siguiente("catalogo").orElseThrow().id());
        assertEquals("c-3", balanceador.siguiente("catalogo").orElseThrow().id());
        assertEquals("c-1", balanceador.siguiente("catalogo").orElseThrow().id(),
                "Al llegar al final vuelve a empezar");
    }

    @Test
    void cadaServicioLlevaSuPropioTurno() {
        registro.registrar(new InstanciaServicio("p-1", "pedidos", "127.0.0.1", 9101));
        registro.registrar(new InstanciaServicio("p-2", "pedidos", "127.0.0.1", 9102));

        balanceador.siguiente("catalogo");
        balanceador.siguiente("catalogo");

        assertEquals("p-1", balanceador.siguiente("pedidos").orElseThrow().id(),
                "El turno de pedidos no se ve afectado por las llamadas a catalogo");
    }

    @Test
    void unServicioSinInstanciasDevuelveVacio() {
        assertTrue(balanceador.siguiente("inventado").isEmpty());
        assertTrue(balanceador.ordenDeIntento("inventado").isEmpty());
    }

    @Test
    void lasInstanciasCaidasQuedanFueraDelReparto() {
        ahora[0] += RegistroServicios.TIEMPO_DE_VIDA_MS + 1;
        registro.registrar(new InstanciaServicio("c-4", "catalogo", "127.0.0.1", 9004));

        assertEquals("c-4", balanceador.siguiente("catalogo").orElseThrow().id());
        assertEquals("c-4", balanceador.siguiente("catalogo").orElseThrow().id());
    }

    @Test
    void elOrdenDeIntentoIncluyeTodasLasInstanciasSinRepetir() {
        List<InstanciaServicio> orden = balanceador.ordenDeIntento("catalogo");

        assertEquals(3, orden.size());
        assertEquals(3, orden.stream().map(InstanciaServicio::id).distinct().count());
    }

    @Test
    void elOrdenDeIntentoEmpiezaPorLaInstanciaDelTurno() {
        assertEquals("c-1", balanceador.ordenDeIntento("catalogo").get(0).id());
        assertEquals("c-2", balanceador.ordenDeIntento("catalogo").get(0).id());
        assertEquals("c-3", balanceador.ordenDeIntento("catalogo").get(0).id());
        assertEquals("c-1", balanceador.ordenDeIntento("catalogo").get(0).id());
    }

    @Test
    void elRepartoSigueSiendoValidoTrasMuchasLlamadas() {
        int[] conteo = new int[3];
        for (int i = 0; i < 300; i++) {
            String id = balanceador.siguiente("catalogo").orElseThrow().id();
            conteo[Integer.parseInt(id.substring(2)) - 1]++;
        }
        assertArrayEquals(new int[]{100, 100, 100}, conteo, "El reparto debe ser parejo");
    }
}
