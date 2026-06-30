# Invoice Backend Implementation Report

**Project:** NovaFacts — Financial Management for Short-Term Rental Bookings  
**Module:** Invoice (factura)  
**Date:** 2026-06-27  
**Build result:** `./mvnw clean test` → 32 tests, 0 failures — **BUILD SUCCESS**

---

## Executive Summary

The Invoice backend module has been implemented following the exact architectural template established by the Guest, Property, and Reservation modules. The module exposes six REST endpoints under `/api/invoices`, all protected by the existing JWT authentication filter. Monetary values (subtotal, 19% IVA, total) are calculated entirely on the server from reservation and property data — the client supplies only the `reservationId`. All existing modules remain untouched. The module compiles cleanly alongside all 32 pre-existing tests.

---

## Files Created

| File | Purpose |
|---|---|
| `invoice/entity/InvoiceStatus.java` | Enum: PENDING / PAID / CANCELLED |
| `invoice/entity/Invoice.java` | JPA entity → table `factura` |
| `invoice/repository/InvoiceRepository.java` | JpaRepository + 2 derived queries |
| `invoice/dto/CreateInvoiceRequest.java` | Validated request DTO (only `reservationId`) |
| `invoice/dto/InvoiceResponse.java` | Read-only response DTO (all-arg constructor, getters only) |
| `invoice/service/InvoiceService.java` | All business logic and amount calculation |
| `invoice/controller/InvoiceController.java` | 6 REST endpoints, delegates to service |

**Total: 7 new files. 0 existing files modified.**

---

## Files Modified

**None.** `InvoiceService` injects `ReservationRepository` and `PropertyRepository` as Spring beans with no changes to those repositories. `SecurityConfig` automatically protects `/api/invoices/**` via `anyRequest().authenticated()`. `GlobalExceptionHandler` already handles all exception types thrown by the new service.

---

## Database Schema

Hibernate generates the following DDL via `ddl-auto=update`:

```sql
CREATE TABLE factura (
    id          BIGSERIAL        PRIMARY KEY,
    reserva_id  BIGINT           NOT NULL UNIQUE,
    subtotal    NUMERIC(15, 2)   NOT NULL,
    iva         NUMERIC(15, 2)   NOT NULL,
    total       NUMERIC(15, 2)   NOT NULL,
    estado      VARCHAR(20)      NOT NULL,
    creado_en   TIMESTAMP        NOT NULL
);
```

**Notes:**
- `reserva_id` has a `UNIQUE` constraint (`unique = true` on `@Column`). This enforces at the DB level that at most one invoice can exist per reservation, complementing the service-level `existsByReservationId` check.
- All monetary fields use `NUMERIC(15, 2)` matching `propiedad.precio_por_noche`, keeping precision consistent across the financial data model.
- `estado` stores the enum constant name as a VARCHAR string (`EnumType.STRING`), not an ordinal. Adding new status constants will never corrupt existing rows.
- `creado_en` is set once by `@PrePersist` and declared `updatable = false`.
- No `FOREIGN KEY` constraint is generated (no `@ManyToOne` is used). This is by design and consistent with all other modules.

---

## Implemented Endpoints

Base path: `/api/invoices`  
Security: all endpoints require a valid JWT (`Authorization: Bearer <token>`).

| Method | Path | Success | Error cases |
|---|---|---|---|
| GET | `/api/invoices` | 200 | — |
| GET | `/api/invoices/{id}` | 200 | 404 if not found |
| POST | `/api/invoices` | 201 | 400 validation, 400 cancelled reservation, 404 not found, 409 duplicate |
| PUT | `/api/invoices/{id}/pay` | 200 | 404 if not found, 409 if not PENDING |
| PUT | `/api/invoices/{id}/cancel` | 200 | 404 if not found, 409 if not PENDING |
| DELETE | `/api/invoices/{id}` | 204 | 404 if not found |

All error responses use the `{"error": "..."}` envelope from `GlobalExceptionHandler`.

---

## Business Rules Implemented

### 1. Reservation must exist
`reservationRepository.findById(reservationId)` — loading the entity is necessary to access `checkIn`, `checkOut`, and `propertyId`.  
On failure: **404** `"Reserva no encontrada"`

### 2. Reservation must not be cancelled
`reservation.getStatus() == ReservationStatus.CANCELLED` — checked before the duplicate guard.  
On failure: **400** `"No se puede facturar una reserva cancelada"`

### 3. No duplicate invoice per reservation
`invoiceRepository.existsByReservationId(reservationId)` — checked after the status guard to avoid leaking conflict info on invalid input.  
On failure: **409** `"Ya existe una factura para esta reserva"`

### 4. Property must exist
`propertyRepository.findById(reservation.getPropertyId())` — required to read `pricePerNight`.  
On failure: **404** `"Propiedad no encontrada"`

### 5. Amount calculation (server-side only)

```
nights   = ChronoUnit.DAYS.between(checkIn, checkOut)
subtotal = pricePerNight × nights          [scale 2, HALF_UP]
tax      = subtotal × 0.19                 [scale 2, HALF_UP]
total    = subtotal + total
```

