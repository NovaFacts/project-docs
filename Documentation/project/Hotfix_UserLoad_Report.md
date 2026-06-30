# Hotfix Sprint — User Load Readiness Report
**Date:** 2026-06-29
**Branch:** main
**Engineer:** Phase 2 Hotfix Sprint (Claude Sonnet 4.6)
**Source audit:** `Production_Readiness_Audit_v5.md`
**Test result:** 69 / 69 — BUILD SUCCESS

---

## Overview

This sprint resolved all 4 issues classified as "BEFORE FIRST USER LOAD" in the v5 audit.
Patches touch 1 SQL migration, 5 repository files, 1 service file, and 3 controller files.
No frontend files were modified.

---

## Issues Resolved

| Issue | Severity | File(s) changed |
|---|---|---|
| HIGH-8 — Missing FK on `reserva.propiedad_id` | HIGH | `V9__add_fk_reserva_propiedad.sql` (new) |
| CRITICAL-4 — Reservation overlap not atomic under concurrency | CRITICAL | `PropertyRepository.java`, `ReservationService.java` |
| HIGH-9 — `ReservationService.delete()` no FK children guard | HIGH | `AnticipoRepository.java`, `PenalidadRepository.java`, `DevolucionRepository.java`, `ReservationService.java` |
| MEDIUM-15 — No pagination size cap | MEDIUM | `ReservationController.java`, `FacturaController.java`, `UserController.java` |

---

## Detailed Patch Notes

---

### HIGH-8 — V9 Migration: FK Constraint on `reserva.propiedad_id`

**File:** `db/migration/V9__add_fk_reserva_propiedad.sql` (new)

**Root cause:** `reserva.propiedad_id BIGINT NOT NULL` was declared in V1 with only an index.
No `FOREIGN KEY` constraint was ever added. PostgreSQL had no way to enforce referential integrity
at the database level — deleting a `propiedad` row while `reserva` rows referenced it would
silently produce orphan reservations.

**Fix:**
```sql
ALTER TABLE reserva
    ADD CONSTRAINT fk_reserva_propiedad
        FOREIGN KEY (propiedad_id) REFERENCES propiedad(id);
```

**Pre-condition:** All existing `reserva.propiedad_id` values must refer to a `propiedad` row.
In normal operation (data seeded by `DevelopmentDataSeeder`), this is guaranteed.
If orphan rows exist, clean them up before migrating:
```sql
DELETE FROM reserva WHERE propiedad_id NOT IN (SELECT id FROM propiedad);
```

**Test impact:** None. Tests use H2 with `flyway.enabled=false` and `create-drop`; V9 is never
applied to the test schema.

---

### CRITICAL-4 — Pessimistic Locking for Reservation Overlap Serialization

**Files:** `property/repository/PropertyRepository.java`, `reservation/service/ReservationService.java`

**Root cause:** `ReservationService.create()` and `update()` performed an availability overlap
check (`reservationRepository.existsByPropertyId...`) followed by `reservationRepository.save()`.
Under concurrent load, two requests for the same property could both read "no overlap" before
either committed, resulting in a double-booking.

**Design decision — property-row lock:**
Rather than locking the `reserva` table (which would serialize all reservation operations) or
using optimistic locking with retries (which requires client-side retry logic), the fix uses
a `SELECT ... FOR UPDATE` on the `propiedad` row that is being reserved. This row acts as a
coordination point: all concurrent reservation transactions for the **same** property serialize
through it, while transactions for different properties remain unaffected.

**New method in `PropertyRepository`:**
```java
@Query("SELECT p FROM Property p WHERE p.id = :id")
@Lock(LockModeType.PESSIMISTIC_WRITE)
Optional<Property> findByIdForUpdate(@Param("id") Long id);
```

