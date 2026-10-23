package com.fernando.microservicios;

import com.fernando.microservicios.gateway.Gateway;
import com.fernando.microservicios.registro.RegistroServicios;
import com.fernando.microservicios.servicio.MicroServicio;

import java.io.IOException;
import java.util.Scanner;

public class Main {

    public static void main(String[] args) throws IOException {
        int puertoGateway = args.length > 0 ? Integer.parseInt(args[0]) : 8080;

        RegistroServicios registro = new RegistroServicios();

        MicroServicio catalogoA = new MicroServicio("catalogo", "catalogo-1", registro)
                .ruta("/productos", cuerpo -> "{\"instancia\":\"catalogo-1\",\"productos\":[\"teclado\",\"mouse\"]}");
        MicroServicio catalogoB = new MicroServicio("catalogo", "catalogo-2", registro)
                .ruta("/productos", cuerpo -> "{\"instancia\":\"catalogo-2\",\"productos\":[\"teclado\",\"mouse\"]}");
        MicroServicio pedidos = new MicroServicio("pedidos", "pedidos-1", registro)
                .ruta("/pedidos", cuerpo -> "{\"instancia\":\"pedidos-1\",\"pedidos\":[]}");

        catalogoA.iniciar();
        catalogoB.iniciar();
        pedidos.iniciar();

        Gateway gateway = new Gateway(registro);
        gateway.iniciar(puertoGateway);

        System.out.println("=== Microservicios en JDK puro ===");
        System.out.println("Gateway:    http://localhost:" + gateway.getPuerto());
        System.out.println("catalogo-1: puerto " + catalogoA.getPuerto());
        System.out.println("catalogo-2: puerto " + catalogoB.getPuerto());
        System.out.println("pedidos-1:  puerto " + pedidos.getPuerto());
        System.out.println();
        System.out.println("Prueba (fijate como 'instancia' alterna entre catalogo-1 y catalogo-2):");
        System.out.println("  curl http://localhost:" + gateway.getPuerto() + "/servicio/catalogo/productos");
        System.out.println("  curl http://localhost:" + gateway.getPuerto() + "/gateway/servicios");
        System.out.println();
        System.out.println("Escribe 'caer' + Enter para simular la caida de catalogo-1,");
        System.out.println("'sanar' para revivirla, o 'salir' para terminar.");

        Scanner scanner = new Scanner(System.in);
        boolean salir = false;
        while (!salir && scanner.hasNextLine()) {
            switch (scanner.nextLine().trim().toLowerCase()) {
                case "caer":
                    catalogoA.marcarComoEnfermo();
                    System.out.println("catalogo-1 ahora responde 503. El gateway hara failover a catalogo-2.");
                    break;
                case "sanar":
                    catalogoA.marcarComoSano();
                    System.out.println("catalogo-1 vuelve a estar sana.");
                    break;
                case "salir":
                    salir = true;
                    break;
                default:
                    System.out.println("Comandos: caer, sanar, salir");
                    break;
            }
        }

        gateway.close();
        catalogoA.close();
        catalogoB.close();
        pedidos.close();
        System.out.println("Todo detenido.");
    }
}
