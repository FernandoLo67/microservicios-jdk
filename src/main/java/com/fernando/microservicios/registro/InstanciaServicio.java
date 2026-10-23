package com.fernando.microservicios.registro;

import java.util.Objects;

public final class InstanciaServicio {

    private final String id;
    private final String nombre;
    private final String host;
    private final int puerto;

    public InstanciaServicio(String id, String nombre, String host, int puerto) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("El id de la instancia es obligatorio");
        }
        if (nombre == null || nombre.isBlank()) {
            throw new IllegalArgumentException("El nombre del servicio es obligatorio");
        }
        if (puerto <= 0 || puerto > 65535) {
            throw new IllegalArgumentException("Puerto invalido: " + puerto);
        }
        this.id = id;
        this.nombre = nombre;
        this.host = host;
        this.puerto = puerto;
    }

    public String id() {
        return id;
    }

    public String nombre() {
        return nombre;
    }

    public String host() {
        return host;
    }

    public int puerto() {
        return puerto;
    }

    public String urlBase() {
        return "http://" + host + ":" + puerto;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof InstanciaServicio)) {
            return false;
        }
        InstanciaServicio that = (InstanciaServicio) o;
        return puerto == that.puerto
                && Objects.equals(id, that.id)
                && Objects.equals(nombre, that.nombre)
                && Objects.equals(host, that.host);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, nombre, host, puerto);
    }

    @Override
    public String toString() {
        return nombre + "[" + id + "] @ " + urlBase();
    }
}
