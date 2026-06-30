# Payment Module Code Review

**Module:** `com.novafacts.backend.payment`  
**Date:** 2026-06-27  
**Reviewer perspective:** Senior Spring Boot architect  
**Files reviewed:** `Payment.java`, `PaymentMethod.java`, `PaymentRepository.java`, `CreatePaymentRequest.java`, `PaymentResponse.java`, `PaymentService.java`, `PaymentController.java`  
**Cross-referenced:** `InvoiceService.java`, `Invoice.java`, `InvoiceRepository.java`, `SecurityConfig.java`, `GlobalExceptionHandler.java`

---

## Critical Issues

**None found.**

No incorrect business behavior, no data corruption path, no broken API contract, no security hole, and no financial integrity failure in any code path.

---

## Medium Issues

### M-1 — Concurrent duplicate payment produces non-deterministic error response

**File:** `PaymentService.java:68`

```java
if (paymentRepository.existsByInvoiceId(request.getInvoiceId())) {
    throw new ResponseStatusException(HttpStatus.CONFLICT,
            "La factura ya tiene un pago registrado");
}
```

The service-level duplicate guard runs before the INSERT. Under concurrent load, two threads for the same `invoiceId` can both pass `existsByInvoiceId()` before either commits. Data integrity is protected at all times by two layers:

- **Layer 1 — `@Version` on Invoice:** When both threads call `invoiceService.pay()`, the second thread's UPDATE on `factura` will detect that the version was already incremented by the first thread's commit. Hibernate throws `ObjectOptimisticLockingFailureException`, which the existing `GlobalExceptionHandler.handleGeneric()` converts to 500.

- **Layer 2 — `UNIQUE` constraint on `factura_id`:** If Thread A commits before Thread B reaches the `save()` flush, the DB rejects Thread B's INSERT with a `DataIntegrityViolationException`, which `GlobalExceptionHandler.handleDataIntegrity()` converts to 409 `{"error": "Conflicto de datos"}`.

The outcome for the losing thread is either a 409 with the wrong message or a 500, depending on which layer fires first — both are non-deterministic from the client's perspective. **Data is never corrupted** — this is purely an error-response consistency issue.

This is not a regression; the Invoice module has an identical race on `existsByReservationId`. It is the same accepted trade-off throughout the project.

---

### M-2 — `factura_id` unique index is implicit, not explicitly named

**File:** `Payment.java:15`

```java
@Column(name = "factura_id", nullable = false, unique = true)
private Long invoiceId;
```

`unique = true` causes Hibernate to generate a UNIQUE B-tree index on `factura_id` with an auto-generated name (e.g., `UK_pago_factura_id`). The index is present and queries against it are O(1), so there is no performance issue.

The minor concern is schema maintainability: the Reservation module explicitly declares its index with a chosen name via `@Table(indexes = @Index(name = "idx_reserva_propiedad_id", ...))`, making it identifiable in schema dumps and easier to reference in future migrations. An auto-named index is opaque.

The Invoice module follows the same implicit pattern for `reserva_id unique = true`. Payment is consistent with Invoice.

---

## Minor Issues

### m-1 — `@Version` on Payment entity is non-functional in the current implementation

**File:** `Payment.java:31`

```java
@Version
private Long version;
```

`@Version` prevents lost-update races on concurrent modifications to the same entity. Payment has no update endpoint — there is no code path that reads a Payment, modifies it, and saves it back. The version field is initialized to `0` on INSERT and never incremented after that.

For `delete()`, Hibernate does issue `DELETE FROM pago WHERE id = ? AND version = ?`, but since the version is always `0` (never changed), this always succeeds and the optimistic lock provides no additional protection over the `findById` check that precedes it.

The annotation is harmless and forward-safe — if a `PUT /api/payments/{id}` endpoint is added later, optimistic locking will work correctly without any entity change. It follows the same `@Version` convention used on `Invoice`. Flagged only for awareness that it provides no runtime protection today.

---

### m-2 — Invoice entity is loaded twice inside `create()`

**File:** `PaymentService.java:57` and (indirectly) `InvoiceService.java:99`

```java
// PaymentService.create() — line 57
Invoice invoice = invoiceRepository.findById(request.getInvoiceId())  // Load #1
        .orElseThrow(...);

// ... later at line 83
invoiceService.pay(request.getInvoiceId());
    // inside InvoiceService.pay():
    Invoice invoice = getOrThrow(id);  // Load #2 — same invoiceId
```

