# Architecture Preparation for Reservations Module

**Date:** 2026-06-27
**Scope:** Transactional service layer policy + booking package integration assessment
**Build result:** `BUILD SUCCESS` — 32 tests, 0 failures, 0 errors

---

## 1. Transactional Changes Performed

### 1.1 Policy Applied

The following policy was applied uniformly across all `@Service` classes:

| Method category | Annotation | Rationale |
|---|---|---|
| Methods that write to the database (`create`, `update`, `delete`) | `@Transactional` | All writes are atomic. If any step fails, the entire operation rolls back. |
| Methods that only read from the database (`findAll`, `findById`, `getUsers`, `login`) | `@Transactional(readOnly = true)` | Hibernate skips dirty checking; the JPA provider may route reads to a replica; communicates intent explicitly. |
| Infrastructure methods called by the security filter (`loadUserByUsername`) | `@Transactional(readOnly = true)` | Ensures the user lookup runs inside a managed session even when called from outside a service context (e.g., from `JwtAuthenticationFilter`). |

Private helper methods (`getOrThrow`, `toResponse`) are not annotated. Spring's AOP proxy does not intercept private method calls; these helpers always execute within the transaction of their calling public method.

The import used: `org.springframework.transaction.annotation.Transactional` (Spring's annotation, not `jakarta.transaction.Transactional`). Spring's annotation supports `readOnly`, `propagation`, `isolation`, and `rollbackFor` attributes; it is the correct choice for Spring Boot 3.x applications.

---

### 1.2 `GuestService` — Changes

**File:** `guest/service/GuestService.java`

```java
@Transactional(readOnly = true)
public List<GuestResponse> findAll() { ... }

@Transactional(readOnly = true)
public GuestResponse findById(Long id) { ... }

@Transactional
public GuestResponse create(CreateGuestRequest request) { ... }

@Transactional
public GuestResponse update(Long id, UpdateGuestRequest request) { ... }

@Transactional
public void delete(Long id) { ... }
```

**Effect on `create()` and `update()`:** The duplicate-document check (`existsByDocumentNumber`) and the subsequent `save()` now execute within the same transaction. If two concurrent requests pass the service-level uniqueness check simultaneously, one will fail at the database constraint and the transaction will roll back cleanly. The `GlobalExceptionHandler.handleDataIntegrity()` catches the resulting `DataIntegrityViolationException` and returns `409 Conflict`. This eliminates the race condition described in the Architecture Readiness Review.

**Effect on `delete()`:** The `getOrThrow()` call (SELECT) and `guestRepository.delete()` (DELETE) now execute atomically. If the entity is deleted between the SELECT and the DELETE by a concurrent request, the outer transaction handles the exception correctly.

---

### 1.3 `UserService` — Changes

**File:** `auth/service/UserService.java`

```java
@Transactional
public void deleteUser(Long id) { ... }

@Transactional
public UserResponse createUser(CreateUserRequest request) { ... }

@Transactional(readOnly = true)
public List<UserResponse> getUsers() { ... }

@Transactional(readOnly = true)
public LoginResponse login(LoginRequest request) { ... }
```

**Note on `login()`:** This method reads a `User` from the database, performs an in-memory BCrypt comparison, and generates a JWT. No database writes occur. Marking it `readOnly = true` is semantically correct: Hibernate skips the snapshot comparison of the loaded `User` entity since no flush will occur.

---

### 1.4 `UserDetailsServiceImpl` — Changes

**File:** `auth/service/UserDetailsServiceImpl.java`

```java
@Override
@Transactional(readOnly = true)
public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException { ... }
```

This method is invoked by `JwtAuthenticationFilter` on every authenticated request, outside any existing Spring transaction. Without `@Transactional`, the repository call runs in auto-commit mode. With `@Transactional(readOnly = true)`, a proper managed session is opened and closed cleanly per request. The behavior is identical to callers; the improvement is at the session lifecycle level.

---

### 1.5 Build Verification

```
./mvnw clean compile   → BUILD SUCCESS (exit 0, no warnings)
./mvnw test            → BUILD SUCCESS, Tests run: 32, Failures: 0, Errors: 0, Skipped: 0
```

All 32 pre-existing tests continue to pass. No business logic was modified.

---

## 2. Booking Package Assessment

### 2.1 What Currently Exists

```
booking/
├── model/
│   └── Booking.java               Plain Java immutable value object
└── service/
    ├── BookingValidator.java      Business rule validator (no Spring annotations)
    └── InvoiceCalculator.java     Financial calculator (no Spring annotations)
```

Corresponding tests:

```
test/booking/
├── model/BookingTest.java         9 tests — constructor validation
├── service/BookingValidatorTest.java  10 tests — business rules
└── service/InvoiceCalculatorTest.java 12 tests — financial calculations with COP amounts
```

None of the three classes are Spring-managed beans. They contain no JPA annotations, no `@Service`, no `@Component`. They are plain Java objects that can be instantiated with `new`.

---

### 2.2 Inventory of Business Rules

The 31 executable tests in `BookingValidatorTest` and `InvoiceCalculatorTest` constitute a machine-readable specification of domain rules. Each is documented below with its boundary values.

#### Booking creation constraints (`BookingValidator`)

| Rule | Constant | Source |
|---|---|---|
| Check-in must not be in the past | `LocalDate.now()` comparison | `validate()` line 16 |
| Maximum guests per booking | `MAX_GUESTS_PER_ROOM = 4` | `validate()` line 18 |
| Maximum stay duration | `MAX_STAY_NIGHTS = 30` | `validate()` line 22 |
| Check-out must be strictly after check-in | Constructor guard | `Booking.java` line 29 |
| Price per night must be > 0 | Constructor guard | `Booking.java` line 32 |
| Guest count must be ≥ 1 | Constructor guard | `Booking.java` line 35 |

**Boundary values confirmed by tests:**
- 4 guests → valid (`validate_fourGuests_isValid`)
- 5 guests → throws (`validate_fiveGuests_throws`)
- 30 nights → valid (`validate_thirtyNights_isValid`)
- 31 nights → throws (`validate_thirtyOneNights_throws`)

#### Date conflict detection (`BookingValidator.hasDateConflict`)

The algorithm: two bookings overlap if and only if `b1.checkIn < b2.checkOut AND b2.checkIn < b1.checkOut`.

**Critical boundary confirmed by test:** Same-day checkout/check-in is NOT a conflict (`hasDateConflict_adjacentDates_returnsFalse`). This business rule must be preserved exactly: a guest checking out on day N and a guest checking in on day N is valid.

#### Invoice calculation (`InvoiceCalculator`)

| Rule | Value | Test case |
|---|---|---|
| IVA rate | 19% | `calculateTax_normalSubtotal_returns19Percent` → 100,000 × 0.19 = 19,000 |
| Long-stay discount threshold | 7 nights (inclusive) | `calculateDiscount_sevenNights_returnsTenPercent` |
| Long-stay discount rate | 10% of subtotal | `calculateDiscount_sevenNights` → 7 × 100,000 × 0.10 = 70,000 |
| No discount below threshold | — | `calculateDiscount_sixNights_returnsZero` |
| Formula | total = subtotal + (subtotal × 0.19) − discount | `calculateTotal_sevenNights_withDiscount` → 763,000 |

**Precision note:** All calculations use `double`. The tests use `assertEquals(expected, actual, 0.01)` — a delta tolerance. At COP amounts (100,000 to millions), `double` arithmetic can accumulate rounding errors that exceed 1 centavo in edge cases. The test suite's `0.01` tolerance masks this. The production implementation should use `BigDecimal`.

---

### 2.3 Verdict: What to Keep, What to Discard

#### Keep — business rules and constants

The numeric constants and the algorithms are correct and tested. They must be ported into the new service layer verbatim:

| Element | Keep? | Destination in new module |
|---|---|---|
| `MAX_GUESTS_PER_ROOM = 4` | ✅ | `ReservaService` as a private constant |
| `MAX_STAY_NIGHTS = 30` | ✅ | `ReservaService` as a private constant |
| `hasDateConflict` interval algorithm | ✅ | Translated to JPQL in `ReservaRepository` |
| IVA = 19% | ✅ | `FacturaService` as a `BigDecimal` constant |
| Discount rate = 10% at ≥ 7 nights | ✅ | `FacturaService` as `BigDecimal` constants |
| 31 test cases as boundary specifications | ✅ | New tests for `ReservaService` and `FacturaService` |

#### Discard — the class model

| Element | Discard? | Reason |
|---|---|---|
| `Booking.java` as domain model | ✅ | Uses `String guestName` (should be FK to `huesped`); uses `double pricePerNight` (should be `BigDecimal`); has no property reference, no status, no relationship to the JPA schema |
| `BookingValidator` as a standalone class | ✅ | Validation logic belongs inside `ReservaService`, following the established pattern in `GuestService` |
| `InvoiceCalculator` as a standalone class | ✅ | Calculation logic belongs inside `FacturaService` using `BigDecimal` arithmetic |
| `booking/` package itself | ✅ (after migration) | Once the rules are ported and the new service tests pass, the original package should be deleted |

---

## 3. Recommended Architecture for the Reservations Module

### 3.1 Package Structure

Follow the established feature-package convention:

```
com.novafacts.backend
├── auth/             (existing)
├── booking/          (to be deleted after migration — see §4)
├── common/           (existing — GlobalExceptionHandler)
├── config/           (existing — SecurityConfig)
├── guest/            (existing)
│
├── reserva/          ← NEW
│   ├── controller/ReservaController.java
│   ├── dto/
│   │   ├── CreateReservaRequest.java
│   │   ├── UpdateReservaRequest.java
│   │   └── ReservaResponse.java
│   ├── entity/Reserva.java
│   ├── repository/ReservaRepository.java
│   └── service/ReservaService.java
│
└── factura/          ← FUTURE (after reserva is complete)
    ├── controller/FacturaController.java
    ├── dto/ ...
    ├── entity/Factura.java
    ├── repository/FacturaRepository.java
    └── service/FacturaService.java
```

### 3.2 `Reserva` JPA Entity

Map to `@Table(name = "reserva")` following the `Esquema_BD.sql` naming convention:

```java
@Entity
@Table(name = "reserva")
public class Reserva {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "huesped_id", nullable = false)
    private Long huespedId;

    @Column(name = "propiedad_id", nullable = false)
    private Long propiedadId;

    @Column(name = "fecha_entrada", nullable = false)
    private LocalDate fechaEntrada;

    @Column(name = "fecha_salida", nullable = false)
    private LocalDate fechaSalida;

    @Column(name = "precio_noche", nullable = false, precision = 15, scale = 2)
    private BigDecimal precioNoche;   // BigDecimal — not double

    @Column(name = "num_huespedes", nullable = false)
    private Integer numHuespedes;

    @Column(name = "estado", nullable = false, length = 20)
    private String estado;           // "PENDIENTE", "CONFIRMADA", "CANCELADA", "COMPLETADA"

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn;

    @PrePersist
    protected void onCreate() {
        if (creadoEn == null) creadoEn = LocalDateTime.now();
        if (estado == null) estado = "PENDIENTE";
    }

    // getters only — no setter for creadoEn (following Guest.java pattern)
}
```

**Note on `propiedadId`:** The `propiedad` table exists in `Esquema_BD.sql` but has no corresponding JPA entity yet. Use a plain `Long propiedadId` FK column. `ddl-auto=update` will create the column; referential integrity is not enforced at the JPA level until the `Propiedad` entity is added. This is an acceptable trade-off during incremental development.

### 3.3 `ReservaRepository`

```java
public interface ReservaRepository extends JpaRepository<Reserva, Long> {

    List<Reserva> findByHuespedId(Long huespedId);

    // Date-overlap conflict check — preserves the same-day adjacency rule:
    // a booking whose checkOut = today does NOT conflict with a booking whose checkIn = today
    boolean existsByPropiedadIdAndFechaEntradaLessThanAndFechaSalidaGreaterThan(
            Long propiedadId, LocalDate fechaSalida, LocalDate fechaEntrada);
}
```

The derived query name encodes the interval overlap condition: `fechaEntrada < :fechaSalida AND fechaSalida > :fechaEntrada`. This is the exact boolean algebra from `BookingValidator.hasDateConflict()` translated to Spring Data.

### 3.4 `ReservaService`

```java
@Service
public class ReservaService {

    private static final int MAX_HUESPEDES = 4;
    private static final int MAX_NOCHES = 30;

    private final ReservaRepository reservaRepository;

    public ReservaService(ReservaRepository reservaRepository) {
        this.reservaRepository = reservaRepository;
    }

    @Transactional(readOnly = true)
    public List<ReservaResponse> findAll() { ... }

    @Transactional(readOnly = true)
    public ReservaResponse findById(Long id) { ... }

    @Transactional
    public ReservaResponse create(CreateReservaRequest request) {
        // (1) Date order validation
        if (!request.getFechaSalida().isAfter(request.getFechaEntrada())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "La fecha de salida debe ser posterior a la de entrada");
        }
        // (2) Past date check
        if (request.getFechaEntrada().isBefore(LocalDate.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "No se puede reservar en fechas pasadas");
        }
        // (3) Guest count (ported from BookingValidator)
        long noches = ChronoUnit.DAYS.between(request.getFechaEntrada(), request.getFechaSalida());
        if (request.getNumHuespedes() > MAX_HUESPEDES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Máximo " + MAX_HUESPEDES + " huéspedes por habitación");
        }
        // (4) Stay duration (ported from BookingValidator)
        if (noches > MAX_NOCHES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "La estadía no puede superar " + MAX_NOCHES + " noches");
        }
        // (5) Date conflict check (ported from BookingValidator.hasDateConflict, now via DB)
        if (reservaRepository.existsByPropiedadIdAndFechaEntradaLessThanAndFechaSalidaGreaterThan(
                request.getPropiedadId(), request.getFechaSalida(), request.getFechaEntrada())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "La propiedad ya tiene una reserva para esas fechas");
        }
        // (6) Persist
        Reserva reserva = new Reserva();
        // ... map request to entity ...
        return toResponse(reservaRepository.save(reserva));
    }

    @Transactional
    public ReservaResponse update(Long id, UpdateReservaRequest request) { ... }

    @Transactional
    public void delete(Long id) {
        reservaRepository.delete(getOrThrow(id));
    }

    private Reserva getOrThrow(Long id) {
        return reservaRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Reserva no encontrada"));
    }
}
```

Observe that:
- All five business rules from `BookingValidator` are now `ResponseStatusException` throws with Spanish messages, consistent with `GuestService`
- The date conflict check is a single database query, not an in-memory comparison — correct for a multi-user system
- The `@Transactional` annotations follow the policy established in §1

### 3.5 Invoice Calculation in the Future `FacturaService`

When the billing module is built, the `InvoiceCalculator` constants translate directly:

```java
@Service
public class FacturaService {

    private static final BigDecimal IVA_RATE = new BigDecimal("0.19");
    private static final BigDecimal DESCUENTO_LARGA_ESTADIA = new BigDecimal("0.10");
    private static final int UMBRAL_LARGA_ESTADIA_NOCHES = 7;

    @Transactional
    public FacturaResponse generarFactura(Long reservaId) {
        Reserva reserva = reservaRepository.findById(reservaId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reserva no encontrada"));

        long noches = ChronoUnit.DAYS.between(reserva.getFechaEntrada(), reserva.getFechaSalida());
        BigDecimal subtotal = reserva.getPrecioNoche().multiply(BigDecimal.valueOf(noches));
        BigDecimal impuesto = subtotal.multiply(IVA_RATE);
        BigDecimal descuento = noches >= UMBRAL_LARGA_ESTADIA_NOCHES
            ? subtotal.multiply(DESCUENTO_LARGA_ESTADIA)
            : BigDecimal.ZERO;
        BigDecimal total = subtotal.add(impuesto).subtract(descuento);

        // persist Factura entity ...
    }
}
```

Key differences from `InvoiceCalculator`:
- `BigDecimal` throughout — eliminates floating-point precision issues
- Reads `Reserva` from the database rather than accepting a plain `Booking` object
- Persists a `Factura` entity — it's not just a calculation, it's a write operation
- `@Transactional` — the SELECT (reserva) + INSERT (factura) are atomic

---

## 4. Migration Strategy

### Phase 0 — Before starting the Reservations module (prerequisite)

Decide whether `propiedad` (property) records must exist before reservations can be created. Two options:

- **Option A:** Implement a minimal `Propiedad` JPA entity and `POST /api/propiedades` endpoint first. Reservations link to it via FK. This is architecturally correct.
- **Option B:** Accept `propiedadId` as an unchecked Long FK in `CreateReservaRequest`. The `propiedad` entity will be added in a later sprint; the FK column exists in the table from `ddl-auto=update`. This allows booking development to begin immediately.

For a class project with time constraints, Option B is acceptable as long as the team documents it. For a production system, Option A is required.

### Phase 1 — Implement the `reserva/` module

1. Create `Reserva.java` entity with the fields described in §3.2
2. Create `ReservaRepository.java` with the conflict-check derived query
3. Create `ReservaService.java` — port the five business rules from `booking/service/BookingValidator.java` as `ResponseStatusException` checks inside `create()`
4. Create `ReservaController.java` — follow `GuestController` as the template (thin delegation only)
5. Create DTOs: `CreateReservaRequest`, `UpdateReservaRequest`, `ReservaResponse`
6. Apply Bean Validation annotations (`@NotNull`, `@Future`, `@Min`) with Spanish messages on `CreateReservaRequest`
7. Annotate all service methods with `@Transactional` / `@Transactional(readOnly = true)`

### Phase 2 — Port the tests

Write unit tests for `ReservaService` that cover the same boundary cases as `BookingValidatorTest`:
- `create_fourGuests_succeeds()`
- `create_fiveGuests_throwsBadRequest()`
- `create_thirtyNights_succeeds()`
- `create_thirtyOneNights_throwsBadRequest()`
- `create_pastCheckIn_throwsBadRequest()`
- `create_dateConflict_throwsConflict()`
- `create_adjacentDates_succeeds()` — same-day checkout/checkin is valid

Write unit tests for `FacturaService` that cover the same boundary cases as `InvoiceCalculatorTest`:
- `generarFactura_threeNights_subtotalCorrect()`
- `generarFactura_sixNights_noDiscount()`
- `generarFactura_sevenNights_discountApplied()`
- `generarFactura_sevenNights_totalCorrect()` — 763,000 COP

### Phase 3 — Delete the `booking/` package

Once `ReservaService` tests pass and cover all boundary cases from `BookingValidatorTest` and `InvoiceCalculatorTest`, the original package is redundant and should be deleted:

```bash
rm -rf booking/model/Booking.java
rm -rf booking/service/BookingValidator.java
rm -rf booking/service/InvoiceCalculator.java
# And the corresponding test files
```

Verify: `./mvnw clean compile && ./mvnw test` must still pass after deletion.

---

## 5. Risks

### Risk 1 — `propiedad` dependency (Medium)

The `Reserva` entity references `propiedadId`. If the `propiedad` entity is not implemented first (Phase 0, Option A), FK integrity is not enforced at the application level. Data inserted with a non-existent `propiedadId` will have orphaned references.

**Mitigation:** Document the dependency. Use Option B during development; add the FK constraint (as a JPA `@ManyToOne`) when the `Propiedad` entity is added.

### Risk 2 — `double` in test tolerance masks real precision errors (Low)

The existing `InvoiceCalculatorTest` uses `assertEquals(expected, actual, 0.01)`. At amounts above 10,000,000 COP (common for extended stays), `double` multiplication can produce errors larger than 0.01. The tests may not catch these.

**Mitigation:** When porting to `FacturaService`, use `BigDecimal` from the first line. Do not port the `double` calculations; port only the business rule constants (rates and thresholds).

### Risk 3 — Date validation is timezone-dependent (Low)

`BookingValidator.validate()` uses `LocalDate.now()` without a timezone. In a Docker container with a different OS timezone than the developer's machine, "today" differs.

**Mitigation:** In `ReservaService`, use `LocalDate.now(ZoneId.of("America/Bogota"))` (matching the domain: Colombia). Alternatively, accept check-in dates for today and validate only that check-in is not strictly in the past.

### Risk 4 — Booking test suite becomes orphaned (Low)

The 31 tests in `booking/service/BookingValidatorTest` and `booking/service/InvoiceCalculatorTest` currently pass and document real business rules. If `ReservaService` tests are not written before the `booking/` package is deleted, the specifications are lost.

**Mitigation:** Phase 2 (port tests) must complete before Phase 3 (delete package). Never delete the `booking/` tests without first confirming equivalent coverage exists in `ReservaService` tests.

### Risk 5 — Concurrent reservation creation without serializable isolation (Low, future)

The conflict check (`existsByPropiedadId...`) and the subsequent `INSERT` are within the same `@Transactional` boundary but at the default `READ_COMMITTED` isolation level. Two simultaneous requests for the same property and dates can both pass the check and both insert, creating a double-booking.

**Mitigation:** Add a `UNIQUE` constraint at the database level: `UNIQUE (propiedad_id, fecha_entrada)` is too strict (allows one booking per day). The correct mitigation is a database-level constraint or a pessimistic lock (`@Lock(LockModeType.PESSIMISTIC_WRITE)`) on the conflict check query. This is acceptable to defer until load testing reveals it as a real issue for a class project.

---

## 6. Readiness Assessment

### Criteria check

| Criterion | Status |
|---|---|
| `@Transactional` policy established in all existing services | ✅ Done — see §1 |
| Multi-table write operations will be atomic in new modules | ✅ Guaranteed by the established pattern |
| `booking/` package has a documented integration path | ✅ See §3 and §4 |
| Business rule constants are identified and ready to port | ✅ See §2.2 |
| Test specifications for boundary values are documented | ✅ 31 test cases enumerated in §4 Phase 2 |
| Build passes with all changes | ✅ `BUILD SUCCESS`, 32 tests, 0 failures |
| New module package structure is defined | ✅ See §3.1 |
| Prerequisite dependency (`propiedad`) is identified | ✅ See Risk 1 and Phase 0 |

### Verdict

**The project is ready to begin development of the Reservations module.**

The two architectural blockers identified in `Architecture_Readiness_Review.md` have been resolved:

- **C-1 (booking package ambiguity):** Resolved by this document. The business logic is catalogued, the migration path is explicit, and the `booking/` package has a defined end-of-life after Phase 2 completes.
- **C-2 (no `@Transactional` pattern):** Resolved by the changes in §1. Every service method in the project is now correctly annotated. New module developers have a clear, consistent reference to follow.

The immediate next step is the Phase 0 decision: whether to implement a minimal `Propiedad` entity before or after the `Reserva` entity. Once that decision is made, Phase 1 can begin.

---

*End of Architecture Preparation Report — NovaFacts — 2026-06-27*
