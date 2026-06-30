# Development Data Seeder Report

**Date:** 2026-06-28
**Build result:** 54 tests, 0 failures — **BUILD SUCCESS**
**File created:** `project-backend/src/main/java/com/novafacts/backend/config/DevelopmentDataSeeder.java`

---

## 1. Files Created / Modified

| Action | File |
|--------|------|
| **Created** | `src/main/java/com/novafacts/backend/config/DevelopmentDataSeeder.java` |

No existing file was modified. No controller, service, or entity was touched.

---

## 2. Why `@Profile("dev")` Was Used

`@Profile("dev")` restricts the `CommandLineRunner` bean to the `dev` Spring profile. Without this guard, the seeder would execute on every startup — including in CI, production, and integration tests.

**How to activate:**

```bash
# Maven dev server
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# Packaged JAR
java -jar backend.jar --spring.profiles.active=dev

# Environment variable
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
```

Integration tests run under `@ActiveProfiles("test")`, so the seeder bean is never instantiated during test execution. The 54-test suite confirms this — no interference.

---

## 3. Why Deterministic Random Generation Matters

The seeder uses `new Random(42)` (fixed seed) instead of `new Random()` (time-seeded). This means:

- Every developer who runs the seeder on a fresh database gets the **exact same dataset**.
- Guest-to-reservation assignments, invoice statuses, and payment methods are stable across runs.
- Bug reports can reference specific records ("reservation #7", "invoice #3") and every developer will have the same data at those IDs.
- Screenshots and demo videos remain reproducible.

Without a fixed seed, each seeder run produces a different distribution of statuses and assignments, making collaborative debugging unreliable.

---

## 4. How Duplicate Generation Is Prevented

The `alreadyPopulated()` method is called at the top of `run()` before any data is created:

```java
private boolean alreadyPopulated() {
    return userRepository.count() > 0
            || guestRepository.count() > 0
            || propertyRepository.count() > 0
            || reservationRepository.count() > 0
            || invoiceRepository.count() > 0
            || paymentRepository.count() > 0;
}
```

If **any** of the six tables contains at least one row, the seeder logs `"Development database already populated."` and exits immediately. This means:

- Restarting the application does not re-seed.
- Partial seeding (e.g., only users exist) also triggers the early exit, preventing inconsistent data.
- The check is OR-based: a single non-empty table is enough to abort.

To re-seed from scratch, all six tables must be cleared first (e.g., `docker compose down -v && docker compose up -d`).

---

## 5. Generated Dataset

### Users (5)

| Username    | Password     | Nombre           | Rol |
|-------------|--------------|------------------|-----|
| admin       | admin123     | Administrador    | 1   |
| maria       | maria123     | María García     | 2   |
| juan        | juan123      | Juan Pérez       | 2   |
| recepcion   | recepcion123 | Recepción        | 2   |
| pruebas     | pruebas123   | Usuario Pruebas  | 2   |

Passwords are BCrypt-encoded using the application's `PasswordEncoder` bean (`BCryptPasswordEncoder` from `SecurityConfig`). All five users can log in via `POST /api/auth/login`.

### Guests (20)

20 guests with realistic Colombian names, unique `CC` document numbers (`100000001`–`100000020`), phone numbers in the `315XXXXXXX` format, and emails derived from `firstName.lastName@example.com` (accent-stripped).

Sample:

| # | First Name | Last Name  | Document   | Email                         |
|---|-----------|------------|------------|-------------------------------|
| 1 | Juan      | Pérez      | 100000001  | juan.perez@example.com        |
| 2 | María     | Gómez      | 100000002  | maria.gomez@example.com       |
| 5 | Camila    | Herrera    | 100000005  | camila.herrera@example.com    |
| 9 | Sofía     | Moreno     | 100000009  | sofia.moreno@example.com      |

### Properties (10)

| Name                  | City       | Capacity | Price/Night (COP) |
|-----------------------|------------|----------|-------------------|
| Villa El Retiro       | Bogotá     | 6        | 350,000           |
| Casa Bosque           | Medellín   | 4        | 280,000           |
| Loft Chapinero        | Bogotá     | 2        | 180,000           |
| Apartamento Norte     | Bogotá     | 4        | 220,000           |
| Finca La Palma        | Medellín   | 8        | 450,000           |
| Casa Colonial         | Cartagena  | 6        | 650,000           |
| Penthouse Centro      | Bogotá     | 4        | 650,000           |
| Cabaña Mirador        | Bogotá     | 4        | 280,000           |
| Villa Campestre       | Cali       | 8        | 350,000           |
| Apartamento Salitre   | Bogotá     | 4        | 120,000           |

### Reservations (30)

Each property receives three reservations in non-overlapping time windows:

| Slot | Anchor             | Typical Status            | Purpose              |
|------|--------------------|---------------------------|----------------------|
| A    | ~7 weeks ago       | COMPLETED (80%), CANCELLED (20%) | Historic data  |
| B    | ~3 weeks ago       | COMPLETED (75%), CANCELLED (25%) | Recent past    |
| C    | 2–5 weeks from now | CONFIRMED (60%), CANCELLED (40%) | Future bookings |

