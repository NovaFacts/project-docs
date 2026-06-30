# Production Readiness Audit — NovaFacts Backend
**Date:** 2026-06-29
**Auditor:** Claude Sonnet 4.6 (Principal Engineer audit pass)
**Scope:** Full backend codebase — Spring Boot 3.5, Java 21, PostgreSQL, Flyway V1–V8

---

## 1. Executive Summary

The system has **4 critical-severity issues** that can cause data corruption, security breaches, or silent financial inconsistencies in production. The most dangerous is an **anti-pattern in the JWT authentication filter that allows soft-deleted users to remain fully authenticated for up to 24 hours after deactivation.** The second most dangerous is a **broken anticipo lifecycle**: `FacturaService.create()` accepts an anticipo discount but never marks the anticipo as `"aplicado"`, meaning a refund can be issued for an anticipo that was already deducted from an invoice — a double-dip of financial value. There are additionally 5 high-severity issues, 7 medium, and 4 low. No single issue is hypothetical; all are traceable to exact lines of code.

---

## 2. Ranked Issue List

| # | Severity | Title |
|---|---|---|
| 1 | **CRITICAL** | Deactivated users remain authenticated via JWT for up to 24h |
| 2 | **CRITICAL** | Anticipo lifecycle is broken — double financial claim possible |
| 3 | **CRITICAL** | `DevolucionService.create()` allows double-refund on same anticipo |
| 4 | **CRITICAL** | Reservation double-booking race condition — overlap check is non-atomic |
| 5 | HIGH | `JwtAuthenticationFilter` silently swallows all exceptions — security events invisible |
| 6 | HIGH | Login endpoint reveals valid-but-inactive accounts (user enumeration) |
| 7 | HIGH | CORS origin hardcoded to `localhost:5173` — breaks every non-dev environment |
| 8 | HIGH | `reserva.propiedad_id` has no FK constraint at the DB level |
| 9 | HIGH | `ReservationService.delete()` produces a generic 409 with FK children present |
| 10 | MEDIUM | `NotaCreditoService.create()` allows credit note against a cancelled invoice |
| 11 | MEDIUM | `NotaCreditoService.create()` allows monto > factura.total |
| 12 | MEDIUM | `PenalidadService.create()` allows `montoAprobado > montoSegunPolitica` |
| 13 | MEDIUM | Past check-in dates accepted — no temporal validation in reservation |
| 14 | MEDIUM | `GlobalExceptionHandler.handleGeneric()` swallows exception stack trace silently |
| 15 | MEDIUM | No pagination size cap — unbounded DB reads possible |
| 16 | MEDIUM | `UserService.getUsers()` returns soft-deleted users in listing |
| 17 | LOW | Timing side-channel in login — non-existent users respond faster |
| 18 | LOW | Anticipo and Devolucion state machines use raw `String` — no type safety |
| 19 | LOW | `DevelopmentDataSeeder.canalRepository.findAll()` relies on indeterminate ordering |
| 20 | LOW | `Dockerfile` `EXPOSE 8081` — wrong port (server runs on 8082) |

---

## 3. Root Cause Analysis & Fixes

---

### CRITICAL-1 — Deactivated users remain fully authenticated for 24 hours

**File:** `auth/filter/JwtAuthenticationFilter.java:48`

**Root Cause:**
`jwtService.isTokenValid()` only verifies the username matches and the token is not expired. It does not call `userDetails.isEnabled()`. Meanwhile, `UserDetailsServiceImpl.loadUserByUsername()` correctly sets `disabled(!Boolean.TRUE.equals(user.getActivo()))` on the `UserDetails` object — but the filter ignores that flag entirely.

Flow:
1. Admin calls `DELETE /api/usuarios/{id}` → `UserService.deleteUser()` sets `activo=false`
2. Deactivated user presents their existing JWT
3. `JwtAuthenticationFilter` calls `loadUserByUsername()` → gets `UserDetails` with `enabled=false`
4. `isTokenValid()` returns `true` (username matches, not expired)
5. `SecurityContextHolder` is populated — user is fully authenticated
6. All subsequent role checks pass normally

The `UserService.login()` correctly rejects inactive users at login time, but this protection is entirely bypassed for every subsequent request via the filter.

**Fix:**
```java
// JwtAuthenticationFilter.java:48
if (jwtService.isTokenValid(token, userDetails) && userDetails.isEnabled()) {
    // ...populate SecurityContext
}
```

---

### CRITICAL-2 — Anticipo lifecycle broken: double financial claim possible