**Call site (inside `@Transactional` in `ReservationService`):**
```java
// CRITICAL-4: acquire row-level exclusive lock before the overlap check
lockPropertyOrThrow(request.getPropertyId());   // SELECT ... FOR UPDATE

// overlap check (now inside the lock scope)
if (reservationRepository.existsByPropertyIdAndCheckInBeforeAndCheckOutAfterAndStatus(...)) {
    throw new ResponseStatusException(HttpStatus.CONFLICT, "La propiedad ya tiene ...");
}

reservationRepository.save(reservation);   // lock released on transaction commit
```

`lockPropertyOrThrow()` replaces the old `validatePropertyExists()` helper — it combines
existence validation with lock acquisition in one query.

**Deadlock analysis:**

| Scenario | Risk | Explanation |
|---|---|---|
| Two concurrent `create()` for **same** property | None | Transaction B blocks on `SELECT FOR UPDATE` until A commits. No circular wait. |
| Two concurrent `create()` for **different** properties | None | They lock different rows — zero intersection. |
| `create()` + `PropertyService.delete()` on same property | Low | `delete()` issues `UPDATE SET activa=false`, which takes an implicit write lock. One waits for the other; no cycle. |
| `update()` changing property from P1 → P2 | None | Only P2 (the new property) is locked. No dual-property locking. |

Deadlock requires a **circular dependency**: A waits for B while B waits for A.
Because each reservation transaction locks exactly one `propiedad` row and acquires no
other lock afterward, no cycle can form. PostgreSQL's deadlock detector (~1 s timeout)
would handle any pathological scenario regardless.

**Latency impact:** Under concurrent requests for the **same** property, requests
serialize at the DB level. This is expected behavior (and the correct outcome).
Requests for different properties are unaffected.

---

### HIGH-9 — `ReservationService.delete()`: Explicit FK Children Guard

**Files:** `anticipo/repository/AnticipoRepository.java`, `penalidad/repository/PenalidadRepository.java`,
`devolucion/repository/DevolucionRepository.java`, `reservation/service/ReservationService.java`

**Root cause:** `ReservationService.delete()` called `reservationRepository.delete(reservation)`
directly. When a reservation had associated `anticipo`, `penalidad`, `factura`, or `devolucion`
rows, PostgreSQL raised a FK violation, which Spring translated to a `DataIntegrityViolationException`.
`GlobalExceptionHandler` caught this and returned a generic 409 `"Conflicto de datos"` — no
information about why the delete was rejected.

**Added `existsByReservaId` to three repositories** (pattern matches `FacturaRepository` which
already had it):

```java
// AnticipoRepository, PenalidadRepository, DevolucionRepository (same pattern)
@Query("SELECT COUNT(x) > 0 FROM X x WHERE x.reserva.id = :reservaId")
boolean existsByReservaId(@Param("reservaId") Long reservaId);
```

**Guard in `ReservationService.delete()`:**
```java
boolean hasFinancialHistory =
        anticipoRepository.existsByReservaId(id)   ||
        penalidadRepository.existsByReservaId(id)  ||
        facturaRepository.existsByReservaId(id)    ||
        devolucionRepository.existsByReservaId(id);

if (hasFinancialHistory) {
    throw new ResponseStatusException(HttpStatus.CONFLICT,
            "No se puede eliminar la reserva porque tiene historial financiero asociado.");
}

reservationRepository.delete(reservation);
```

Short-circuit evaluation (`||`) means only the first found child is queried; the remaining
checks are skipped. In the common case of a reservation with no children, all four queries
run — they are index-lookup `COUNT` queries and complete in microseconds.

The explicit message gives the API consumer actionable information to resolve the issue
(cancel or settle the associated financial records first).

---

### MEDIUM-15 — Pagination Size Cap at 100

**Files:** `reservation/controller/ReservationController.java`,
`factura/controller/FacturaController.java`, `auth/controller/UserController.java`