**There is no second SQL query.** Both calls are within the same Hibernate persistence context (same `@Transactional` boundary via `REQUIRED` propagation). JPA's first-level cache guarantees that `EntityManager.find()` for the same entity class and primary key returns the cached managed instance. The second `findById` is served from memory. ✓

The observable side effect is a redundant status check: PaymentService verifies `status == PENDING` at rule 2, and InvoiceService.pay() re-verifies the same condition on the same entity instance. Within a single transaction this is always consistent — the second check always passes because no other thread can modify the entity between these two lines within our transaction. The duplicate check is harmless noise.

The associated risk is message inconsistency: if the status check ever did fire inside `invoiceService.pay()`, the client would see `"Solo se pueden pagar facturas pendientes"` (InvoiceService's message) instead of `"La factura no puede pagarse"` (PaymentService's message). This path cannot be reached in single-threaded execution, and under concurrent execution the `@Version` on Invoice fires first. No behavioral consequence today.

---

### m-3 — `reference` allows empty string

**File:** `CreatePaymentRequest.java:17`

```java
@Size(max = 100, message = "La referencia no puede superar 100 caracteres")
private String reference;
```

`@Size(max = 100)` allows `""` (empty string, length 0). A client that sends `"reference": ""` will store an empty string instead of `null`. Semantically, `null` means "no reference provided" and `""` means "reference field was provided but is blank" — an ambiguous distinction with no current business consequence, but one that could cause confusion if future code checks `reference != null` to determine whether a reference exists.

Adding `@NotBlank` would reject blank strings but would require the field to always be present. The correct fix for an optional field is to strip and nullify on the service side (`if (reference != null && reference.isBlank()) reference = null;`) before saving. Since no business rule currently depends on this distinction, it is cosmetic.

---

### m-4 — PaymentController uses ResponseEntity consistently; InvoiceController does not

**File:** `PaymentController.java` vs. `InvoiceController.java`

Every `PaymentController` method returns `ResponseEntity<>`:

```java
public ResponseEntity<List<PaymentResponse>> getAll() { ... }
public ResponseEntity<PaymentResponse> getById(...) { ... }
```

`InvoiceController.getAll()` and `InvoiceController.getById()` return raw types:

```java
public List<InvoiceResponse> getAll() { ... }
public InvoiceResponse getById(...) { ... }
```

`PaymentController` is the more correct pattern — `ResponseEntity` allows explicit HTTP status and header control. The inconsistency is in `InvoiceController` (weaker), not in `PaymentController` (stronger). This is a pre-existing issue in the Invoice module; the Payment module did not introduce or worsen it.

---

## Section-by-Section Evaluation

### 1. Business Logic Correctness

| Rule | Status | Notes |
|---|---|---|
| Duplicate payments | Correct | Service check + DB UNIQUE constraint (defense-in-depth) |
| Invoice status transition | Correct | PENDING-only guard; `invoiceService.pay()` atomically sets PAID |
| Deletion rules | Correct | Blocked when invoice is PAID; returns 409 |
| Transactional consistency | Correct | Payment INSERT + invoice UPDATE in one `@Transactional` boundary |
| Rollback behavior | Correct | Any failure in `create()` rolls back both payment and invoice changes |
| Optimistic locking | Correct | `@Version` on Invoice prevents concurrent double-pay; `@Version` on Payment present but non-functional today (m-1) |
| Payment immutability | Correct | No PUT endpoint; no update path through service |
| Amount integrity | Correct | `amount = invoice.total`, read server-side; no client field for amount |

The ordering of guards in `create()` is correct and efficient:

```
1. Invoice exists?          → 404  (prerequisite for everything)
2. Invoice is PENDING?      → 409  (semantic check before DB query)
3. Payment already exists?  → 409  (cheaper count query before INSERT)
4. Set amount from invoice
5. Save payment
6. invoiceService.pay()     ← same transaction
```

Each guard is evaluated in dependency-first, cheapest-first order. No unnecessary work is done before a required guard.

---

### 2. Database Design

| Concern | Verdict |
|---|---|
| `factura_id` unique constraint | Present — `unique = true` creates implicit index |
| `monto` precision | NUMERIC(15,2) — matches `factura.total`; correct |
| Nullable columns | `reference` nullable ✓; all others `nullable = false` ✓ |
| Enum persistence | `EnumType.STRING` — resistant to reordering ✓ |
| `@PrePersist` | Sets `createdAt` once, `updatable = false` ✓ |
| No setter on `createdAt` | Correct ✓ |
| Future FK on `factura_id` | `BIGINT` matches `factura.id` (`BIGSERIAL`); adding `REFERENCES factura(id)` later requires no column type change ✓ |
| `paidAt` vs `createdAt` separation | Correct — business timestamp vs. record timestamp are decoupled |