**Files:** `factura/service/FacturaService.java:62-108`, `anticipo/service/AnticipoService.java:52-71`, `devolucion/service/DevolucionService.java:60-93`

**Root Cause:**
`FacturaService.create()` accepts a `descuentoAnticipo` field representing money already paid upfront. However, it never transitions any `Anticipo` entity from `"registrado"` to `"aplicado"`. The `AnticipoService.delete()` guard at line 76 protects against deleting an `"aplicado"` anticipo, but that state is only set by `DevelopmentDataSeeder` — never by any service in the normal application workflow.

Consequence: after a `Factura` is created with `descuentoAnticipo = X`, the underlying `Anticipo` record remains in state `"registrado"`. `DevolucionService.create()` only checks that the anticipo belongs to the reservation — it does not check `estado`. A refund of the same anticipo amount can then be issued, resulting in the client receiving double value (one credit on the invoice, one cash refund).

**Fix — two parts:**

`FacturaService.create()` must mark the corresponding anticipo as applied:
```java
// In FacturaService.create(), after saving the factura:
if (request.getAnticipoId() != null && request.getDescuentoAnticipo().compareTo(BigDecimal.ZERO) > 0) {
    Anticipo anticipo = anticipoRepository.findById(request.getAnticipoId())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Anticipo no encontrado"));
    if (!"registrado".equals(anticipo.getEstado())) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "El anticipo ya fue aplicado o devuelto");
    }
    anticipo.setEstado("aplicado");
    anticipoRepository.save(anticipo);
}
```

`DevolucionService.create()` must also guard against already-applied anticipos:
```java
// After loading anticipo, before creating devolucion:
if ("aplicado".equals(anticipo.getEstado())) {
    throw new ResponseStatusException(HttpStatus.CONFLICT,
        "El anticipo ya fue aplicado a una factura y no puede ser devuelto directamente");
}
if ("devuelto".equals(anticipo.getEstado())) {
    throw new ResponseStatusException(HttpStatus.CONFLICT,
        "Ya existe una devolución para este anticipo");
}
```

---

### CRITICAL-3 — `DevolucionService.create()` allows double-refund on same anticipo

**File:** `devolucion/service/DevolucionService.java:60-93`

**Root Cause:**
Between the check `anticipo.getReserva().getId().equals(reserva.getId())` and the call `anticipo.setEstado("devuelto")`, there is no guard on the current `estado` of the anticipo. Two concurrent HTTP requests for the same anticipo will both read `estado="registrado"`, both pass all checks, and both insert `devolucion` rows. The second `anticipoRepository.save(anticipo)` call will overwrite the first `"devuelto"` write (last-write-wins — no optimistic lock on `Anticipo`). The DB will contain two `devolucion` rows for one anticipo.

**Fix:**
```java
// DevolucionService.create(), after loading anticipo:
if (!"registrado".equals(anticipo.getEstado())) {
    throw new ResponseStatusException(HttpStatus.CONFLICT,
        "El anticipo no está en estado registrado y no puede ser devuelto");
}
```

Add `@Version` to `Anticipo` to enforce optimistic locking, or use a `SELECT FOR UPDATE` via a native query.

---

### CRITICAL-4 — Reservation double-booking race condition

**File:** `reservation/service/ReservationService.java:75-80`

**Root Cause:**
The overlap check `reservationRepository.existsByPropertyIdAndCheckIn...` at line 75 and the `reservationRepository.save(reservation)` at line 105 are two separate DB operations with no serialization between them. Two concurrent requests for the same property and overlapping dates can both read `false` from `existsBy...` and both save successfully. The DB has no exclusion constraint on `(propiedad_id, fecha_inicio, fecha_fin, estado)`.

**Fix:** Use a pessimistic write lock on the property row to serialize concurrent reservations:
```java
@Transactional
public ReservationResponse create(CreateReservationRequest request) {
    // Lock the property row to serialize concurrent reservations for the same property
    propertyRepository.findByIdForUpdate(request.getPropertyId());
    // ... existing logic follows
}
```

Repository addition:
```java
@Query("SELECT p FROM Property p WHERE p.id = :id")
@Lock(LockModeType.PESSIMISTIC_WRITE)
Optional<Property> findByIdForUpdate(@Param("id") Long id);
```

---

### HIGH-5 — JWT filter silently swallows all exceptions

**File:** `auth/filter/JwtAuthenticationFilter.java:56-58`

**Root Cause:**
```java
} catch (Exception e) {
    SecurityContextHolder.clearContext();
}
```

