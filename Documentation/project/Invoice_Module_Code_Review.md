# Invoice Module Code Review

**Module:** `com.novafacts.backend.invoice`  
**Date:** 2026-06-27  
**Build state:** `./mvnw clean test` → 32 tests, 0 failures, BUILD SUCCESS  
**Files reviewed:** `Invoice.java`, `InvoiceStatus.java`, `InvoiceRepository.java`, `CreateInvoiceRequest.java`, `InvoiceResponse.java`, `InvoiceService.java`, `InvoiceController.java`

---

## 1. Critical Issues

### C-1 — `delete()` has no status guard — PAID invoices can be deleted

**File:** `InvoiceService.java:112-114`

```java
@Transactional
public void delete(Long id) {
    invoiceRepository.delete(getOrThrow(id));
}
```

**Why it is critical:** A `PAID` invoice represents a completed financial transaction. Deleting it destroys the audit trail for that transaction. In financial accounting, records of completed payments are immutable — they can only be reversed via counter-entries (credit notes, voids), never physically deleted. This violates that invariant.

**Impact:**
- A client can delete a PAID invoice via `DELETE /api/invoices/{id}` with no restriction.
- When the Payment module is introduced, a payment record will reference the invoice's ID. Deleting a PAID invoice leaves the payment record pointing to a non-existent invoice — a dangling foreign key in the financial ledger.
- An attacker who gains access can erase evidence of a completed payment (financial fraud vector).

**Concrete example:**
```bash
# 1. Create and pay invoice 1
POST /api/invoices → 201 { "id": 1, "status": "PENDING" }
PUT  /api/invoices/1/pay → 200 { "id": 1, "status": "PAID" }

# 2. Delete the paid invoice — currently succeeds
DELETE /api/invoices/1 → 204
# Invoice record is gone. No trace of the payment.
```

**Recommended fix:** Add a status guard in `delete()`:

```java
@Transactional
public void delete(Long id) {
    Invoice invoice = getOrThrow(id);
    if (invoice.getStatus() == InvoiceStatus.PAID) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
                "No se puede eliminar una factura pagada");
    }
    invoiceRepository.delete(invoice);
}
```

A stronger variant would only allow deleting `PENDING` invoices:

```java
if (invoice.getStatus() != InvoiceStatus.PENDING) {
    throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Solo se pueden eliminar facturas pendientes");
}
```

---

## 2. Medium Improvements

### M-1 — Race condition on status transitions: `pay()` and `cancel()` have no optimistic locking

**File:** `InvoiceService.java:89-108`

`pay()` and `cancel()` both follow the read-check-write pattern without any concurrency protection:

```java
Invoice invoice = getOrThrow(id);          // READ
if (invoice.getStatus() != PENDING) { ... } // CHECK
invoice.setStatus(PAID);
invoiceRepository.save(invoice);           // WRITE
```

Two concurrent requests on the same invoice can both read `PENDING` before either commits. The worst case is a simultaneous pay+cancel:

```
Thread A: reads invoice (PENDING) → status check passes → saves PAID
Thread B: reads invoice (PENDING) → status check passes → saves CANCELLED
Result: undefined — depends entirely on commit order
```

The final status `PAID` or `CANCELLED` is determined by which thread writes last, not by business intent. For PAID→PAID (two pay calls) the result is idempotent. For PAID vs CANCELLED (concurrent pay + cancel) the result is non-deterministic.

**Recommended fix:** Add `@Version Long version` to the `Invoice` entity. JPA will throw `OptimisticLockException` on the second commit, which Spring translates to a 409 via the generic exception handler:

```java
@Version
private Long version;
```

No other code changes required — Spring Data handles the version check automatically on `save()`.

---

### M-2 — Race condition on duplicate invoice creation

**File:** `InvoiceService.java:63-66`

```java
if (invoiceRepository.existsByReservationId(request.getReservationId())) {
    throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe una factura...");
}
// ... then save
```

Two concurrent POST requests for the same `reservationId` can both pass `existsByReservationId()` before either commits. The second `save()` will fail at the DB level (the `UNIQUE` constraint on `reserva_id` prevents actual data corruption), but the error is caught by `GlobalExceptionHandler.handleDataIntegrity()`, which returns:

```json
{ "error": "Conflicto de datos" }
```

instead of the informative `"Ya existe una factura para esta reserva"` from the service-level check. The DB constraint protects data integrity, but the client receives a generic message.

This is the same race pattern as in `ReservationService.create()` and is consistent with the project's other modules. It is not a regression, but worth documenting for future resolution.

---

### M-3 — `reserva_id` index is implicit, not explicitly declared

