# Sprint 5 Report — Facturación, Notas de Crédito y Devoluciones

**Fecha:** 2026-06-29  
**Resultado backend:** `./mvnw clean test` → **69 tests, 0 failures, BUILD SUCCESS**  
**Resultado frontend:** `npm run build` → **143 modules, ✓ built in ~400ms**

---

## Objetivo

Implementar los módulos financieros finales del sistema NovaFacts: `factura`, `nota_credito` y `devolucion`, con RBAC estricto, cálculo server-side de totales y extracción de `usuario_id` desde el SecurityContext.

---

## Fase 1 — Análisis previo

Se documentaron las decisiones clave antes de implementar:

- Mantener `invoice/entity/InvoiceStatus.java` (PENDING/PAID/CANCELLED) para compatibilidad con DashboardService y para ser reutilizada por `Factura`.
- Eliminar todos los demás archivos del módulo `invoice/` (entidad con FK de tipo Long plano, incompatible con el nuevo esquema).
- El `DashboardControllerTest` debía reescribirse para usar `Factura` con `@ManyToOne Reservation` real (no IDs ficticios).
- Agregar columna `recargo_penalidad` a `factura` aunque no estaba en `Esquema_BD.sql`, requerido por la fórmula de total.
- Orden de eliminación en setUp de tests: `devolucion → notaCredito → anticipo → penalidad → factura → reservation → politica → property → temporada → canal → user → rol`.

---

## Fase 2 — Migración Flyway (V6)

**Archivo:** `src/main/resources/db/migration/V6__facturas_notas_y_devoluciones.sql`

- Recrea tabla `factura` con columnas: `subtotal`, `descuento_anticipo`, `recargo_penalidad`, `impuestos`, `total`, `estado`, `url_documento`, `emitida_en`. FK a `reserva` y `usuario`.
- Crea tabla `nota_credito` con FK a `factura` y `usuario`.
- Crea tabla `devolucion` con FK a `reserva`, `anticipo` y `usuario`.

---

## Fase 3 — Backend Spring Boot

### Módulo `factura/`

| Clase | Descripción |
|---|---|
| `Factura.java` | `@Entity` con `@ManyToOne Reservation` y `@ManyToOne User`. `@PrePersist` inicializa nulls a ZERO. |
| `FacturaRepository.java` | `JpaRepository`. `findByReservaId` y `existsByReservaId` con `@Query` JPQL. `countByEstado`. |
| `FacturaRequest.java` | Validación con `@NotNull`, `@DecimalMin`, `@Digits`. Mensajes en español. |
| `FacturaResponse.java` | DTO inmutable. |
| `FacturaService.java` | Fórmula: `total = subtotal − descuentoAnticipo + recargoPenalidad + impuestos`. Usuario extraído de SecurityContext. Genera `"FAC-" + UUID prefix`. Estado inicial: PENDING. |
| `FacturaController.java` | `GET /`, `GET /{id}`, `GET /by-reserva/{id}`, `POST` (201), `PUT /{id}/emitir`, `PUT /{id}/anular`, `DELETE /{id}` (204). |

### Módulo `notacredito/`

| Clase | Descripción |
|---|---|
| `NotaCredito.java` | `@Entity` con FK a `Factura` y `User`. Genera `"NC-" + UUID prefix`. |
| `NotaCreditoService.java` | Usuario extraído de SecurityContext. |
| `NotaCreditoController.java` | `GET /`, `GET /{id}`, `GET /by-factura/{id}`, `POST` (201), `DELETE /{id}` (204). |

### Módulo `devolucion/`

| Clase | Descripción |
|---|---|
| `Devolucion.java` | `@Entity` con FK a `Reservation`, `Anticipo` y `User`. Estado: `pendiente/procesada/rechazada`. |
| `DevolucionService.java` | `create()` valida que el anticipo pertenece a la reserva; marca `anticipo.estado = "devuelto"`. `procesar()` registra `procesadaEn`. |
| `DevolucionController.java` | `GET /`, `GET /{id}`, `GET /by-reserva/{id}`, `POST` (201), `PUT /{id}/procesar`, `PUT /{id}/rechazar`, `DELETE /{id}` (204). |

### RBAC actualizado (`SecurityConfig.java`)