All arithmetic uses `BigDecimal`. The IVA rate is a package-level constant `new BigDecimal("0.19")` — not a floating-point literal. Rounding is applied at each step to `scale = 2` using `RoundingMode.HALF_UP`.

### 6. New invoices start as PENDING
`InvoiceStatus.PENDING` is hardcoded on creation. The client cannot supply a status at creation time — `CreateInvoiceRequest` has no `status` field.

### 7. Status transitions are guarded
`pay()` and `cancel()` only accept invoices in `PENDING` status.  
On failure: **409** `"Solo se pueden pagar facturas pendientes"` / `"Solo se pueden cancelar facturas pendientes"`  
This prevents: paying a cancelled invoice, cancelling a paid invoice, double-paying.

---

## Validation Rules (DTO layer)

| Field | Constraint | Message |
|---|---|---|
| `reservationId` | `@NotNull` | "El identificador de la reserva es obligatorio" |
| `reservationId` | `@Positive` | "El identificador de la reserva debe ser mayor a cero" |

Business-rule validation (reservation existence, status, duplicate, property existence, calculation) is performed in the service layer because it requires cross-entity data unavailable at deserialization time.

---

## Architectural Decisions

### 1. `reserva_id UNIQUE` at the DB level
`@Column(unique = true)` on `reservationId` adds a DB-level uniqueness constraint as defense-in-depth behind the service-level `existsByReservationId` check. The same two-layer pattern is used for `nombre` in `Property`.

### 2. `tax` Java field maps to `iva` column
The Java field is named `tax` (English, consistent with other Java identifiers in the project). The DB column is named `iva` (Spanish, consistent with the domain language of the schema). Both the entity and the response DTO expose the field as `tax` to the API, which is intentional — the API uses English JSON keys throughout.

### 3. Status transitions validated in the service, not the controller
`pay()` and `cancel()` reject non-PENDING invoices with `409 CONFLICT`. This rule lives in the service (not controller or repository) following the project's pattern where all business logic belongs to the service layer.

### 4. `IVA_RATE` is a `static final BigDecimal` constant
Using `new BigDecimal("0.19")` (not `0.19d`) prevents the floating-point imprecision that `BigDecimal.valueOf(0.19)` would carry into calculations. Declared as a class constant for readability and to ensure a single source of truth for the rate.

### 5. No request body on `pay` and `cancel` endpoints
These are action endpoints — the only input they need is the invoice ID in the path. Adding a request body would be misleading (it would imply the client can supply data for the transition). This matches the idiom for resource state transitions in REST.

### 6. `findByReservationId` returns `Optional<Invoice>`
There is at most one invoice per reservation (enforced by the unique constraint). `Optional` is the semantically correct return type and opens the door for future use cases (e.g., "find the invoice for this reservation" lookup from a reservation detail screen).

---

## Assumptions Made

1. **COMPLETED reservations can be invoiced.** The spec only blocks CANCELLED reservations. A completed reservation has already occurred; billing after the fact is a normal scenario.

2. **`subtotal` is computed as `pricePerNight × nights` without long-stay discounts.** The existing `InvoiceCalculator` in the `booking` package applies a 10% discount for stays ≥ 7 nights. That class is a plain POJO from an earlier scaffold, not connected to persistence or this module. The spec does not mention the discount for this implementation. Applying it would require a business decision; it is left as a future improvement.

3. **Deleting a PAID or CANCELLED invoice is allowed.** The spec only says DELETE returns 204 or 404. No additional status guard is applied on delete.

4. **No cascade between invoice and reservation status.** Cancelling an invoice via `PUT /api/invoices/{id}/cancel` does not automatically cancel the associated reservation. These are independent state machines; coordinating them is out of scope.

---

## Example curl Requests

Replace `<TOKEN>` with the JWT from `POST /api/auth/login`.  
These examples assume reservation ID 1 exists (CONFIRMED) for a property priced at COP 350,000/night, checked in Aug 1 and out Aug 7 (6 nights).