Date offsets use `LocalDate.now().minusDays()` / `plusDays()` so they remain realistic regardless of when the seeder runs. No two CONFIRMED reservations for the same property overlap.

Guest count is randomly selected between 1 and the property's capacity (inclusive).

**Expected distribution with seed 42:** approximately 15–16 COMPLETED, 8–10 CONFIRMED, 5–7 CANCELLED.

### Invoices (≈22)

Generated for every non-CANCELLED reservation. Status is assigned based on the reservation state:

- **COMPLETED reservation** → PAID (6/7 probability), CANCELLED (1/7)
- **CONFIRMED reservation** → PENDING (2/3 probability), PAID (1/3)

Amount calculation mirrors `InvoiceService` exactly:

```
subtotal = pricePerNight × nights               (scale 2, HALF_UP)
tax      = subtotal × 0.19                      (scale 2, HALF_UP)
total    = subtotal + tax                        (scale 2, HALF_UP)
```

Sample invoice for Loft Chapinero (COP 180,000/night), 3-night stay:

```
subtotal = 180,000.00 × 3 = 540,000.00
tax      = 540,000.00 × 0.19 = 102,600.00
total    = 642,600.00
```

### Payments (≈11)

Created only for PAID invoices. Payment methods cycle as CASH → CARD → TRANSFER. Reference numbers:

- CASH payments: `null` (no reference)
- CARD / TRANSFER: `REF-00002`, `REF-00003`, `REF-00005`, `REF-00006`, …

`paidAt` is set to 30–150 minutes after the invoice's `createdAt`, simulating realistic payment processing time.

---

## 6. Architectural Decisions

### Use repositories, not services

Services enforce business rules via `ResponseStatusException` and assume HTTP context (e.g., `UserService.createUser()` hardcodes `nombre = email` and `rolId = 1`). The seeder needs to:

- Set proper `nombre` and `rolId` values for each user
- Persist reservations with COMPLETED/CANCELLED status directly (not via create+update lifecycle)
- Create invoices with PAID/CANCELLED status directly (not create+pay/cancel chain)

All business rules that the services enforce are upheld manually:
- Invoice amounts use the identical formula (`IVA_RATE = 0.19`, `HALF_UP`, `scale 2`)
- Invoices are not created for CANCELLED reservations
- Payments are not created for non-PAID invoices
- Guest counts never exceed property capacity
- Reservation date slots are non-overlapping per property

### Private instance variables for cross-phase references

`guests`, `properties`, `reservations`, and `invoices` are instance fields populated by each seed method and consumed by the next. This avoids extra repository queries and keeps the inter-phase data flow explicit.

### Relative date offsets over absolute months

`today.minusDays(52 + i * 2)` rather than `today.minusMonths(1).withDayOfMonth(3 + i)` prevents edge cases when:
- The current day-of-month is less than the target day (February/short months)
- The seeder runs at the start of a new month (making "slot B" fall in the future)

The relative approach guarantees slot A is always ~7 weeks past, slot B is always ~3 weeks past, and slot C is always 2–5 weeks future.

### Compact method structure

The class is organized into 6 seed methods + 3 helpers (`calcSubtotal`, `calcTax`, `toSlug`) + `alreadyPopulated` + `logSummary`. No method exceeds ~50 lines. The `buildReservation` private helper avoids repetition across the three slots in `seedReservations()`.

---

## 7. Assumptions

1. **`rolId` values are unconstrained integers.** There is no `rol` JPA entity or FK constraint. The seeder assigns `rolId = 1` for admin and `rolId = 2` for all other users.

2. **Document type is always `CC`.** The `tipo_documento` column has no check constraint. All 20 guests use `CC` (Cédula de Ciudadanía), the most common Colombian ID type.

3. **`paidAt` is approximately `now()`** (seeder run time plus a small offset). The Payment entity's `paidAt` column has no constraint tying it to any reservation or invoice date, so this is valid.

4. **The `@Version` field on `Invoice` and `Payment` initializes to 0 on insert.** This is standard JPA optimistic locking behavior and requires no manual initialization.

5. **No FK constraints are enforced at the JPA level** between `huesped_id` / `propiedad_id` in `reserva`, or between `reserva_id` in `factura`. The seeder uses real IDs from the entities it just persisted, so referential integrity holds in practice.

6. **The seeder does not create `guest/user` controller test records.** Integration test classes clean their data with `deleteAll()` in `@BeforeEach` and run under the `test` profile, which never activates the seeder.

---

## 8. Build and Test Results

```
[INFO] Tests run: 54, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

All 54 pre-existing tests continue to pass. The `@Profile("dev")` annotation ensures the seeder bean is not instantiated under `@ActiveProfiles("test")`, producing zero interference with the test context.
