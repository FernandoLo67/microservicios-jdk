# Microservicios en JDK puro

Una arquitectura de microservicios completa —registro de servicios, gateway, balanceo de carga, failover y circuit breaker— **implementada desde cero con la biblioteca estandar de Java**, sin Spring Cloud, sin Eureka, sin Resilience4j.

> Este repo es el hermano de `microservicios-spring-boot`. Aquel muestra como se hace con el framework; este muestra **que hace el framework por dentro**.

## Las piezas

```
registro/      RegistroServicios      -> lo que hace Eureka: quien esta vivo y donde
               BalanceadorRoundRobin  -> lo que hace Ribbon/LoadBalancer: a quien le toca
resiliencia/   CircuitBreaker         -> lo que hace Resilience4j: cuando dejar de insistir
               Reintento              -> retry con backoff exponencial y jitter
gateway/       Gateway                -> lo que hace Spring Cloud Gateway: la puerta unica
servicio/      MicroServicio          -> un servicio que se registra y expone rutas
```

## Como ejecutar

```bash
mvn package
java -jar target/microservicios-jdk-1.0.0.jar 8080
```

Arranca el gateway y tres instancias (dos de `catalogo`, una de `pedidos`), cada una en su puerto:

```bash
curl http://localhost:8080/servicio/catalogo/productos   # fijate como "instancia" alterna
curl http://localhost:8080/gateway/servicios
```

Escribiendo `caer` en la consola se simula la caida de `catalogo-1`: las siguientes llamadas siguen respondiendo 200, atendidas por `catalogo-2`. Eso es el failover funcionando.

## Los tres mecanismos que vale la pena mirar

**1. Registro con latidos.** Cada instancia se registra y debe mandar un latido cada cierto tiempo. Si deja de latir mas de `TIEMPO_DE_VIDA_MS`, desaparece de las consultas sin que nadie tenga que darla de baja. Es como se detecta una maquina que murio sin avisar.

**2. Balanceo y failover.** `ordenDeIntento()` devuelve **todas** las instancias vivas, empezando por la que le toca el turno. El gateway las recorre hasta que una responde: por eso una instancia caida no rompe la peticion, solo la desvia. El indice usa `Math.floorMod` para que el reparto siga siendo correcto cuando el contador desborda.

**3. Cortacircuitos de tres estados.**

```
CERRADO ──(3 fallos seguidos)──> ABIERTO ──(pasan 5s)──> SEMIABIERTO
   ^                                                          │
   └──────────────(1 exito)─────────────────────────────────┘
                                  │
   ABIERTO <──(1 fallo, sin esperar al umbral)───────────────┘
```

El detalle que suele hacerse mal: en `SEMIABIERTO` **un solo fallo reabre el circuito**, no hace falta volver a acumular tres. Y con el circuito abierto, `ejecutar()` ni siquiera intenta la llamada — hay una prueba que lo verifica con una bandera.

Otro detalle: un **404 del servicio no cuenta como fallo**. Que un recurso no exista es una respuesta valida, no un problema de infraestructura; contarlo abriria el circuito por errores del cliente.

## Retry con backoff exponencial

`Reintento` complementa al circuit breaker: antes de rendirse, reintenta la operacion con esperas que crecen exponencialmente (`base * 2^intento`) mas un 20% de jitter aleatorio para evitar el efecto thundering herd — cuando todos los clientes reintentan al mismo instante despues de una caida.

```
intento 0:  100ms + jitter
intento 1:  200ms + jitter
intento 2:  400ms + jitter
...         hasta un maximo configurable
```

La espera nunca supera el tope (`esperaMaximaMs`), y el reloj es inyectable igual que en el circuit breaker.

## Pruebas

**54 pruebas**, con reloj inyectable (`LongSupplier`) para que los vencimientos de latidos y las esperas del cortacircuitos se prueben al instante en vez de con `Thread.sleep`.

`GatewayTest` son pruebas de integracion de verdad: levantan microservicios reales en puertos libres y el gateway los llama por HTTP sobre loopback. Verifican el reparto entre instancias, el failover cuando una cae, la apertura del cortacircuitos y la recuperacion cuando el servicio revive.

```bash
mvn test
```