```
POST/PUT/DELETE /api/facturas/**      → ADMINISTRADOR, CONTADOR
POST/PUT/DELETE /api/notas-credito/** → ADMINISTRADOR, CONTADOR
POST/PUT/DELETE /api/devoluciones/**  → ADMINISTRADOR, CONTADOR
POST/PUT/DELETE /api/anticipos/**     → ADMINISTRADOR, CONTADOR, AUXILIAR_CONTABLE
POST/PUT/DELETE /api/penalidades/**   → ADMINISTRADOR, CONTADOR, AUXILIAR_CONTABLE
```

### DashboardService actualizado

Reemplaza `InvoiceRepository.countByStatus()` por `FacturaRepository.countByEstado()`.

### Tests eliminados / creados

| Acción | Archivo |
|---|---|
| Eliminado | `InvoiceControllerTest.java` |
| Creado | `FacturaControllerTest.java` — 9 tests |
| Creado | `NotaCreditoControllerTest.java` — 4 tests |
| Creado | `DevolucionControllerTest.java` — 5 tests |
| Reescrito | `DashboardControllerTest.java` — usa `Factura`/`FacturaRepository` |
| Actualizado setUp | `AnticipoControllerTest`, `ReservationControllerTest`, `PropertyControllerTest` |

---

## Fase 4 — Frontend Vue 3

### Tipos TypeScript nuevos

- `src/types/factura.ts` — `FacturaEstado`, `Factura`, `CreateFacturaRequest`
- `src/types/notaCredito.ts` — `NotaCredito`, `CreateNotaCreditoRequest`
- `src/types/devolucion.ts` — `DevolucionEstado`, `Devolucion`, `CreateDevolucionRequest`

### Servicios nuevos

- `src/services/facturaService.ts` — getFacturas, getFactura, getFacturaByReserva, createFactura, emitirFactura, anularFactura, deleteFactura
- `src/services/notaCreditoService.ts` — CRUD + getNotasByFactura
- `src/services/devolucionService.ts` — CRUD + procesarDevolucion + rechazarDevolucion

### Vistas nuevas

| Vista | Funcionalidad |
|---|---|
| `FacturasView.vue` | Tabla + modal de creación con selección de reserva, auto-cálculo de `descuentoAnticipo` (suma anticipos) y `recargoPenalidad` (suma penalidades), sugerencia de IVA 19%, total en tiempo real. Acciones: emitir, anular, eliminar. |
| `NotasCreditoView.vue` | Tabla + modal (selección de factura, monto, motivo). Eliminar. |
| `DevolucionesView.vue` | Tabla + modal con reserva → anticipo en cascada. Acciones: procesar, rechazar, eliminar. |

### Eliminados

- `src/views/InvoicesView.vue`
- `src/services/invoiceService.ts`
- `src/types/invoice.ts`

### Router actualizado

- Rutas `/facturas`, `/notas-credito`, `/devoluciones` con `meta: { requiresBilling: true }` (solo Administrador y Contador).
- Guard `requiresBilling` aplicado en `beforeEach`.

### AppNav actualizado

- Links Facturas / Notas Crédito / Devoluciones visibles para `esFacturador` (Administrador o Contador).
- Links Anticipos / Penalidades siguen visibles para `esFinanciero` (incluye Auxiliar contable).

### DashboardView actualizado

- Importa `getFacturas` de `facturaService`.
- Sección "Facturas recientes" muestra `numeroFactura`, `reservaId`, `total`, `estado`, `emitidaEn`.
- Tipos `Invoice`/`InvoiceStatus` reemplazados por `Factura`/`FacturaEstado`.

---

## Invariantes de negocio implementados

1. **Cálculo server-side:** `total = subtotal − descuento_anticipo + recargo_penalidad + impuestos`. El frontend propone valores; el backend recalcula siempre.
2. **No se exponen entidades JPA:** todos los endpoints usan DTOs.
3. **Usuario desde SecurityContext:** `SecurityContextHolder.getContext().getAuthentication().getName()` → lookup en `UserRepository`.
4. **RBAC estricto:** operaciones de escritura en facturación requieren `ROLE_ADMINISTRADOR` o `ROLE_CONTADOR`. AUXILIAR_CONTABLE no tiene acceso.
5. **No eliminar factura emitida (PAID):** el servicio lanza 409 Conflict.
6. **Devolucion valida anticipo:** verifica que el anticipo pertenece a la reserva antes de crear la devolución; marca el anticipo como `"devuelto"`.