The `version` column has no `@Column` annotation. JPA/Hibernate generates it as `version bigint not null` with the field name as the column name. Since "version" is the same in both languages, no Spanish name mapping is lost. Consistent with the `Invoice` entity.

---

### 3. REST API Consistency

| Endpoint | Method | Status | Notes |
|---|---|---|---|
| `/api/payments` | GET | 200 | `ResponseEntity<List<>>` ✓ |
| `/api/payments/{id}` | GET | 200 / 404 | `ResponseEntity<>` ✓ |
| `/api/payments/by-invoice/{invoiceId}` | GET | 200 / 404 | `ResponseEntity<>` ✓ |
| `/api/payments` | POST | 201 / 400 / 404 / 409 | `ResponseEntity.status(CREATED)` ✓ |
| `/api/payments/{id}` | DELETE | 204 / 404 / 409 | `ResponseEntity.noContent()` ✓ |

No endpoint naming inconsistency. `/by-invoice/{invoiceId}` mirrors `/by-reservation/{reservationId}` in the Invoice module. DTO separation is complete — no entity is exposed. `@Valid` on the POST body is present.

---

### 4. Security

| Concern | Status |
|---|---|
| Authentication | All endpoints require JWT via `anyRequest().authenticated()` ✓ |
| Amount injection | Impossible — no `amount` field in `CreatePaymentRequest` ✓ |
| Enum injection | `PaymentMethod` is a Java enum; invalid values rejected by deserialization (400) ✓ |
| XSS via `reference` | No risk — `reference` is stored and returned as a JSON string, never rendered as HTML ✓ |
| Authorization (role-based) | Absent — any authenticated user can read all payments, create, or delete. This is consistent with all other modules in the project and is a project-wide gap, not a Payment regression. |
| `PUT /api/invoices/{id}/pay` bypass | Pre-existing; not introduced by Payment module ✓ |

---

### 5. Performance

| Concern | Verdict |
|---|---|
| Invoice loaded twice in `create()` | No second SQL — JPA L1 cache serves the second `findById` from memory (m-2) |
| `existsByInvoiceId` query | `SELECT count(*) WHERE factura_id = ?` — uses the unique index; O(1) ✓ |
| `findByInvoiceId` query | Uses the unique index; O(1) ✓ |
| `findAll()` without pagination | Returns all rows — consistent with all other modules ✓ |
| N+1 risks | None — `toResponse()` maps only scalar fields already on the managed entity; no lazy-loading triggered ✓ |

---

### 6. Concurrency

| Scenario | Behavior |
|---|---|
| Concurrent payment on same invoice | Layer 1: Invoice `@Version` fires → 500. Layer 2: DB `UNIQUE` fires → 409. Data is always consistent; error response is non-deterministic (M-1). |
| Concurrent `pay()` via legacy endpoint + `POST /api/payments` | Same layered protection applies. |
| Concurrent payment + invoice cancel | `cancel()` requires PENDING; if payment completes first, invoice is PAID — `cancel()` returns 409. If cancel runs first (and succeeds, which requires PENDING), invoice is CANCELLED — payment's rule 2 returns 409. Either ordering is safe. |
| Concurrent delete of the same payment | Both threads call `paymentRepository.delete(payment)`. The second DELETE hits 0 rows (already deleted). Hibernate does not throw on 0-row DELETE for non-`@Version` entities. With `@Version`, Hibernate issues `DELETE WHERE id=? AND version=?` — if the first commit removed the row, the second attempt finds no matching row, and Hibernate throws `OptimisticLockException` → 500. In practice this is benign since delete is blocked for PAID invoices, but if two threads concurrently delete the same PENDING-invoice payment, the second gets a 500 instead of a 404. This is an edge case of an edge case. |

---

### 7. Integration with InvoiceService

**Correctness of `invoiceService.pay()` call:**

```java
invoiceService.pay(request.getInvoiceId());
```

`InvoiceService.pay(Long id)` takes the invoice's primary key. `request.getInvoiceId()` is the invoice's primary key. The parameter is correct. ✓

**Transaction atomicity:**

`PaymentService.create()` is `@Transactional`. `InvoiceService.pay()` is also `@Transactional`. Spring's default propagation is `REQUIRED`: when `invoiceService.pay()` is called from within an active transaction, it joins that transaction rather than starting a new one. Both the `paymentRepository.save()` (INSERT into pago) and the `invoiceRepository.save()` (UPDATE factura estado=PAID, version++) execute within the same database transaction and commit atomically. ✓

