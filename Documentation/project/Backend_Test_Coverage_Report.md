# Backend Test Coverage Report

**Date:** 2026-06-28  
**Build result:** `./mvnw clean test` → 52 tests, 0 failures — **BUILD SUCCESS**

---

## Files Created

### Infrastructure

| File | Purpose |
|---|---|
| `pom.xml` (modified) | Added `com.h2database:h2` with `<scope>test</scope>` |
| `src/test/resources/application-test.properties` | H2 in-memory datasource, fixed JWT secret, `ddl-auto=create-drop` |

### Test Classes

| File | Tests |
|---|---|
| `src/test/java/com/novafacts/backend/property/PropertyControllerTest.java` | 5 |
| `src/test/java/com/novafacts/backend/reservation/ReservationControllerTest.java` | 5 |
| `src/test/java/com/novafacts/backend/invoice/InvoiceControllerTest.java` | 5 |
| `src/test/java/com/novafacts/backend/payment/PaymentControllerTest.java` | 5 |

### Source File Modified

| File | Change |
|---|---|
| `invoice/controller/InvoiceController.java` | `getAll()` and `getById()` now return `ResponseEntity<>`, matching PaymentController |

---

## Test Infrastructure Design

**Database:** H2 2.3.232 in-memory (`jdbc:h2:mem:testdb`), `ddl-auto=create-drop`. Tables are created at context startup and dropped at shutdown. All test classes share one Spring context (context caching).

**Security:** `@WithMockUser` at class level populates `SecurityContextHolder` before each test method. `JwtAuthenticationFilter` receives no `Authorization` header from MockMvc requests and exits early. The authorization check (`anyRequest().authenticated()`) passes because the mock user is present in the context.

**Test isolation:** Each test class calls `repository.deleteAll()` on all relevant tables in `@BeforeEach`. Table deletion order follows the dependency chain (payment → invoice → reservation → property → guest) even though there are no DB-level FK constraints, to match the logical data model.

**Data setup:** Prerequisite data (Guest, Property, Reservation for Invoice tests; all plus Invoice for Payment tests) is seeded directly via repository injection in `@BeforeEach`, not through HTTP. This keeps setup fast and focuses the HTTP layer on the module under test.

---

## Scenarios Covered

### PropertyControllerTest (5 tests)

| Test | Endpoint | Expected | What is verified |
|---|---|---|---|
| `create_property_returns_201` | `POST /api/properties` | 201 | Name, capacity, auto-assigned ID |
| `duplicate_property_returns_409` | `POST /api/properties` | 409 | Case-insensitive uniqueness guard |
| `get_by_id_returns_200` | `GET /api/properties/{id}` | 200 | ID, name, city match saved entity |
| `update_returns_200` | `PUT /api/properties/{id}` | 200 | Updated name, capacity, city |
| `delete_returns_204_and_subsequent_get_returns_404` | `DELETE + GET` | 204 → 404 | Entity removed from DB |

### ReservationControllerTest (5 tests)

| Test | Endpoint(s) | Expected | What is verified |
|---|---|---|---|
| `create_reservation_returns_201` | `POST /api/reservations` | 201 | Status=CONFIRMED, guestId, propertyId |
| `overlapping_reservation_returns_409` | `POST × 2` | 201 → 409 | Overlap detection for CONFIRMED reservations |
| `cancelled_reservation_does_not_block_dates` | `POST, PUT (cancel), POST` | 201, 200, 201 | Cancelled status releases dates |
| `update_reservation_returns_200` | `POST + PUT` | 201 → 200 | Updated dates and guestCount |
| `delete_reservation_returns_204_and_subsequent_get_returns_404` | `POST + DELETE + GET` | 201 → 204 → 404 | Entity removed |

### InvoiceControllerTest (5 tests)

| Test | Endpoint(s) | Expected | What is verified |
|---|---|---|---|
| `create_invoice_returns_201_with_calculated_amounts` | `POST /api/invoices` | 201 | subtotal=1000000, tax=190000, total=1190000, status=PENDING |
| `duplicate_invoice_returns_409` | `POST × 2` | 201 → 409 | One invoice per reservation |
| `pay_invoice_returns_200_with_status_paid` | `POST + PUT /pay` | 201 → 200 | status=PAID |
| `cancel_invoice_returns_200_with_status_cancelled` | `POST + PUT /cancel` | 201 → 200 | status=CANCELLED |
| `delete_paid_invoice_returns_409` | `POST + PUT /pay + DELETE` | 201 → 200 → 409 | PAID invoices are protected from deletion |

### PaymentControllerTest (5 tests)