**Root cause:** All three paginated `GET` endpoints accepted an arbitrary `size` query parameter.
A request with `?size=1000000` would cause Hibernate to issue a `SELECT` with `LIMIT 1000000`,
transferring potentially millions of rows from PostgreSQL to the JVM heap in a single request.

**Fix — single-line cap at each paginated endpoint:**

```java
// Before:
return ResponseEntity.ok(service.findAll(page, size));

// After:
return ResponseEntity.ok(service.findAll(page, Math.min(size, 100)));
```

Applied identically in all three controllers. The cap is enforced at the controller layer
before the value is passed to the service, keeping services free of request-validation logic.

**Behavior:** A caller sending `?size=500` receives exactly 100 items with no error —
consistent with common REST API conventions for implicit clamping. If an explicit 400 is
preferred in the future, that can be changed without touching the service layer.

---

## Security / Correctness Impact Summary

| Change | Before | After |
|---|---|---|
| `reserva` referential integrity | App-layer only | DB-level FK (V9) + App-layer |
| Concurrent booking for same property | TOCTOU race — double-booking possible | Serialized via `SELECT FOR UPDATE` |
| Delete reservation with children | Generic 409 "Conflicto de datos" | Descriptive 409 in Spanish |
| Paginated list with huge `size` | Heap/memory DoS vector | Hard cap at 100 |

---

## Verification Checklist

- [x] Code compiles without warnings (`./mvnw clean package -DskipTests` — BUILD SUCCESS)
- [x] All 69 tests pass (`./mvnw test` — 69/69, 0 failures, 0 errors)
- [x] `PropertyRepository.findByIdForUpdate` present with `@Lock(PESSIMISTIC_WRITE)`
- [x] `ReservationService.create()` and `update()` call `lockPropertyOrThrow()` before overlap check
- [x] `ReservationService.delete()` checks all four financial-child repositories before deleting
- [x] `AnticipoRepository`, `PenalidadRepository`, `DevolucionRepository` all have `existsByReservaId`
- [x] `ReservationController`, `FacturaController`, `UserController` cap `size` at 100
- [x] `V9__add_fk_reserva_propiedad.sql` present in `db/migration`

---

## Remaining Open Issues (Not in Scope for This Sprint)

The following items from `Production_Readiness_Audit_v5.md` remain open:

**BEFORE GOING BEYOND ALPHA:**
- MEDIUM-10 — NotaCredito: no guard against issuing credit on an already-credited invoice
- MEDIUM-11 — Penalidad: no validation that the amount does not exceed the reservation total
- MEDIUM-12 — Penalidad: estado field is a free-text string with no enforced values
- MEDIUM-13 — Past check-in dates accepted without warning
- MEDIUM-16 — Soft-deleted users visible in `GET /api/usuarios` listing
- LOW-17 — Timing side-channel on login (BCrypt skipped for non-existent users)
- LOW-18 — Estado string fields on Anticipo/Penalidad/Factura/Devolucion not validated as enums
- LOW-19 — `DevelopmentDataSeeder` inserts properties before checking if they already exist
- LOW-20 — `Dockerfile` exposes port 8080 but app listens on 8082

---

## Files Changed in This Sprint

```
A  src/main/resources/db/migration/V9__add_fk_reserva_propiedad.sql
M  src/main/java/com/novafacts/backend/property/repository/PropertyRepository.java
M  src/main/java/com/novafacts/backend/anticipo/repository/AnticipoRepository.java
M  src/main/java/com/novafacts/backend/penalidad/repository/PenalidadRepository.java
M  src/main/java/com/novafacts/backend/devolucion/repository/DevolucionRepository.java
M  src/main/java/com/novafacts/backend/reservation/service/ReservationService.java
M  src/main/java/com/novafacts/backend/reservation/controller/ReservationController.java
M  src/main/java/com/novafacts/backend/factura/controller/FacturaController.java
M  src/main/java/com/novafacts/backend/auth/controller/UserController.java
```