**File:** `Invoice.java:15`

```java
@Column(name = "reserva_id", nullable = false, unique = true)
private Long reservationId;
```

`unique = true` causes Hibernate to generate a B-tree index on `reserva_id` (implicit). However, this differs from the pattern established by `Reservation.java`, which explicitly declares its index in `@Table`:

```java
@Table(
    name = "reserva",
    indexes = { @Index(name = "idx_reserva_propiedad_id", columnList = "propiedad_id") }
)
```

The `findByReservationId()` and `existsByReservationId()` queries both filter on `reserva_id`. Both will use the implicit index, so there is no performance regression. However, the implicit index has an auto-generated name (e.g., `UK_...`) which is harder to identify in the DB schema, whereas a named index is self-documenting and easier to drop/rebuild in migrations.

**Recommended fix:** Make the index explicit in `@Table`:

```java
@Table(
    name = "factura",
    uniqueConstraints = @UniqueConstraint(name = "uq_factura_reserva_id", columnNames = "reserva_id"),
    indexes = { @Index(name = "idx_factura_reserva_id", columnList = "reserva_id") }
)
```

---

### M-4 — `findByReservationId()` is declared but never called

**File:** `InvoiceRepository.java:12`

```java
Optional<Invoice> findByReservationId(Long reservationId);
```

This method is required by the spec and declared correctly. However, neither `InvoiceService` nor any other class in the codebase currently calls it. The service uses `existsByReservationId()` for the duplicate check (more efficient — generates `SELECT count(*)` rather than `SELECT *`).

`findByReservationId` becomes valuable when the frontend needs to look up an invoice by reservation ID (e.g., a "view invoice for this booking" feature). A missing service method and controller endpoint (`GET /api/invoices/by-reservation/{reservationId}`) means that functionality is not exposed.

**Recommendation:** Either add `GET /api/invoices/by-reservation/{reservationId}` backed by `findByReservationId`, or document the method as a future entry point. The repository declaration itself is correct — the gap is in the service and controller.

---

### M-5 — `total` is not explicitly rounded

**File:** `InvoiceService.java:78`

```java
BigDecimal total = subtotal.add(tax);
```

This is mathematically correct: `subtotal` has scale 2 (explicitly set on line 75), `tax` has scale 2 (explicitly set on line 77), and `BigDecimal.add()` returns `max(scale1, scale2) = 2`. So `total` is always scale 2 without calling `.setScale()`.

However, this relies on an implicit Java specification rule. If a future developer modifies the calculation above (e.g., introduces a discount as `subtotal - discount`) and that intermediate value has a different scale, `total` could silently change its scale without a compilation error. Making the intent explicit is cheap and prevents this class of future bug:

```java
BigDecimal total = subtotal.add(tax).setScale(2, RoundingMode.HALF_UP);
```

---

### M-6 — `IVA_RATE` is hardcoded in the service class

**File:** `InvoiceService.java:26`

```java
private static final BigDecimal IVA_RATE = new BigDecimal("0.19");
```

For the current academic project this is acceptable. In any real deployment, the VAT rate changes by tax regulation. A rate change would require a code change, recompile, retest, and redeploy. This should come from `application.properties`:

```properties
novafacts.invoice.iva-rate=0.19
```

```java
@Value("${novafacts.invoice.iva-rate}")
private BigDecimal ivaRate;
```

Flagged as Medium because it will cause a problem the first time the IVA rate is updated.

---

## 3. Minor Improvements

### m-1 — `pay()` and `cancel()` follow an action-endpoint URL pattern

**File:** `InvoiceController.java:38-45`

```
PUT /api/invoices/{id}/pay
PUT /api/invoices/{id}/cancel
```

These are sub-resource action endpoints (RPC-over-REST). A stricter REST interpretation would use `PATCH /api/invoices/{id}` with a body `{"status": "PAID"}`. The action-endpoint style was explicitly specified and is consistent throughout the project. No change needed — flagged only as a stylistic awareness point.

---

### m-2 — `getAll()` returns every invoice for every user — no authorization filter

**File:** `InvoiceController.java:24-26`

Any authenticated user can retrieve all invoices for all guests via `GET /api/invoices`. This is consistent with how `GuestController`, `PropertyController`, and `ReservationController` behave. For a financial module this is more sensitive, but fixing it requires role-based access control which is an authorized-user-level concern outside this module's scope.

---

### m-3 — `findByReservationId` returns `Optional<Invoice>` but the repository-level uniqueness means it will always be 0 or 1

This is the correct return type for a unique-constrained lookup. No change needed — noted for documentation completeness.

---

### m-4 — `cancel()` method name shadows nothing but could be renamed for clarity