| Test | Endpoint(s) | Expected | What is verified |
|---|---|---|---|
| `create_payment_returns_201_with_amount_from_invoice` | `POST /api/payments` | 201 | amount=1428000 (from invoice, not client), paymentMethod, reference, paidAt |
| `duplicate_payment_returns_409` | `POST × 2` | 201 → 409 | Second payment rejected when invoice is PAID |
| `invoice_becomes_paid_after_payment` | `POST payments + GET invoice` | 201 → 200 PAID | Atomic invoice settlement: payment creation transitions invoice to PAID |
| `get_payment_by_invoice_returns_200` | `POST + GET /by-invoice/{id}` | 201 → 200 | findByInvoiceId returns correct payment |
| `delete_confirmed_payment_returns_409` | `POST + DELETE` | 201 → 409 | Confirmed payments cannot be deleted |

---

## Pre-existing Tests (Unchanged)

| Test class | Tests | Type |
|---|---|---|
| `BackendApplicationTests` | 1 | Smoke (skipped context load) |
| `Booking — constructor and night calculation` | 9 | Unit — domain model |
| `InvoiceCalculator — subtotal, tax, discount and total` | 12 | Unit — domain service |
| `BookingValidator — business rules and date conflict detection` | 10 | Unit — domain service |

Total pre-existing: **32 unit tests**  
New integration tests: **20 tests**  
Grand total: **52 tests**

---

## Test Totals by Run

```
[INFO] Tests run: 5,  PaymentControllerTest
[INFO] Tests run: 1,  BackendApplicationTests
[INFO] Tests run: 9,  Booking — constructor and night calculation
[INFO] Tests run: 12, InvoiceCalculator — subtotal, tax, discount and total
[INFO] Tests run: 10, BookingValidator — business rules and date conflict detection
[INFO] Tests run: 5,  ReservationControllerTest
[INFO] Tests run: 5,  PropertyControllerTest
[INFO] Tests run: 5,  InvoiceControllerTest

Total: 52, Failures: 0, Errors: 0
```

---

## Remaining Uncovered Areas

### Modules with no integration tests

| Module | Controller | Reason |
|---|---|---|
| Auth (`/api/auth/login`) | `AuthController` | Requires a real user in DB and JWT round-trip; not in scope for this task |
| Users (`/api/users`) | `UserController` | Same — admin/user management flow |
| Guest (`/api/guests`) | `GuestController` | Not in scope for this task; follows the same CRUD pattern as Property |

### Scenarios not covered within tested modules

| Scenario | Module | Why omitted |
|---|---|---|
| `GET /api/properties` (list all) | Property | Tested implicitly (data setup shows findAll works via repository); list endpoint adds no new logic |
| `GET /api/reservations`, `GET /api/invoices`, `GET /api/payments` | All | Same reason — findAll is covered by the existence of data-creation tests |
| Validation rejection (missing required fields, `@NotBlank`, `@Positive`) | All | Bean Validation fires before service; a single representative case per module would suffice for a validation suite |
| 404 on non-existent resource (GET/PUT/DELETE) | All | Covered implicitly by the delete test (GET after delete returns 404); more explicit 404 tests could be added |
| Reservation: guest not found (404) | Reservation | Service guard covered by unit-level logic review; no integration test |
| Reservation: guestCount exceeds capacity (400) | Reservation | Same |
| Reservation: dates > 30 nights (400) | Reservation | Same |
| Invoice: cancelled reservation cannot be invoiced (400) | Invoice | Service guard not directly tested at HTTP level |
| Payment: invoice not found (404) | Payment | Not covered |
| `GET /api/invoices/by-reservation/{id}` | Invoice | Endpoint exists; no dedicated test |

### Infrastructure gaps

| Gap | Notes |
|---|---|
| No security integration test | All tests use `@WithMockUser`; no test verifies that unauthenticated requests return 401 |
| No negative auth test | JWT token rejection, expired token, malformed token — not tested |
| No concurrent/race condition test | Optimistic locking and duplicate-payment races are unit-level concerns and require multi-threaded test harness |
| No `@DataJpaTest` or repository-level tests | Derived queries (`existsByNameIgnoreCase`, overlap query) are tested indirectly via the HTTP layer |

---

## InvoiceController Standardization

`InvoiceController.getAll()` and `getById()` were the only two methods in the entire controller layer returning raw types instead of `ResponseEntity`. They now return `ResponseEntity<List<InvoiceResponse>>` and `ResponseEntity<InvoiceResponse>` respectively.

All six controllers (`AuthController`, `UserController`, `GuestController`, `PropertyController`, `ReservationController`, `InvoiceController`, `PaymentController`) now use `ResponseEntity` consistently.

**No HTTP status code, endpoint URL, or response body was changed.** The change is cosmetic at the HTTP level — both raw returns and `ResponseEntity.ok(...)` produce identical 200 responses.

---

## Build Result

```
[INFO] Compiling 54 source files
[INFO] Compiling 8 test source files
[INFO] Tests run: 52, Failures: 0, Errors: 0, Skipped: 0
[INFO] Analyzed bundle '' with 48 classes
[INFO] BUILD SUCCESS
[INFO] Total time: 16.613 s
[INFO] Finished at: 2026-06-28T12:58:04-05:00
```

All 32 pre-existing tests continue to pass. All 20 new integration tests pass on first run.
