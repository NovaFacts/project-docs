# Sprint 4 Report — Anticipos, Penalidades & Eliminación de Pago

**Fecha:** 2026-06-29  
**Sprint:** 4  
**Repositorio backend:** `github.com/NovaFacts/project-backend`  
**Repositorio frontend:** `github.com/NovaFacts/project-frontend`

---

## 1. Objetivo

Implementar los módulos financieros de `anticipo` y `penalidad` conforme a `Esquema_BD.sql`, y erradicar permanentemente el módulo inventado `pago` que no formaba parte del modelo de datos canónico del sistema.

---

## 2. Cambios de Esquema de Base de Datos (V5)

### Migración: `V5__anticipos_penalidades_y_borrar_pagos.sql`

#### Tabla `anticipo` (creada)

| Columna | Tipo | Notas |
|---|---|---|
| `id` | INT IDENTITY PK | Auto-generado |
| `reserva_id` | INT NOT NULL | FK → `reserva(id)` |
| `usuario_id` | INT NOT NULL | FK → `usuario(id)` — extraído del SecurityContext, nunca del cliente |
| `monto` | DECIMAL(12,2) NOT NULL | Monto del anticipo |
| `fecha_pago` | DATE NOT NULL | Fecha efectiva del pago |
| `metodo_pago` | VARCHAR(80) | Opcional (transferencia, efectivo, tarjeta…) |
| `estado` | VARCHAR(50) NOT NULL | Ciclo: `registrado` → `aplicado` → `devuelto` |
| `registrado_en` | TIMESTAMP NOT NULL DEFAULT now() | Auditoría de creación |

#### Tabla `penalidad` (creada)

| Columna | Tipo | Notas |
|---|---|---|
| `id` | INT IDENTITY PK | Auto-generado |
| `reserva_id` | INT NOT NULL | FK → `reserva(id)` |
| `usuario_id` | INT NOT NULL | FK → `usuario(id)` — extraído del SecurityContext |
| `monto_segun_politica` | DECIMAL(12,2) NOT NULL | Penalidad calculada por la política (RF_09) |
| `monto_aprobado` | DECIMAL(12,2) NOT NULL | Penalidad finalmente aprobada |
| `monto_condonado` | DECIMAL(12,2) NOT NULL DEFAULT 0 | Diferencia condonada |
| `fecha_cancelacion` | DATE NOT NULL | Fecha de la cancelación que originó la penalidad |
| `motivo` | TEXT | Opcional |
| `calculado_en` | TIMESTAMP NOT NULL DEFAULT now() | Auditoría de creación |

#### Tabla `pago` (eliminada)

```sql
DROP TABLE IF EXISTS pago CASCADE;
```

La tabla `pago` fue creada en V1 como módulo inventado sin respaldo en `Esquema_BD.sql`. Se elimina con CASCADE para remover cualquier constraint FK residual.

---

## 3. Implementación de Seguridad: Referencia `usuario_id`

### Principio

Ambos módulos financieros (`anticipo` y `penalidad`) extraen el `usuario_id` exclusivamente del `SecurityContextHolder` de Spring Security. **Nunca se acepta un `usuario_id` en el cuerpo de la petición.**

### Patrón implementado

```java
// En AnticipoService.create() y PenalidadService.create()
String email = SecurityContextHolder.getContext().getAuthentication().getName();
User usuario = userRepository.findByUsername(email)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Usuario autenticado no encontrado en el sistema"));
```

Este patrón es idéntico al establecido en `ReservationService.create()` (línea 81–84), garantizando consistencia en toda la capa de servicios.

### RBAC en SecurityConfig

