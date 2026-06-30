# Reservation Backend Code Review

**Module:** `com.novafacts.backend.reservation`  
**Reviewer:** Senior Architecture Review  
**Date:** 2026-06-27  
**Pre-condition:** `./mvnw clean test` → 32 tests, 0 failures, BUILD SUCCESS  

---

## Critical Issues

### C-1 — Cancelled and completed reservations permanently block their dates

**Files:** `ReservationRepository.java:7-10`, `ReservationService.java:47-50`, `ReservationService.java:68-71`

**Impact:** Functional correctness failure. Once a reservation is cancelled, no new reservation can be created for that property and those dates. The cancellation feature is effectively broken.

**Root cause:** Both overlap queries have no filter on `status`. A `CANCELLED` reservation remains in the `reserva` table and its dates satisfy the `fecha_inicio < ? AND fecha_fin > ?` condition just as a `CONFIRMED` one does.

**Reproduction:**

```bash
# 1. Create reservation for property 1, Aug 1-7
POST /api/reservations → { "propertyId": 1, "checkIn": "2026-08-01", "checkOut": "2026-08-07", ... }
# → 201 { "id": 1, "status": "CONFIRMED" }

# 2. Cancel it
PUT /api/reservations/1 → { ..., "status": "CANCELLED" }
# → 200 { "id": 1, "status": "CANCELLED" }

# 3. Try to rebook the same dates on the same property
POST /api/reservations → { "propertyId": 1, "checkIn": "2026-08-01", "checkOut": "2026-08-07", ... }
# → 409 "La propiedad ya tiene una reserva en esas fechas"  ← BUG
```

The same issue applies to `COMPLETED` reservations: a completed stay should never block rebooking those same past dates.

**Fix:** Add a status filter to both repository methods. Only `CONFIRMED` reservations should block dates.

Current repository:
```java
boolean existsByPropertyIdAndCheckInBeforeAndCheckOutAfter(
        Long propertyId, LocalDate checkOut, LocalDate checkIn);

boolean existsByPropertyIdAndCheckInBeforeAndCheckOutAfterAndIdNot(
        Long propertyId, LocalDate checkOut, LocalDate checkIn, Long id);
```

Fixed repository:
```java
boolean existsByPropertyIdAndCheckInBeforeAndCheckOutAfterAndStatus(
        Long propertyId, LocalDate checkOut, LocalDate checkIn, ReservationStatus status);

boolean existsByPropertyIdAndCheckInBeforeAndCheckOutAfterAndStatusAndIdNot(
        Long propertyId, LocalDate checkOut, LocalDate checkIn, ReservationStatus status, Long id);
```

Fixed service calls (pass `ReservationStatus.CONFIRMED` as the status argument):
```java
// in create():
if (reservationRepository.existsByPropertyIdAndCheckInBeforeAndCheckOutAfterAndStatus(
        request.getPropertyId(), request.getCheckOut(), request.getCheckIn(),
        ReservationStatus.CONFIRMED)) { ... }

// in update():
if (reservationRepository.existsByPropertyIdAndCheckInBeforeAndCheckOutAfterAndStatusAndIdNot(
        request.getPropertyId(), request.getCheckOut(), request.getCheckIn(),
        ReservationStatus.CONFIRMED, id)) { ... }
```

This must be fixed before the module is used in any environment where cancellations occur.

---

## Medium Improvements

### M-1 — Overlap query parameter order is counterintuitive and fragile

**Files:** `ReservationRepository.java:7-10`, `ReservationService.java:47-49`, `ReservationService.java:68-70`

The overlap query is mathematically correct. However, the derived method name creates a non-obvious parameter order:

```java
existsByPropertyIdAndCheckInBeforeAndCheckOutAfter(
    Long propertyId,
    LocalDate checkOut,   // ← 2nd param is checkOut (bound for "CheckIn Before")
    LocalDate checkIn     // ← 3rd param is checkIn  (bound for "CheckOut After")
)
```

Spring Data assigns positional parameters left-to-right to keywords in the method name. `CheckInBefore` takes the `checkOut` value (because `checkIn < newCheckOut`), and `CheckOutAfter` takes the `checkIn` value (because `checkOut > newCheckIn`). This is correct:

```sql
WHERE propiedad_id = ?        -- propertyId
  AND fecha_inicio < ?        -- newCheckOut (correctly passed 2nd)
  AND fecha_fin > ?            -- newCheckIn  (correctly passed 3rd)
```

But a future developer reading the call site:
```java
.existsByPropertyIdAndCheckInBeforeAndCheckOutAfter(
    request.getPropertyId(), request.getCheckOut(), request.getCheckIn())
```
…will likely assume the parameters are in `(propertyId, checkIn, checkOut)` order and might silently swap them during a refactor, introducing a bug that passes identical tests (the Spring Data method name, not the values, determines the semantics).

**Mitigation:** Add a one-line comment at the call site and the repository declaration explaining the intentional swap:

```java
// Overlap condition: existing.checkIn < newCheckOut AND existing.checkOut > newCheckIn
// Parameter order is intentional: checkOut is passed as the "Before" bound, checkIn as "After"
boolean existsByPropertyIdAndCheckInBeforeAndCheckOutAfterAndStatus(...);
```