`cancel()` in `InvoiceService` does not conflict with any Java API or framework method. The name is accurate. However, `cancelInvoice(Long id)` would be slightly more specific as a private API hint that this is the invoice's own cancellation (distinct from a future method that might cancel related reservations). This is cosmetic.

---

## 4. Architecture Review: Invoice Module and Future Payment Module

### Current state

The Invoice module currently owns payment state directly via `InvoiceStatus.PAID` and exposes a public REST endpoint `PUT /api/invoices/{id}/pay` that transitions the status. There is no Payment entity, no payment record, and no financial transaction history.

### Question analysis

---

**Should Invoice own payment state?**

**Yes, partially** — Invoice must know whether it has been paid, because an invoice has a single lifecycle (PENDING → PAID or PENDING → CANCELLED). The `status` field on `Invoice` is correct. What should change is *who sets it*.

---

**Should Payment update Invoice?**

**Yes.** When the Payment module is introduced, a `Payment` entity will represent a financial transaction (amount, method, timestamp, reference). After a `Payment` is persisted, `InvoiceService.pay()` should be called internally by `PaymentService` as a side-effect. The Invoice status becomes a derived view of the payment record, not an independently settable value.

```java
// Future PaymentService.create()
@Transactional
public PaymentResponse create(CreatePaymentRequest request) {
    Invoice invoice = invoiceService.getOrThrow(request.getInvoiceId()); // or via repository
    // ... validate ...
    Payment payment = new Payment();
    // ... populate ...
    paymentRepository.save(payment);
    invoiceService.pay(invoice.getId());  // internal call, not through HTTP
    return toResponse(payment);
}
```

---

**Should `PUT /api/invoices/{id}/pay` continue to exist?**

**No, or admin-only.** This endpoint bypasses the payment ledger. A client could call it to mark an invoice as PAID without any corresponding payment record, creating a financial inconsistency (invoice says PAID, no payment transaction exists). When the Payment module ships:

- Option A — **Remove the endpoint.** Only payments can mark invoices PAID. Clean, no ambiguity.
- Option B — **Restrict to an admin role** via Spring Security's `@PreAuthorize("hasRole('ADMIN')")`. Allows manual override (e.g., cash payment recorded outside the system) while protecting against accidental misuse.

**Option A is recommended** if the project will have a complete Payment module. Option B if manual overrides are a realistic use case.

---

**Would it create duplicate business logic?**

**Yes, with the current design.** Two paths can mark an invoice PAID:

1. `PUT /api/invoices/{id}/pay` (public, no payment record)
2. `POST /api/payments` → `InvoiceService.pay()` (internal, with payment record)

Both call the same service method, but only path 2 leaves a financial trail. This duality must be resolved before the Payment module ships.

---

**Should invoices become PAID automatically after payments?**

**Yes.** The `PaymentService.create()` transaction should atomically: (1) persist the `Payment` record, and (2) call `invoiceService.pay()`. Both operations must succeed or neither should commit — they share the same `@Transactional` boundary. This guarantees that every PAID invoice has an associated payment record and every payment record corresponds to a PAID invoice.

---

**Recommendation summary**

| Decision | Recommendation |
|---|---|
| Does Invoice own `status`? | Yes — keep the field |
| Who sets `status = PAID`? | Only `PaymentService`, via `InvoiceService.pay()` internally |
| Keep `PUT /api/invoices/{id}/pay`? | Remove when Payment module ships (or restrict to admin) |
| Duplicate business logic risk? | Yes — must be resolved before Payment ships |
| Automatic PAID on payment? | Yes — same `@Transactional` boundary |

The `InvoiceService.pay()` method itself is well-designed and should stay. Only the public REST endpoint that exposes it needs to be reconsidered.

---

## 5. Things Already Well Implemented

### BigDecimal arithmetic is fully correct

`IVA_RATE = new BigDecimal("0.19")` — string constructor, no floating-point imprecision.  
`subtotal` and `tax` are both explicitly scaled to 2 with `HALF_UP`.  
`total = subtotal.add(tax)` produces scale 2 by Java BigDecimal contract.  
All monetary DB columns declare `precision = 15, scale = 2`.  
The calculation is: `subtotal = pricePerNight × nights`, `tax = subtotal × 0.19`, `total = subtotal + tax`. This is mathematically correct and internally consistent.

### Server-side monetary calculation — client cannot influence amounts

`CreateInvoiceRequest` has exactly one field: `reservationId`. The client sends no money. All monetary values are derived by the service from `pricePerNight` (property) and `checkIn`/`checkOut` (reservation). This is the correct design for an invoice module where financial amounts must be authoritative.