```java
.requestMatchers(HttpMethod.POST,   "/api/anticipos/**", "/api/penalidades/**")
    .hasAnyRole("ADMINISTRADOR", "CONTADOR", "AUXILIAR_CONTABLE")
.requestMatchers(HttpMethod.PUT,    "/api/anticipos/**", "/api/penalidades/**")
    .hasAnyRole("ADMINISTRADOR", "CONTADOR", "AUXILIAR_CONTABLE")
.requestMatchers(HttpMethod.DELETE, "/api/anticipos/**", "/api/penalidades/**")
    .hasAnyRole("ADMINISTRADOR", "CONTADOR", "AUXILIAR_CONTABLE")
```

Los roles se derivan del campo `nombre` en la tabla `rol` mediante la transformación definida en `UserDetailsServiceImpl`: `ROLE_` + `nombre.toUpperCase().replace(" ", "_")`.

| Nombre en BD | Autoridad Spring |
|---|---|
| `Administrador` | `ROLE_ADMINISTRADOR` |
| `Contador` | `ROLE_CONTADOR` |
| `Auxiliar contable` | `ROLE_AUXILIAR_CONTABLE` |

Los endpoints GET (`/api/anticipos`, `/api/penalidades`) quedan bajo `anyRequest().authenticated()`, accesibles a cualquier rol autenticado.

---

## 4. Endpoints REST

### Anticipos (`/api/anticipos`)

| Método | Ruta | Roles permitidos | Descripción |
|---|---|---|---|
| GET | `/api/anticipos` | Cualquier usuario autenticado | Listar todos los anticipos |
| GET | `/api/anticipos/{id}` | Cualquier usuario autenticado | Obtener anticipo por ID |
| GET | `/api/anticipos/by-reserva/{reservaId}` | Cualquier usuario autenticado | Anticipos de una reserva |
| POST | `/api/anticipos` | ADMINISTRADOR, CONTADOR, AUXILIAR_CONTABLE | Registrar anticipo |
| DELETE | `/api/anticipos/{id}` | ADMINISTRADOR, CONTADOR, AUXILIAR_CONTABLE | Eliminar (rechazado si estado=`aplicado`) |

### Penalidades (`/api/penalidades`)

| Método | Ruta | Roles permitidos | Descripción |
|---|---|---|---|
| GET | `/api/penalidades` | Cualquier usuario autenticado | Listar todas las penalidades |
| GET | `/api/penalidades/{id}` | Cualquier usuario autenticado | Obtener penalidad por ID |
| GET | `/api/penalidades/by-reserva/{reservaId}` | Cualquier usuario autenticado | Penalidades de una reserva |
| POST | `/api/penalidades` | ADMINISTRADOR, CONTADOR, AUXILIAR_CONTABLE | Registrar penalidad |
| DELETE | `/api/penalidades/{id}` | ADMINISTRADOR, CONTADOR, AUXILIAR_CONTABLE | Eliminar penalidad |

---

## 5. Validaciones Bean (DTO)

### `AnticipoRequest`
- `reservaId`: `@NotNull`, `@Positive` — ID de reserva obligatorio y mayor a cero
- `monto`: `@NotNull`, `@Positive`, `@Digits(integer=10, fraction=2)` — mayor a cero
- `fechaPago`: `@NotNull` — fecha obligatoria
- `metodoPago`: `@Size(max=80)` — opcional

### `PenalidadRequest`
- `reservaId`: `@NotNull`, `@Positive`
- `montoSegunPolitica`: `@NotNull`, `@Positive`, `@Digits(integer=10, fraction=2)`
- `montoAprobado`: `@NotNull`, `@PositiveOrZero`, `@Digits(integer=10, fraction=2)`
- `montoCondonado`: `@PositiveOrZero`, `@Digits(integer=10, fraction=2)` — opcional, default 0
- `fechaCancelacion`: `@NotNull`
- `motivo`: sin restricción de longitud — campo libre

Todos los mensajes de validación están en **español** y son procesados por el `GlobalExceptionHandler` existente, que devuelve HTTP 400 con el cuerpo `{"error": "<mensaje>"}`.

---

## 6. Cambios en el Dashboard

