# Similar Products API (Spring Boot)

API REST que devuelve el detalle de los productos similares a uno dado, orquestando dos APIs existentes (`similarids` y `product`). Versión **Java 17 + Spring Boot 3 (WebFlux)** del enunciado, con el mismo diseño y comportamiento que la [versión Python](https://github.com/howzera/backendDevTest).

- Enunciado original: [docs/original-statement.md](docs/original-statement.md)
- Contrato expuesto: [similarProducts.yaml](similarProducts.yaml)
- APIs consumidas: [existingApis.yaml](existingApis.yaml)

```
GET /product/{productId}/similar
200 -> [{"id": "2", "name": "Dress", "price": 19.99, "availability": true}, ...]   (orden de similitud)
404 -> {"detail": "Product '999' not found"}
503 -> {"detail": "Similar products for '1' are temporarily unavailable"}
```

## Guía rápida: ejecución y pruebas

### Puertos en localhost

| Puerto | Servicio |
|---|---|
| **5000** | Esta API (`app`) |
| **3001** | Mock de las APIs existentes (`simulado`) |
| **3000** | Grafana (resultados de k6) |
| **8086** | InfluxDB (métricas de k6) |

> Usa los mismos puertos que la versión Python y que el [servicio de notificaciones](https://github.com/howzera/ai-python-test): no levantes dos a la vez. Ejecuta `docker compose down` en uno antes de arrancar el otro.

### Paso a paso

1. **Iniciar Docker Desktop.** En Windows/macOS, compartir la carpeta `shared` si Docker lo pide.
2. **Ejecutar los tests** (JDK 17; Maven no hace falta, lo descarga el wrapper):
   ```bash
   ./mvnw test          # Windows: mvnw.cmd test    -> esperado: Tests run: 37, Failures: 0
   ```
3. **Levantar los mocks y las métricas:**
   ```bash
   docker compose up -d simulado influxdb grafana
   ```
4. **Comprobar el mock:** `curl http://localhost:3001/product/1/similarids` → `[2,3,4]`
5. **Levantar la API (puerto 5000).** La imagen compila el proyecto, no hace falta JDK en la máquina:
   ```bash
   docker compose up -d --build app
   ```
   Alternativa sin Docker: `./mvnw spring-boot:run` (usa `http://localhost:3001` por defecto).
6. **Probar los escenarios** con `curl http://localhost:5000/product/{id}/similar`, **en este orden** (las filas 2 y 3 dependen de la caché):

   | # | Petición | Resultado esperado | Tiempo | Qué demuestra |
   |---|---|---|---|---|
   | 1 | `/product/1/similar` | 200 → 2, 3, 4 | ~0,15 s | Caso normal, orden de similitud |
   | 2 | `/product/2/similar` | 200 → 3, 100 | ~1,5 s | El 1000 (5 s) supera el timeout: respuesta parcial |
   | 3 | `/product/2/similar` (esperar ~5 s) | 200 → 3, 100, 1000 | ~10 ms | *Late completion*: el 1000 entró en caché |
   | 4 | `/product/3/similar` | 200 → 100, 1000 | ~1,5 s | 10000 (50 s) demasiado lento: se omite |
   | 5 | `/product/4/similar` | 200 → 1, 2 | ms | El similar 5 da 404: se omite |
   | 6 | `/product/5/similar` | 200 → 1, 2 | ms | El similar 6 da 500: se omite |
   | 7 | `/product/999/similar` | **404** `Product '999' not found` | ms | El producto pedido no existe |

7. **Probar la resiliencia** (dependencia caída):

   | Paso | Comando | Resultado esperado |
   |---|---|---|
   | Parar el mock | `docker compose stop simulado` | - |
   | Producto no cacheado | `curl http://localhost:5000/product/7/similar` | **503** `Similar products for '7' are temporarily unavailable` |
   | Producto cacheado | `curl http://localhost:5000/product/1/similar` | **200** → 2, 3, 4 (desde la caché) |
   | Volver a levantar el mock | `docker compose start simulado` | - |

8. **Vaciar la caché:** `docker compose restart app`. Sin reinicio, caduca sola a los 5 minutos.
9. **Prueba de carga k6:**
   ```bash
   docker compose run --rm k6 run scripts/test.js
   ```
   Resultados en Grafana: http://localhost:3000/d/Le2Ku9NMk/k6-performance-test (rango "Last 15 minutes").
10. **Parar todo:** `docker compose down`

> En Windows PowerShell, usa `curl.exe` en lugar de `curl`.

## Arquitectura hexagonal

```
src/main/java/com/similarproducts/
├── domain/                          # Núcleo: sin Spring ni HTTP (solo Reactor como tipo asíncrono)
│   ├── ProductDetail.java           #   value object inmutable (record, precio BigDecimal)
│   ├── ProductNotFoundException     #   el producto no existe (respuesta de negocio válida)
│   ├── ProductUnavailableException  #   la fuente falló (error, timeout, circuito abierto)
│   └── ProductRepository.java       #   puerto de salida
├── application/
│   └── GetSimilarProducts.java      # Caso de uso: orquestación y reglas de negocio
├── infrastructure/
│   ├── web/                         # Adaptador de ENTRADA: controller, DTOs, errores -> 404/503
│   └── productclient/               # Adaptadores de SALIDA, todos implementan ProductRepository
│       ├── HttpProductRepository           # WebClient contra las APIs existentes
│       ├── CircuitBreakerProductRepository # decorador: Resilience4j, un circuito por recurso
│       └── CachedProductRepository         # decorador: Caffeine, TTL + single-flight
├── config/
│   ├── SimilarProductsProperties    # configuración tipada (application.yml / variables de entorno)
│   └── ApplicationConfig            # composition root: el único sitio que conoce todas las piezas
└── SimilarProductsApplication.java
```

Las dependencias apuntan hacia dentro. El dominio y el caso de uso no tienen anotaciones de Spring: se crean en `ApplicationConfig` y se testean con un repositorio falso, sin contexto de Spring ni red.

La resiliencia y la caché son **decoradores del mismo puerto**:

```
GetSimilarProducts -> CachedProductRepository -> CircuitBreakerProductRepository -> HttpProductRepository -> mocks
```

## Decisiones técnicas

**Spring WebFlux (no bloqueante) en lugar de Spring MVC.** El escenario de carga tiene productos que tardan 1, 5 y 50 s. Con MVC cada petición ocupa un hilo mientras espera; con WebFlux (Netty + `WebClient`) un puñado de hilos atiende miles de peticiones en espera. Es la recomendación del enunciado.

**Respuesta parcial ante fallos.** Si un similar no existe (404), falla (500) o tarda más de `detail-timeout` (1,5 s), se omite y se devuelven los demás. `flatMapSequential` pide los detalles en paralelo pero los emite en el orden de entrada, así que se conserva el orden de similitud aunque lleguen desordenados. Los ids repetidos se eliminan (`uniqueItems` en el contrato).

**404 vs 503.** 404 solo si el producto pedido no existe. Si `similarids` falla o no responde, 503 con cuerpo JSON: devolver `[]` ocultaría el fallo y el frontend lo trataría como "sin similares".

**Timeouts en tres niveles.**
- `detail-timeout=1500ms`: lo máximo que una petición espera por un similar.
- `upstream-timeout=10s`: límite duro de cualquier llamada HTTP (`responseTimeout` de Reactor Netty).
- `upstream-connect-timeout=2s`: abrir la conexión TCP. Con el mock caído, el 503 llega en 2 s y no en 10.

**Caché en memoria con Caffeine (TTL, tamaño máximo y single-flight).** Se cachean `similarids` y los detalles (300 s) y también los "no encontrado" (60 s), con un `Expiry` por tipo de entrada. Los errores **no** se cachean. Máximo 10.000 entradas (LRU aproximado), para que la memoria no crezca sin límite.
- *Single-flight*: `AsyncCache` guarda el `CompletableFuture` en curso; si 200 usuarios piden el producto 1 a la vez, sale **una** llamada al mock.
- *Late completion*: la carga se suscribe por su cuenta (`toFuture()`) y cada petición la espera con `Mono.fromFuture(future, true)` (`suppressCancel`). Si una petición se rinde por timeout, no cancela la carga compartida y su resultado entra en caché. Por eso el 1000 (5 s) se omite la primera vez pero aparece después, en milisegundos.
- Los fallos se guardan como entrada con TTL 0: las peticiones que ya esperaban comparten el error, pero la siguiente vuelve a intentarlo.

**Circuit breaker por recurso con Resilience4j.** Ventana de las últimas 5 llamadas con umbral del 100 % = "5 fallos seguidos". Se abre 10 s y falla al instante; después deja pasar una única llamada de prueba. Es por recurso (`product:6`) y no global: un producto roto no corta los sanos. Solo `ProductUnavailableException` cuenta como fallo; un 404 cuenta como éxito.

**Excepciones de dominio sin stack trace.** Son resultados esperados que se lanzan miles de veces bajo carga; construir el stack trace sería coste sin valor.

**Una sola instancia.** La caché y los circuitos viven en memoria. Con varias réplicas cada una tendría su copia; en producción usaría Redis.

## Resultados de rendimiento (k6)

`docker compose run --rm k6 run scripts/test.js`, 200 usuarios por escenario, app en Docker sobre Windows 11, arrancando con la caché vacía:

| Métrica | Spring Boot | Python (referencia) |
|---|---|---|
| Duración media | **176 ms** | 161 ms |
| Mediana | **4,4 ms** | 3,2 ms |
| p90 | 982 ms | - |
| p95 | **1,50 s** | 1,50 s |
| Máximo | **1,56 s** | 1,56 s |
| Throughput | **247 req/s** | 256 req/s |

Las dos versiones quedan prácticamente igual porque el cuello de botella no es el lenguaje, sino el diseño: con la caché caliente casi todas las respuestas salen de memoria. El p95 de 1,5 s es el escenario `verySlow`: el producto 10000 tarda 50 s, supera el límite duro de 10 s, nunca entra en caché y cada petición espera el timeout de 1,5 s.

## Tests

```bash
./mvnw test   # 37 tests, sin red ni Docker
```

| Clase | Qué cubre |
|---|---|
| `GetSimilarProductsTest` | Orden de similitud, lista vacía, 404, fallo de `similarids`, similar inexistente / con error / lento (un test por escenario de k6), concurrencia, ids duplicados |
| `CachedProductRepositoryTest` | Acierto de caché, single-flight (200 llamadas -> 1), expiración por TTL, caché de "no encontrado", no cachear errores, late completion |
| `CircuitBreakerProductRepositoryTest` | Apertura tras N fallos, reinicio del contador, half-open con una sola prueba, aislamiento por recurso, 404 no es fallo |
| `HttpProductRepositoryTest` | HTTP real contra `MockWebServer`: ids numéricos -> string, 404 en texto plano y JSON, 500 vacío, campos que faltan, cuerpo no JSON, timeout, conexión rechazada, id codificado en la URL |
| `ProductControllerTest` | Forma del contrato, respuesta parcial, 404 y 503 con cuerpo de error |
| `SimilarProductsApplicationTest` | El contexto de Spring arranca y la caché es el decorador exterior |

El tiempo de la caché se inyecta (`Ticker` de Caffeine) para testear el TTL sin `sleep`.

## Configuración

Valores por defecto en `SimilarProductsProperties`. Todos se pueden cambiar con variables de entorno (*relaxed binding* de Spring):

| Variable | Defecto |
|---|---|
| `PRODUCTS_API_BASE_URL` | `http://localhost:3001` |
| `SIMILAR_PRODUCTS_DETAIL_TIMEOUT` | `1500ms` |
| `SIMILAR_PRODUCTS_UPSTREAM_TIMEOUT` | `10s` |
| `SIMILAR_PRODUCTS_UPSTREAM_CONNECT_TIMEOUT` | `2s` |
| `SIMILAR_PRODUCTS_UPSTREAM_MAX_CONNECTIONS` | `500` |
| `SIMILAR_PRODUCTS_CACHE_TTL` / `..._CACHE_NOT_FOUND_TTL` | `300s` / `60s` |
| `SIMILAR_PRODUCTS_CACHE_MAX_ENTRIES` | `10000` |
| `SIMILAR_PRODUCTS_CIRCUIT_FAILURE_THRESHOLD` / `..._CIRCUIT_RESET_TIMEOUT` | `5` / `10s` |

## Qué cambiaría en producción

- **Caché compartida (Redis)** para escalar a varias réplicas.
- **Observabilidad**: Actuator + Micrometer (aciertos de caché, estado de circuitos, latencia por dependencia) y logs estructurados.
- **Stale-while-revalidate**: servir el dato expirado mientras se refresca en segundo plano.
- **Fallar rápido con productos conocidos como lentos** (p. ej. el 10000) en lugar de esperar el timeout en cada petición.
- **Health checks** y límites de concurrencia por dependencia (bulkhead de Resilience4j).