This catches expired tokens, tampered tokens, malformed tokens, `NullPointerException` in `JwtService`, `UsernameNotFoundException` from `UserDetailsService`, and any future exception type. None are logged. In production, security attacks are indistinguishable from bugs.

**Fix:**
```java
} catch (ExpiredJwtException e) {
    log.debug("JWT expired for request to {}", request.getRequestURI());
    SecurityContextHolder.clearContext();
} catch (JwtException e) {
    log.warn("Invalid JWT on request to {}: {}", request.getRequestURI(), e.getMessage());
    SecurityContextHolder.clearContext();
} catch (Exception e) {
    log.error("Unexpected error in JWT filter for {}", request.getRequestURI(), e);
    SecurityContextHolder.clearContext();
}
```

---

### HIGH-6 — Login reveals valid-but-inactive accounts (user enumeration)

**File:** `auth/service/UserService.java:85-88`

**Root Cause:**
The login method returns `"Credenciales inválidas"` for unknown users and wrong passwords (correct), but returns `"Cuenta de usuario desactivada"` when the account exists but `activo=false`. An attacker can enumerate valid email addresses by observing this different message.

**Fix:**
```java
// UserService.login()
if (!Boolean.TRUE.equals(user.getActivo())) {
    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales inválidas");
}
```

---

### HIGH-7 — CORS hardcoded to `localhost:5173`

**File:** `config/SecurityConfig.java:73`

**Root Cause:**
```java
configuration.setAllowedOrigins(List.of("http://localhost:5173"));
```

This is a compile-time constant. Every staging, production, or other environment will have all browser requests blocked by the same-origin policy until this is changed and redeployed.

**Fix:**
```properties
# application.properties
cors.allowed-origins=${CORS_ALLOWED_ORIGINS:http://localhost:5173}
```

```java
// SecurityConfig.java
@Value("${cors.allowed-origins}")
private List<String> allowedOrigins;

configuration.setAllowedOrigins(allowedOrigins);
```

Add `CORS_ALLOWED_ORIGINS=https://app.novafacts.com` to the production environment.

---

### HIGH-8 — `reserva.propiedad_id` has no FK constraint at the DB level

**Files:** `db/migration/V1__baseline.sql:38-48`, `reservation/entity/Reservation.java:41-42`

**Root Cause:**
V1 creates `propiedad_id BIGINT NOT NULL` on the `reserva` table with only an index — no `FOREIGN KEY (propiedad_id) REFERENCES propiedad(id)`. No subsequent migration adds this constraint. The entity uses `@Column(name = "propiedad_id")` (a plain column, not `@ManyToOne`). The only guard is the application-level `propertyRepository.existsById()` check in `ReservationService`.

Any direct DB insert, data migration, or bug that bypasses the service layer can create reservations referencing non-existent properties. `toResponse()` will not NPE on this field since it's a raw `Long`, but any reporting or financial aggregation that joins on the property will silently miss rows.

**Fix:**
```sql
-- V9__add_fk_reserva_propiedad.sql
ALTER TABLE reserva
    ADD CONSTRAINT fk_reserva_propiedad
    FOREIGN KEY (propiedad_id) REFERENCES propiedad(id);
```

---

### HIGH-9 — `ReservationService.delete()` produces a generic 409 with FK children present

**File:** `reservation/service/ReservationService.java:142-144`

**Root Cause:**
```java
public void delete(Long id) {
    reservationRepository.delete(getOrThrow(id));
}
```

If the reservation has child records (anticipos, penalidades, factura), PostgreSQL throws `DataIntegrityViolationException`. `GlobalExceptionHandler.handleDataIntegrity()` returns `{"error": "Conflicto de datos"}` with no indication of what needs to be removed first.

**Fix:**
```java
@Transactional
public void delete(Long id) {
    Reservation reservation = getOrThrow(id);
    if (anticipoRepository.existsByReservaId(id)) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "No se puede eliminar la reserva: tiene anticipos registrados");
    }
    if (facturaRepository.existsByReservaId(id)) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "No se puede eliminar la reserva: tiene una factura emitida");
    }
    reservationRepository.delete(reservation);
}
```

---

### MEDIUM-10 — Credit notes can be issued against cancelled invoices

**File:** `notacredito/service/NotaCreditoService.java:55-76`

**Root Cause:** `create()` loads the `Factura` and immediately creates the `NotaCredito` with no check on `factura.getEstado()`. A `CANCELLED` invoice should not accept credit notes.

**Fix:**
```java
if (factura.getEstado() == InvoiceStatus.CANCELLED) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
        "No se puede emitir una nota de crédito sobre una factura anulada");
}
```

---