### Backend: `DashboardResponse.java`

Campos renombrados para reflejar la realidad del dominio:

| Campo anterior | Campo nuevo | Fuente |
|---|---|---|
| `totalPayments` | `totalAnticipos` | `anticipoRepository.count()` |
| `totalRevenue` | `montoTotalAnticipos` | `anticipoRepository.sumTotalMonto()` |

### Frontend: `types/dashboard.ts`

```typescript
export interface DashboardStats {
    totalAnticipos: number;
    montoTotalAnticipos: number;
    // ... demás campos sin cambio
}
```

### Frontend: `DashboardView.vue`

- Sección "Pagos recientes" eliminada.
- Sección "Anticipos recientes" agregada con tabla: Reserva | Monto | Método | Estado | Fecha pago.
- Tarjeta "Pagos registrados" → "Anticipos registrados" (`totalAnticipos`).
- Tarjeta "Ingresos totales" → "Monto total anticipos" (`montoTotalAnticipos`).

---

## 7. Correcciones al Suite de Tests

### Archivos eliminados
- `PaymentControllerTest.java` — 5 tests reemplazados por `AnticipoControllerTest.java`

### Archivos nuevos
- `AnticipoControllerTest.java` — 7 tests cubriendo: creación exitosa, validación de campos, reserva inexistente (404), GET por ID, GET por reserva, DELETE exitoso, DELETE de anticipo `aplicado` (409).

### Archivos modificados

#### `DashboardControllerTest.java`
- Eliminadas importaciones de `Payment`, `PaymentMethod`, `PaymentRepository`
- Campo `PaymentRepository paymentRepository` → `AnticipoRepository anticipoRepository` + `PenalidadRepository penalidadRepository`
- `setUp()`: `paymentRepository.deleteAll()` → `anticipoRepository.deleteAll(); penalidadRepository.deleteAll()`
- Test `getDashboard_withSeedData_returnsCorrectAggregates`: reemplaza creación de `Payment` con `Anticipo`; aserciones `$.totalPayments`/`$.totalRevenue` → `$.totalAnticipos`/`$.montoTotalAnticipos`
- Test `getDashboard_emptyDatabase_returnsAllZeros`: mismas aserciones actualizadas

#### `InvoiceControllerTest.java`
- Eliminada dependencia de `PaymentRepository`
- `setUp()`: `paymentRepository.deleteAll()` → `anticipoRepository.deleteAll(); penalidadRepository.deleteAll()`
- Test `invoice_becomes_paid_via_payment_creation` → **renombrado y reescrito** como `get_invoice_by_reservation_returns_200`: verifica `GET /api/invoices/by-reservation/{id}` retorna 200 con estado PENDING
- Test `delete_paid_invoice_returns_409`: ya no llama a `/api/payments`; marca la factura como PAID directamente vía `invoiceRepository.save()` con `setStatus(InvoiceStatus.PAID)`, luego verifica que `DELETE /api/invoices/{id}` retorna 409

#### `ReservationControllerTest.java`
- Añadidos `@Autowired AnticipoRepository` y `@Autowired PenalidadRepository`
- `setUp()`: `anticipoRepository.deleteAll(); penalidadRepository.deleteAll()` al inicio para garantizar integridad FK con H2

#### `PropertyControllerTest.java`
- Añadidos `@Autowired AnticipoRepository` y `@Autowired PenalidadRepository`
- `setUp()`: mismo patrón de limpieza antes de `reservationRepository.deleteAll()`

### Motivo técnico de los cambios en setUp

El perfil de test usa H2 en modo `create-drop` con Flyway deshabilitado. JPA crea tablas con FK nativas en H2. Como `anticipo.reserva_id` y `penalidad.reserva_id` referencian `reserva.id`, cualquier intento de `reservationRepository.deleteAll()` con anticipos/penalidades en la BD falla con violación de FK. La solución adoptada es limpiar anticipos y penalidades en los `@BeforeEach` de todos los test que borren reservas.