```bash
# 1 — Create invoice (201)
curl -s -X POST http://localhost:8082/api/invoices \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"reservationId": 1}' | jq .
# {
#   "id": 1,
#   "reservationId": 1,
#   "subtotal": 2100000.00,    (350000 × 6)
#   "tax":       399000.00,    (2100000 × 0.19, HALF_UP)
#   "total":    2499000.00,
#   "status": "PENDING",
#   "createdAt": "..."
# }

# 2 — List all invoices (200)
curl -s http://localhost:8082/api/invoices \
  -H "Authorization: Bearer <TOKEN>" | jq .

# 3 — Get invoice by ID (200)
curl -s http://localhost:8082/api/invoices/1 \
  -H "Authorization: Bearer <TOKEN>" | jq .

# 4 — Non-existent invoice (404)
curl -s http://localhost:8082/api/invoices/999 \
  -H "Authorization: Bearer <TOKEN>" | jq .
# { "error": "Factura no encontrada" }

# 5 — Duplicate invoice for same reservation (409)
curl -s -X POST http://localhost:8082/api/invoices \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"reservationId": 1}' | jq .
# { "error": "Ya existe una factura para esta reserva" }

# 6 — Invoice for a cancelled reservation (400)
curl -s -X POST http://localhost:8082/api/invoices \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"reservationId": 2}' | jq .  # reservation 2 is CANCELLED
# { "error": "No se puede facturar una reserva cancelada" }

# 7 — Mark invoice as paid (200)
curl -s -X PUT http://localhost:8082/api/invoices/1/pay \
  -H "Authorization: Bearer <TOKEN>" | jq .
# { ..., "status": "PAID", ... }

# 8 — Try to pay an already-paid invoice (409)
curl -s -X PUT http://localhost:8082/api/invoices/1/pay \
  -H "Authorization: Bearer <TOKEN>" | jq .
# { "error": "Solo se pueden pagar facturas pendientes" }

# 9 — Cancel an invoice (200)
curl -s -X PUT http://localhost:8082/api/invoices/2/cancel \
  -H "Authorization: Bearer <TOKEN>" | jq .  # invoice 2 is PENDING
# { ..., "status": "CANCELLED", ... }

# 10 — Try to cancel a paid invoice (409)
curl -s -X PUT http://localhost:8082/api/invoices/1/cancel \
  -H "Authorization: Bearer <TOKEN>" | jq .
# { "error": "Solo se pueden cancelar facturas pendientes" }

# 11 — Delete invoice (204)
curl -s -X DELETE http://localhost:8082/api/invoices/3 \
  -H "Authorization: Bearer <TOKEN>" -v

# 12 — Validation error — missing reservationId (400)
curl -s -X POST http://localhost:8082/api/invoices \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{}' | jq .
# { "error": "reservationId: El identificador de la reserva es obligatorio" }

# 13 — Unauthenticated (401)
curl -s http://localhost:8082/api/invoices
# (HTTP 401, empty body)
```

---

## Future Improvements

1. **Long-stay discount.** Apply the 10% discount from `InvoiceCalculator` for stays ≥ 7 nights. This would add a `discount` column to `factura` and a `discountedSubtotal` to the response. Requires a business decision.

2. **Configurable IVA rate.** The 19% rate is hardcoded as a class constant. For a production system, this should come from an application property or a `configuracion` table to allow rate changes without redeployment.

3. **Reservation status → COMPLETED on payment.** When an invoice is marked PAID, `ReservationService.complete(reservationId)` could be called to transition the reservation to COMPLETED. This cross-module coordination is currently not implemented.

4. **Pagination on `findAll()`.** Consistent with all other modules, `findAll()` returns unbounded results. Replace with `Pageable` before production load.

5. **Invoice number.** Production invoices require a human-readable sequential invoice number (e.g., `FAC-2026-0001`). The current `id` is a technical surrogate key, not suitable for legal invoicing.

---

## Build Result

```
[INFO] Compiling 47 source files with javac [release 21] to target/classes
[INFO] Tests run: 32, Failures: 0, Errors: 0, Skipped: 0
[INFO] Analyzed bundle '' with 42 classes
[INFO] BUILD SUCCESS
[INFO] Total time:  5.182 s
```

Source files: 47 (was 40 — 7 new invoice files added)  
JaCoCo classes: 42 (was 36 — 6 new classes tracked: entity, repository, 2 DTOs, service, controller)  
Tests: 32 passing, 0 failures, 0 errors

---

## Final Report

### Created Files
7 new files in `com.novafacts.backend.invoice`:
`entity/InvoiceStatus.java`, `entity/Invoice.java`, `repository/InvoiceRepository.java`, `dto/CreateInvoiceRequest.java`, `dto/InvoiceResponse.java`, `service/InvoiceService.java`, `controller/InvoiceController.java`

### Modified Files
None.

### Endpoints
`GET /api/invoices`, `GET /api/invoices/{id}`, `POST /api/invoices`, `PUT /api/invoices/{id}/pay`, `PUT /api/invoices/{id}/cancel`, `DELETE /api/invoices/{id}`

### Business Rules Implemented
1. Reservation must exist (404)
2. Reservation must not be CANCELLED (400)
3. No duplicate invoice per reservation (409)
4. Property must exist (404)
5. Server-side amount calculation: `subtotal = pricePerNight × nights`, `tax = subtotal × 0.19` (HALF_UP, scale 2), `total = subtotal + tax`
6. New invoices always start as PENDING
7. Only PENDING invoices can be paid or cancelled (409 otherwise)

### Assumptions
- COMPLETED reservations can be invoiced
- No long-stay discount applied
- Deleting any invoice regardless of status is allowed
- Invoice cancellation does not cascade to the reservation

### Build Result
`./mvnw clean test` → **32 tests, 0 failures — BUILD SUCCESS**

### Architectural Decisions
- `reserva_id UNIQUE` at DB level as defense-in-depth
- `IVA_RATE = new BigDecimal("0.19")` as a class constant (not a floating-point literal)
- `tax` in Java / `iva` in DB — English API, Spanish schema
- Status transitions validated in service layer
- No request body on pay/cancel action endpoints