### Validation order in `create()` is optimal

```
1. Reservation exists?          → 404 (precondition for everything else)
2. Reservation not CANCELLED?   → 400 (catch semantic error before DB query)
3. Invoice already exists?      → 409 (avoid property lookup if duplicate)
4. Property exists?             → 404 (needed only for price)
5. Calculate and save
```

Each guard is evaluated in the cheapest-first order that prevents unnecessary database round-trips. No redundant queries.

### `reserva_id UNIQUE` at DB level

`@Column(unique = true)` on `reservationId` provides defense-in-depth behind the service-level `existsByReservationId` check. A race condition produces a DB-level `DataIntegrityViolationException` rather than a duplicate row. Data integrity is guaranteed regardless of concurrency.

### Status transitions are correctly guarded

`pay()` and `cancel()` both reject non-PENDING invoices with 409. This prevents: paying a CANCELLED invoice, cancelling a PAID invoice, double-paying. The guards are symmetric and exhaustive for the current three-status model.

### New invoices are always PENDING — status is non-injectable

`InvoiceStatus.PENDING` is hardcoded in `create()`. `CreateInvoiceRequest` has no `status` field. The client cannot create a PAID invoice directly. Consistent with the same pattern in `ReservationService` where status is system-managed.

### Entity never exposed through the API

`Invoice` is never returned from any controller method. All responses use `InvoiceResponse`. The entity's internal structure is completely hidden from clients.

### `EnumType.STRING` for `estado`

Stores `"PENDING"`, `"PAID"`, `"CANCELLED"` as VARCHAR strings. Resistant to enum constant reordering (which would corrupt `EnumType.ORDINAL` data). Self-documenting in the database.

### Transaction boundaries are exact

- Read methods: `@Transactional(readOnly = true)` ✓
- Write methods: `@Transactional` ✓
- Private helpers (`getOrThrow`, `toResponse`): not annotated — execute within the caller's transaction ✓
- All business logic within a single transaction boundary — no post-commit side effects ✓

### Cross-module dependencies are explicit and clean

`InvoiceService` injects `ReservationRepository` and `PropertyRepository` directly. No `@ManyToOne` JPA relationships. Module boundaries are enforced at the code level, not via lazy-loading behavior.

### Constructor injection throughout, no Lombok, no field injection

Consistent with all existing modules.

### GlobalExceptionHandler and SecurityConfig reused without modification

`ResponseStatusException` from the service is handled by the existing `GlobalExceptionHandler`. The existing `anyRequest().authenticated()` protects `/api/invoices/**` automatically. Zero changes to shared infrastructure.

### `findByReservationId` returns `Optional<Invoice>`

Correct return type for a unique-constrained lookup. The `Optional` wrapper makes absence explicit and avoids null return from a repository method.

### Spanish error messages consistent with the project

| Condition | Status | Message |
|---|---|---|
| Invoice not found | 404 | "Factura no encontrada" |
| Reservation not found | 404 | "Reserva no encontrada" |
| Property not found | 404 | "Propiedad no encontrada" |
| Cancelled reservation | 400 | "No se puede facturar una reserva cancelada" |
| Duplicate invoice | 409 | "Ya existe una factura para esta reserva" |
| Non-pending pay | 409 | "Solo se pueden pagar facturas pendientes" |
| Non-pending cancel | 409 | "Solo se pueden cancelar facturas pendientes" |

All are natural Spanish, consistent in tone with the rest of the project.

---

## 6. Readiness Verdict

**Conditionally ready for the Payment module. One critical fix required first.**

**C-1 (DELETE on PAID invoices)** must be fixed before Payment ships. The reason is not correctness of the current module in isolation — it is the integration risk: when `Payment` records begin referencing `factura.id`, deleting a PAID invoice will leave orphaned payment records pointing to a non-existent invoice. The fix is a two-line status guard in `delete()`.

**The architecture concern (M-3.1 / Section 4)** — the `PUT /api/invoices/{id}/pay` public endpoint — does not need to be resolved *before* building Payment, but it must be resolved *as part of the Payment module's design*. The recommended path is:

1. `PaymentService.create()` calls `invoiceService.pay()` internally.
2. `PUT /api/invoices/{id}/pay` is removed or restricted to admin role when Payment ships.

**All other issues are Medium or Minor and do not block Payment module development.**

The financial calculation (BigDecimal, IVA, HALF_UP, server-side amounts) is correct. The entity schema is production-appropriate. The service structure is clean and follows established project patterns. The module is the highest-quality implementation in the codebase in terms of financial correctness. Apply C-1, address the Payment integration design, and the project is ready to proceed.