---

## 8. Checklist de Verificación

| Ítem | Estado |
|---|---|
| `./mvnw clean test` → BUILD SUCCESS | ✅ 56 tests, 0 fallos |
| Backend compila sin warnings | ✅ |
| Frontend `npm run build` exitoso | ✅ 137 módulos, 0 errores |
| Módulo `pago` completamente eliminado (source + test + frontend) | ✅ |
| Módulo `anticipo` implementado con RBAC | ✅ |
| Módulo `penalidad` implementado con RBAC | ✅ |
| `usuario_id` extraído del SecurityContext (no del request body) | ✅ |
| Bean Validation en español en todos los DTOs | ✅ |
| GlobalExceptionHandler sin modificaciones (ya cubría los casos) | ✅ |
| Dashboard actualizado (stats + lista reciente) | ✅ |
| AppNav con guardas para roles financieros | ✅ |
| Router con `meta: { requiresFinancial: true }` | ✅ |
| Sin código muerto, imports sin usar, ni TODOs | ✅ |
| `factura`, `nota_credito`, `devolucion` sin tocar (Sprint 5) | ✅ |

---

## 9. Archivos del Sprint

### Backend — Nuevos

```
src/main/resources/db/migration/V5__anticipos_penalidades_y_borrar_pagos.sql
src/main/java/com/novafacts/backend/anticipo/entity/Anticipo.java
src/main/java/com/novafacts/backend/anticipo/repository/AnticipoRepository.java
src/main/java/com/novafacts/backend/anticipo/dto/AnticipoRequest.java
src/main/java/com/novafacts/backend/anticipo/dto/AnticipoResponse.java
src/main/java/com/novafacts/backend/anticipo/service/AnticipoService.java
src/main/java/com/novafacts/backend/anticipo/controller/AnticipoController.java
src/main/java/com/novafacts/backend/penalidad/entity/Penalidad.java
src/main/java/com/novafacts/backend/penalidad/repository/PenalidadRepository.java
src/main/java/com/novafacts/backend/penalidad/dto/PenalidadRequest.java
src/main/java/com/novafacts/backend/penalidad/dto/PenalidadResponse.java
src/main/java/com/novafacts/backend/penalidad/service/PenalidadService.java
src/main/java/com/novafacts/backend/penalidad/controller/PenalidadController.java
src/test/java/com/novafacts/backend/anticipo/AnticipoControllerTest.java
```

### Backend — Modificados

```
src/main/java/com/novafacts/backend/config/SecurityConfig.java
src/main/java/com/novafacts/backend/dashboard/dto/DashboardResponse.java
src/main/java/com/novafacts/backend/dashboard/service/DashboardService.java
src/test/java/com/novafacts/backend/dashboard/DashboardControllerTest.java
src/test/java/com/novafacts/backend/invoice/InvoiceControllerTest.java
src/test/java/com/novafacts/backend/reservation/ReservationControllerTest.java
src/test/java/com/novafacts/backend/property/PropertyControllerTest.java
```

### Backend — Eliminados

```
src/main/java/com/novafacts/backend/payment/ (directorio completo)
src/test/java/com/novafacts/backend/payment/PaymentControllerTest.java
```

### Frontend — Nuevos

```
src/types/anticipo.ts
src/types/penalidad.ts
src/services/anticipoService.ts
src/services/penalidadService.ts
src/views/AnticiposView.vue
src/views/PenalidadesView.vue
```

### Frontend — Modificados

```
src/types/dashboard.ts
src/router/index.js
src/components/AppNav.vue
src/views/DashboardView.vue
```

### Frontend — Eliminados

```
src/views/PaymentsView.vue
src/services/paymentService.ts
src/types/payment.ts
```

### Documentación

```
project-docs/Documentation/project/Sprint_4_Report.md
```