### MEDIUM-11 — Credit note amount is unbounded against invoice total

**File:** `notacredito/service/NotaCreditoService.java:65`

**Root Cause:** No validation that `monto <= factura.total`. A credit note for any arbitrary amount can be issued against any invoice.

**Fix:**
```java
if (monto.compareTo(factura.getTotal()) > 0) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
        "El monto de la nota de crédito no puede superar el total de la factura");
}
```

---

### MEDIUM-12 — Penalty approved amount can exceed policy amount

**File:** `penalidad/service/PenalidadService.java:70-71`

**Root Cause:** No validation that `montoAprobado <= montoSegunPolitica`. An operator can approve a higher penalty than what the cancellation policy specifies.

**Fix:**
```java
if (request.getMontoAprobado().compareTo(request.getMontoSegunPolitica()) > 0) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
        "El monto aprobado no puede superar el monto según la política");
}
```

---

### MEDIUM-13 — Past check-in dates are accepted

**File:** `reservation/service/ReservationService.java:177-186`

**Root Cause:** `validateDates()` only checks `checkIn < checkOut` and duration ≤ 30 nights. A reservation with `checkIn = 2020-01-01` passes validation.

**Fix:**
```java
private void validateDates(LocalDate checkIn, LocalDate checkOut) {
    if (!checkIn.isAfter(LocalDate.now().minusDays(1))) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "La fecha de inicio no puede ser en el pasado");
    }
    if (!checkIn.isBefore(checkOut)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "La fecha de inicio debe ser anterior a la fecha de fin");
    }
    if (ChronoUnit.DAYS.between(checkIn, checkOut) > 30) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "La reserva no puede superar 30 noches");
    }
}
```

---

### MEDIUM-14 — `GlobalExceptionHandler.handleGeneric()` swallows the stack trace

**File:** `common/GlobalExceptionHandler.java:48-52`

**Root Cause:**
```java
@ExceptionHandler(Exception.class)
public ResponseEntity<Map<String, String>> handleGeneric(Exception ex) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of("error", "Error interno del servidor"));
}
```

The exception `ex` is never logged. When an unexpected error occurs in production, there is no server-side trace to diagnose it.

**Fix:**
```java
private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

@ExceptionHandler(Exception.class)
public ResponseEntity<Map<String, String>> handleGeneric(Exception ex) {
    log.error("Unhandled exception: {}", ex.getMessage(), ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of("error", "Error interno del servidor"));
}
```

---

### MEDIUM-15 — No pagination size cap

**Files:** `auth/service/UserService.java:74`, `reservation/service/ReservationService.java:57`

**Root Cause:** `PageRequest.of(page, size, ...)` accepts any `size` value from the query parameter. A caller can request `size=100000` and force a full table scan in a single HTTP response.

**Fix:**
```java
private static final int MAX_PAGE_SIZE = 100;

Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), Sort.by("nombre").ascending());
```

---

### MEDIUM-16 — `UserService.getUsers()` returns soft-deleted users

**File:** `auth/service/UserService.java:76`

**Root Cause:** `userRepository.findAll(pageable)` returns all users regardless of `activo`. Deactivated users appear in the admin user management list.

**Fix:**
```java
// UserRepository.java
Page<User> findByActivoTrue(Pageable pageable);

// UserService.java
return new PageResponse<>(userRepository.findByActivoTrue(pageable).map(this::toResponse));
```

---

### LOW-17 — Timing side-channel on login for non-existent users

**File:** `auth/service/UserService.java:81-93`

**Root Cause:** When a user is not found, the method throws immediately without running `passwordEncoder.matches()`. BCrypt verification takes ~100ms. An attacker can enumerate valid emails by measuring response latency: a fast response indicates an unknown email; a slow response indicates a valid email with the wrong password.

**Fix:** Run a dummy BCrypt check on the not-found path to equalize response time:
```java
User user = userRepository.findByUsername(request.getEmail()).orElse(null);
if (user == null) {
    passwordEncoder.matches(request.getPassword(),
        "$2a$10$dummyhashtopreventtimingattackaaaaaaaaaaaaaaaaaaaaaaaa");
    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales inválidas");
}
```

---

### LOW-18 — State machines use raw `String` fields

**Files:** `anticipo/entity/Anticipo.java:35`, `devolucion/entity/Devolucion.java:37`

**Root Cause:** States `"registrado"`, `"aplicado"`, `"devuelto"`, `"pendiente"`, `"procesada"`, `"rechazada"` are plain `String` fields with no compile-time safety. A typo anywhere in the codebase silently passes compilation and produces a logically invisible bug.

