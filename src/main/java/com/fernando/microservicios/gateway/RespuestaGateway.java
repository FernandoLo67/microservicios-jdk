package com.fernando.microservicios.gateway;

import java.util.Objects;

public final class RespuestaGateway {

    private final int codigo;
    private final String cuerpo;
    private final String instanciaAtendio;

    public RespuestaGateway(int codigo, String cuerpo, String instanciaAtendio) {
        this.codigo = codigo;
        this.cuerpo = cuerpo;
        this.instanciaAtendio = instanciaAtendio;
    }

    public int codigo() {
        return codigo;
    }

    public String cuerpo() {
        return cuerpo;
    }

    public String instanciaAtendio() {
        return instanciaAtendio;
    }

    public boolean fueExitosa() {
        return codigo >= 200 && codigo < 300;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RespuestaGateway)) {
            return false;
        }
        RespuestaGateway that = (RespuestaGateway) o;
        return codigo == that.codigo
                && Objects.equals(cuerpo, that.cuerpo)
                && Objects.equals(instanciaAtendio, that.instanciaAtendio);
    }

    @Override
    public int hashCode() {
        return Objects.hash(codigo, cuerpo, instanciaAtendio);
    }

    @Override
    public String toString() {
        return "RespuestaGateway[codigo=" + codigo + ", cuerpo=" + cuerpo
                + ", instanciaAtendio=" + instanciaAtendio + "]";
    }
}