---

### M-2 — No database index on `propiedad_id` in the `reserva` table

**File:** `Reservation.java`

The overlap query runs on every `create()` and `update()` call:

```sql
WHERE propiedad_id = ? AND fecha_inicio < ? AND fecha_fin > ? AND estado = ?
```

Without an index on `propiedad_id`, Hibernate executes a full table scan on `reserva`. For a booking platform, the number of reservations will grow faster than the number of properties, making this query increasingly expensive over time.

The `Property` entity uses `@Column(unique = true)` on `nombre`, which implicitly creates a B-tree index. `Reservation` has no analogous declaration.

**Fix — add index declaration to the entity:**

```java
@Entity
@Table(
    name = "reserva",
    indexes = @Index(name = "idx_reserva_propiedad_id", columnList = "propiedad_id")
)
public class Reservation { ... }
```

`ddl-auto=update` will generate this index on next startup. This is a single annotation change with no logic impact.

---

### M-3 — Concurrent booking race condition (check-then-act)

**File:** `ReservationService.java:47-53`

The overlap check and the subsequent `save()` are not atomic at the database level. Two concurrent POST requests for the same property and overlapping dates can both execute the overlap SELECT (both find no conflict), and both then proceed to INSERT — resulting in two overlapping reservations in the database.

```
Thread A: existsBy... → false
Thread B: existsBy... → false     (before A commits)
Thread A: save()      → INSERT
Thread B: save()      → INSERT    ← overlapping reservation committed
```

This cannot be solved by `@Transactional` alone at the default PostgreSQL isolation level (READ COMMITTED). A solution requires one of:

1. **Pessimistic lock on the property row** before the overlap check (`SELECT FOR UPDATE` on `propiedad.id`).
2. **A unique partial index** at the database level (not expressible via JPA derived queries alone).
3. **Application-level serialization** (e.g., a distributed lock per `propertyId`).

For a class project this is documented and acceptable. For production, option 1 is the lowest-friction fix:

```java
// In PropertyRepository, add:
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT p FROM Property p WHERE p.id = :id")
Optional<Property> findByIdForUpdate(@Param("id") Long id);
```

Then replace `getPropertyOrThrow` in `create()` and `update()` with this locked variant. The existing `@Transactional` boundary will hold the lock until commit.

**This is flagged as Medium because the scenario requires simultaneous requests for the same property, which is unlikely but possible in production.**

---

## Minor Improvements

### m-1 — Invalid `ReservationStatus` string returns 500, not 400

**File:** `UpdateReservationRequest.java:21-22`

If a client sends `"status": "INVALID_VALUE"`, Jackson throws `HttpMessageNotReadableException` (not `MethodArgumentNotValidException`). The `GlobalExceptionHandler` has no `@ExceptionHandler` for this type; it falls through to the generic handler and returns `500 "Error interno del servidor"`.

This is a project-wide gap (the same behavior exists for any enum field in Guest/Property DTOs), not introduced by this module. **No action needed here** — document and resolve at the project level when adding a `HttpMessageNotReadableException` handler to `GlobalExceptionHandler`. When added, all modules benefit automatically.

---

### m-2 — `guestId` and `propertyId` have no positivity constraint on the DTO

**File:** `CreateReservationRequest.java:11-14`, `UpdateReservationRequest.java:11-14`

`@NotNull` accepts any non-null `Long`, including `0L` and `-1L`. Sending `"guestId": -1` passes DTO validation and reaches the service, where `guestRepository.existsById(-1L)` returns `false`, throwing `404 "Huésped no encontrado"`. The message is technically correct but slightly misleading — the input is malformed, not just referencing a nonexistent record.

Adding `@Positive` on both fields would return a 400 with a Spanish message for clearly invalid IDs, before the DB is queried:

```java
@NotNull(message = "El identificador del huésped es obligatorio")
@Positive(message = "El identificador del huésped debe ser mayor a cero")
private Long guestId;
```

This is cosmetic for this module but consistent with the `@Positive` pattern used on `guestCount`.

---

### m-3 — No `@FutureOrPresent` constraint on `checkIn`

**File:** `CreateReservationRequest.java:17`, `UpdateReservationRequest.java:17`

`checkIn: "2020-01-01"` is accepted. Past-date reservations do not violate any stated business rule in the spec, and the implementation report documents this as a deliberate omission. Flagged here only because it is a common source of data quality issues in booking systems.

If added, the constraint should be on `checkIn` only (not `checkOut`), and the message should be in Spanish:

```java
@NotNull(message = "La fecha de inicio es obligatoria")
@FutureOrPresent(message = "La fecha de inicio no puede ser anterior a hoy")
private LocalDate checkIn;
```

---

## Well Implemented

### Overlap detection math is correct

The formula `existsBy...CheckInBefore(newCheckOut)...CheckOutAfter(newCheckIn)` correctly implements the mathematical overlap condition `A.checkIn < B.checkOut AND A.checkOut > B.checkIn`, which is the standard interval intersection test excluding touching boundaries. All edge cases produce correct results:

| Scenario | Existing | New | Result |
|---|---|---|---|
| Adjacent | [10, 15] | [15, 20] | No conflict ✓ |
| Identical | [10, 15] | [10, 15] | Conflict ✓ |
| Contained | [10, 20] | [12, 15] | Conflict ✓ |
| Partial overlap right | [10, 15] | [12, 18] | Conflict ✓ |
| Partial overlap left | [10, 15] | [5, 12] | Conflict ✓ |
| Non-overlapping before | [10, 15] | [5, 10] | No conflict ✓ |
| Non-overlapping after | [10, 15] | [15, 20] | No conflict ✓ |

### Self-exclusion on update is correct

`existsByPropertyId...AndIdNot(propertyId, checkOut, checkIn, id)` correctly excludes the reservation being updated from the conflict check. A reservation can be updated to its own current dates without triggering a false 409. This mirrors the `existsByDocumentNumberAndIdNot` pattern from `GuestRepository` exactly.

### Status is hardcoded to `CONFIRMED` on creation

`CreateReservationRequest` has no `status` field. The service enforces `ReservationStatus.CONFIRMED` on every new reservation. This is the correct architectural decision: status is a system-managed state transition, not a client-supplied value at creation time.

### Transaction boundaries are exactly correct

Every public service method is annotated. Private helpers (`getOrThrow`, `getPropertyOrThrow`, `validateGuestExists`, `validateDates`, `validateGuestCount`, `toResponse`) are not annotated — they execute within the transaction of their caller, which is the only correct behavior given Spring's AOP proxy model.

### Cross-module dependency direction is explicit and clean

`ReservationService` injects `GuestRepository` and `PropertyRepository` directly. No `@ManyToOne` relationships are used. This makes the dependency explicit, avoids accidental lazy-loading behavior, keeps module boundaries clear, and requires no modification to the guest or property packages.

### Entity exposure is zero

`Reservation` is never returned from any controller method. All external responses use `ReservationResponse`. No entity field is reachable by a client.

### Helper method decomposition matches project standard

Five private helpers break the service into readable single-responsibility units: `getOrThrow`, `getPropertyOrThrow`, `validateGuestExists`, `validateDates`, `validateGuestCount`, `toResponse`. The naming is precise and consistent with `GuestService` and `PropertyService`.

### Exception messages are consistent and in Spanish

| Condition | Status | Message |
|---|---|---|
| Reservation not found | 404 | "Reserva no encontrada" |
| Guest not found | 404 | "Huésped no encontrado" |
| Property not found | 404 | "Propiedad no encontrada" |
| Invalid dates | 400 | "La fecha de inicio debe ser anterior a la fecha de fin" |
| Exceeds 30 nights | 400 | "La reserva no puede superar 30 noches" |
| Capacity exceeded | 400 | "La cantidad de huéspedes supera la capacidad de la propiedad" |
| Date conflict | 409 | "La propiedad ya tiene una reserva en esas fechas" |

All messages are consistent in tone and register with the rest of the project.

### `EnumType.STRING` is the correct storage strategy

`@Enumerated(EnumType.STRING)` stores `"CONFIRMED"`, `"CANCELLED"`, `"COMPLETED"` as strings in `estado VARCHAR(20)`. This is robust against enum constant reordering (which would corrupt `EnumType.ORDINAL` data) and makes the table human-readable without a lookup table.

### Invoice module readiness

The `Reservation` entity exposes every field needed by the upcoming `FacturaService`:

| Field | Invoice use |
|---|---|
| `id` | FK reference in `factura.reserva_id` |
| `propertyId` | Lookup `propiedad.precio_por_noche` |
| `checkIn`, `checkOut` | Night count: `DAYS.between(checkIn, checkOut)` |
| `guestId` | Invoice addressee |
| `status` | Guard: only issue invoices for CONFIRMED/COMPLETED |

`FacturaService` can inject `ReservationRepository`, call `findById()`, and compute the invoice without any structural change to this module.

---

## Production Readiness Verdict

**NOT READY — one critical fix required before this module can be used in any environment where reservations are cancelled.**

Fix C-1 (status filter on overlap queries) — a two-line change to the repository and two call sites in the service — before allowing the Invoice module development to begin. Without this fix, cancellation is a one-way operation that permanently destroys availability for those dates.

After C-1 is fixed, the module is architecturally sound for continued development. M-1 (index) should also be applied immediately given it is a single annotation with no logic impact. M-2 and M-3 are acceptable for a class project and can be deferred.

**Fix priority:**

| Issue | Effort | Required before |
|---|---|---|
| C-1 — Status filter on overlap queries | ~15 min | Merge |
| M-1 — Index on `propiedad_id` | ~5 min | Merge |
| M-2 — Overlap query parameter comment | ~5 min | Merge |
| M-3 — Pessimistic lock for concurrent bookings | ~30 min | Production |
| m-2 — `@Positive` on `guestId`/`propertyId` | ~5 min | Optional |
| m-3 — `@FutureOrPresent` on `checkIn` | ~5 min | Optional |
