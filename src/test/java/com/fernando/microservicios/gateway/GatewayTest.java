package com.fernando.microservicios.gateway;

import com.fernando.microservicios.registro.RegistroServicios;
import com.fernando.microservicios.resiliencia.CircuitBreaker;
import com.fernando.microservicios.servicio.MicroServicio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de integracion reales: levantan microservicios de verdad en puertos
 * libres y hacen que el gateway los llame por HTTP sobre loopback.
 */
class GatewayTest {

    private long[] ahora;
    private RegistroServicios registro;
    private Gateway gateway;
    private MicroServicio catalogoA;
    private MicroServicio catalogoB;

    @BeforeEach
    void levantarTodo() throws IOException {
        ahora = new long[]{0L};
        registro = new RegistroServicios(() -> ahora[0]);
        gateway = new Gateway(registro, () -> ahora[0]);

        catalogoA = new MicroServicio("catalogo", "catalogo-1", registro)
                .ruta("/productos", cuerpo -> "{\"instancia\":\"catalogo-1\"}");
        catalogoB = new MicroServicio("catalogo", "catalogo-2", registro)
                .ruta("/productos", cuerpo -> "{\"instancia\":\"catalogo-2\"}");
        catalogoA.iniciar();
        catalogoB.iniciar();
    }

    @AfterEach
    void apagarTodo() {
        gateway.close();
        catalogoA.close();
        catalogoB.close();
    }

    @Test
    void enrutaLaLlamadaAUnaInstanciaViva() {
        RespuestaGateway respuesta = gateway.enrutar("catalogo", "/productos", "GET", null);

        assertEquals(200, respuesta.codigo());
        assertTrue(respuesta.fueExitosa());
        assertTrue(respuesta.cuerpo().contains("catalogo-"));
    }

    @Test
    void reparteLasLlamadasEntreLasDosInstancias() {
        Set<String> atendieron = new HashSet<>();
        for (int i = 0; i < 6; i++) {
            atendieron.add(gateway.enrutar("catalogo", "/productos", "GET", null).instanciaAtendio());
        }
        assertEquals(Set.of("catalogo-1", "catalogo-2"), atendieron,
                "El balanceo debe usar ambas instancias");
    }

    @Test
    void siUnaInstanciaCaeElGatewayHaceFailoverALaOtra() {
        catalogoA.marcarComoEnfermo();

        for (int i = 0; i < 4; i++) {
            RespuestaGateway respuesta = gateway.enrutar("catalogo", "/productos", "GET", null);
            assertEquals(200, respuesta.codigo(), "Deberia haber respondido la instancia sana");
            assertEquals("catalogo-2", respuesta.instanciaAtendio());
        }
    }

    @Test
    void siTodasLasInstanciasCaenDevuelve503() {
        catalogoA.marcarComoEnfermo();
        catalogoB.marcarComoEnfermo();

        RespuestaGateway respuesta = gateway.enrutar("catalogo", "/productos", "GET", null);

        assertEquals(503, respuesta.codigo());
        assertNull(respuesta.instanciaAtendio());
    }

    @Test
    void unServicioSinInstanciasDevuelve503() {
        RespuestaGateway respuesta = gateway.enrutar("inventado", "/lo-que-sea", "GET", null);

        assertEquals(503, respuesta.codigo());
        assertTrue(respuesta.cuerpo().contains("no hay instancias vivas"));
    }

    @Test
    void unaRutaInexistenteEnElServicioSePropagaComo404() {
        RespuestaGateway respuesta = gateway.enrutar("catalogo", "/no-existe", "GET", null);

        assertEquals(404, respuesta.codigo(),
                "Un 404 del servicio no es un fallo de infraestructura: se pasa tal cual");
    }

    @Test
    void un404DelServicioNoAbreElCortacircuitos() {
        for (int i = 0; i < 5; i++) {
            gateway.enrutar("catalogo", "/no-existe", "GET", null);
        }
        assertEquals(CircuitBreaker.Estado.CERRADO, gateway.cortacircuitoDe("catalogo").estado());
    }

    @Test
    void trasVariosFallosElCortacircuitosSeAbreYCortaSinIntentar() {
        catalogoA.marcarComoEnfermo();
        catalogoB.marcarComoEnfermo();

        for (int i = 0; i < Gateway.UMBRAL_DE_FALLOS; i++) {
            gateway.enrutar("catalogo", "/productos", "GET", null);
        }

        assertEquals(CircuitBreaker.Estado.ABIERTO, gateway.cortacircuitoDe("catalogo").estado());

        // Aunque los servicios revivan, el circuito abierto corta de inmediato
        catalogoA.marcarComoSano();
        catalogoB.marcarComoSano();
        RespuestaGateway respuesta = gateway.enrutar("catalogo", "/productos", "GET", null);

        assertEquals(503, respuesta.codigo());
        assertTrue(respuesta.cuerpo().contains("circuito abierto"));
    }

    @Test
    void pasadoElTiempoDeEsperaElCortacircuitosDejaReintentarYSeRecupera() {
        catalogoA.marcarComoEnfermo();
        catalogoB.marcarComoEnfermo();
        for (int i = 0; i < Gateway.UMBRAL_DE_FALLOS; i++) {
            gateway.enrutar("catalogo", "/productos", "GET", null);
        }
        assertEquals(CircuitBreaker.Estado.ABIERTO, gateway.cortacircuitoDe("catalogo").estado());

        catalogoA.marcarComoSano();
        catalogoB.marcarComoSano();
        ahora[0] += Gateway.ESPERA_REINTENTO_MS;

        RespuestaGateway respuesta = gateway.enrutar("catalogo", "/productos", "GET", null);

        assertEquals(200, respuesta.codigo());
        assertEquals(CircuitBreaker.Estado.CERRADO, gateway.cortacircuitoDe("catalogo").estado());
    }

    @Test
    void cadaServicioTieneSuPropioCortacircuitos() {
        assertNotSame(gateway.cortacircuitoDe("catalogo"), gateway.cortacircuitoDe("pedidos"));
        assertSame(gateway.cortacircuitoDe("catalogo"), gateway.cortacircuitoDe("CATALOGO"));
    }

    @Test
    void unaInstanciaQueDejaDeLatirDejaDeRecibirLlamadas() {
        ahora[0] += RegistroServicios.TIEMPO_DE_VIDA_MS + 1;
        registro.latir("catalogo-2");

        for (int i = 0; i < 4; i++) {
            assertEquals("catalogo-2", gateway.enrutar("catalogo", "/productos", "GET", null).instanciaAtendio(),
                    "catalogo-1 dejo de latir, no deberia recibir trafico");
        }
    }

    @Test
    void partirRutaSeparaElServicioDelCamino() {
        assertArrayEquals(new String[]{"catalogo", "/productos"},
                Gateway.partirRuta("/servicio/catalogo/productos"));
        assertArrayEquals(new String[]{"catalogo", "/"},
                Gateway.partirRuta("/servicio/catalogo"));
        assertArrayEquals(new String[]{"catalogo", "/a/b/c"},
                Gateway.partirRuta("/servicio/catalogo/a/b/c"));
    }

    @Test
    void partirRutaRechazaLoQueNoEsUnaRutaDeServicio() {
        assertNull(Gateway.partirRuta("/otra/cosa"));
        assertNull(Gateway.partirRuta("/servicio/"));
        assertNull(Gateway.partirRuta(null));
    }
}