This guarantee holds because `invoiceService` in `PaymentService` is a Spring-managed bean injected via the constructor — calls to it go through the transactional proxy. ✓

**Post-commit state invariant:**

After a successful commit: exactly one `pago` row exists for the `factura_id`, and `factura.estado = 'PAID'`. After a rollback (any exception in `create()`): neither change is visible. This invariant is structurally enforced by the transaction boundary — not by application-level compensating logic. ✓

**Future extensibility:**

The `invoiceService.pay()` method is reused rather than duplicating its logic. If the Invoice pay logic ever changes (e.g., emitting events, writing audit logs), the Payment path automatically inherits those changes. The integration point is clean. ✓

---

### 8. Maintainability

| Concern | Verdict |
|---|---|
| Code duplication | None — `getOrThrow()` and `toResponse()` follow the established project pattern ✓ |
| Naming consistency — Java | English identifiers throughout ✓ |
| Naming consistency — DB | Spanish column names throughout ✓ |
| Package organization | `entity/`, `repository/`, `dto/`, `service/`, `controller/` — exact same structure as all other modules ✓ |
| Constructor injection | Used exclusively; no `@Autowired`; no field injection ✓ |
| No Lombok | Consistent with the rest of the project ✓ |

The module introduces no new patterns, no new abstractions, and no new infrastructure. A developer familiar with any other module in this project will understand the Payment module immediately.

---

## Things Already Well Implemented

**Amount can never be client-injected.** `CreatePaymentRequest` has no `amount` field. The amount is always read from `invoice.getTotal()` inside the service, making monetary fraud via the API impossible.

**Atomic two-table commit.** The payment record and the invoice status update are bound to a single transaction via Spring's `@Transactional` propagation chain. There is no window in which a payment exists without a PAID invoice or a PAID invoice exists without a payment record.

**Two-layer duplicate prevention.** The service-level `existsByInvoiceId()` check handles the common case cleanly. The DB-level `UNIQUE` constraint on `factura_id` handles the race condition. Neither layer alone is sufficient; together they cover all scenarios.

**`@Version` on Invoice protects concurrent pay transitions.** The `ObjectOptimisticLockingFailureException` on the losing thread causes a full rollback, including the payment save. No partial state can survive.

**Payment immutability is structural.** There is no PUT endpoint. There is no service method that updates an existing payment. The only write operations are create and delete. No code change is required to add this protection — its absence from the API makes it impossible.

**Delete guard is correctly scoped.** `delete()` checks `invoice.getStatus() == InvoiceStatus.PAID`. In normal operation, once a payment is successfully created, the invoice is atomically PAID and can never be deleted. The guard is a business rule enforcement, not a workaround.

**PaymentMethod enum prevents string injection.** Jackson's enum deserialization rejects unknown values with a 400 before the controller is even reached. No service-level validation of the enum value is needed.

**`paidAt` is server-side.** `LocalDateTime.now()` is set in the service, not read from the request. Clients cannot backdate or forward-date payments.

**All controller methods return `ResponseEntity`.** The Payment module is actually more consistent in this regard than the Invoice module, which returns raw types from `getAll()` and `getById()`. This is the better pattern.

**`reference` is correctly nullable.** No `@NotNull` constraint. Optional fields that are genuinely optional should not be forced non-null at the DB level. The column is nullable and the Java field type is `String` (nullable). ✓

**Spanish validation messages are consistent in tone with the rest of the codebase.**

---

## Production Readiness Verdict

**Ready for production within the scope of this project.**

There are no critical issues. The two medium findings (M-1: non-deterministic error message under concurrent duplicate payment; M-2: implicit index name) are shared patterns with the Invoice module and represent accepted trade-offs at this project's scale. All financial invariants — amount integrity, atomic settlement, no duplicate payments, payment immutability after settlement — are correctly enforced. The module is the most internally consistent controller in the codebase (full `ResponseEntity` usage on all methods) and the transaction integration with `InvoiceService` is architecturally correct.

The one behavioral gap worth addressing before scaling beyond a class project is M-1: under concurrent payment load, the client may receive either a 409 or a 500 for a duplicate attempt. For production traffic, pessimistic locking (`SELECT FOR UPDATE` on the invoice row inside `create()`) would make the error deterministic. That change requires a `@Query` annotation on `InvoiceRepository` and is a one-line SQL change — but it is out of scope for the current project specification.