**Fix:** Create `AnticipoStatus` and `DevolucionStatus` enums, map with `@Enumerated(EnumType.STRING)`, and add `CHECK` constraints in a new Flyway migration.

---

### LOW-19 — `DevelopmentDataSeeder.canalRepository.findAll()` relies on indeterminate ordering

**File:** `config/DevelopmentDataSeeder.java:111`, lines 171–213

**Root Cause:** `findAll()` without `Sort` returns rows in DB-internal heap order, which is non-deterministic. With exactly 5 canales this will not throw, but after table rebuilds or vacuums the order may differ from insertion order, assigning the wrong canal to each seeded reservation.

**Fix:**
```java
List<Canal> canales = canalRepository.findAll(Sort.by("id").ascending());
```

---

### LOW-20 — `Dockerfile` `EXPOSE 8081` — wrong port

**Root Cause:** `EXPOSE 8081` is documentation metadata used by orchestration tools (Kubernetes, Docker Swarm) for health checks and service discovery. The actual application port is `server.port=8082`. Health probes targeting port 8081 will fail.

**Fix:** Change `EXPOSE 8081` to `EXPOSE 8082`.

---

## 4. Unknown Risks (Require Runtime Validation)

**U1 — `JwtService.getSigningKey()` will fail silently on short keys.** If `JWT_SECRET` is rotated to a value that decodes to fewer than 256 bits, `Keys.hmacShaKeyFor()` will throw on the first token operation — not on startup. No startup validation guard exists. Add a `@PostConstruct` key-size assertion to `JwtService`.

**U2 — H2 ↔ PostgreSQL SQL dialect gaps give false test confidence.** All tests pass with H2 `create-drop`. Any future native SQL query will pass tests and may fail in PostgreSQL. Critical paths (especially `FacturaRepository` JPQL) should be validated against a real PostgreSQL instance in CI.

**U3 — `Temporada` date ranges can overlap.** No DB constraint and no service-layer check prevents creating two `Temporada` records with overlapping `fecha_inicio`/`fecha_fin`. A reservation assigned to one temporada when multiple overlap is indeterminate.

**U4 — `DevelopmentDataSeeder` and `AdminUserInitializer` concurrent startup.** Both run after context load. `AdminUserInitializer` (`ApplicationRunner`) and `DevelopmentDataSeeder` (`CommandLineRunner`) execute in the same phase. On a fresh DB, if their `existsByUsername` / `count()` checks race, both may attempt to insert the same admin email simultaneously. The second insert hits the `UNIQUE` constraint and becomes a caught `DataIntegrityViolationException` with no log.

**U5 — `factura.estado` stored as `VARCHAR` with no DB `CHECK` constraint.** A direct DB update or a future migration error inserting an unrecognized string will cause an unhandled `IllegalArgumentException` on the next JPA read of that row, making the record permanently unreadable until corrected in the DB.

---

## 5. Priority Fix Order for Production Deployment

```
BEFORE FIRST PRODUCTION DEPLOY:
  1. CRITICAL-1  — JwtAuthenticationFilter: add isEnabled() check        (3 lines)
  2. CRITICAL-2  — FacturaService: mark anticipo as "aplicado" on create  (~15 lines)
  3. CRITICAL-3  — DevolucionService: guard anticipo estado before refund  (5 lines)
  4. HIGH-7      — CORS: move allowed-origins to env var                   (5 lines + config)
  5. HIGH-5      — JWT filter: split catch blocks and add logging           (~10 lines)
  6. HIGH-6      — Login: return identical message for inactive accounts    (1 line)
  7. MEDIUM-14   — GlobalExceptionHandler: log unhandled exceptions         (2 lines)

BEFORE FIRST USER LOAD:
  8. CRITICAL-4  — Reservation overlap: SELECT FOR UPDATE on property row
  9. HIGH-8      — V9 migration: add FK on reserva.propiedad_id
  10. HIGH-9     — ReservationService.delete(): explicit FK children guard
  11. MEDIUM-15  — Pagination: cap size at 100

BEFORE GOING BEYOND ALPHA:
  12. MEDIUM-10  — NotaCredito: reject against CANCELLED invoices
  13. MEDIUM-11  — NotaCredito: cap monto at factura.total
  14. MEDIUM-12  — Penalidad: enforce montoAprobado <= montoSegunPolitica
  15. MEDIUM-13  — Reservation: reject past check-in dates
  16. MEDIUM-16  — UserService: filter soft-deleted users from listing
  17. LOW-18     — Replace String state machines with enums
```
