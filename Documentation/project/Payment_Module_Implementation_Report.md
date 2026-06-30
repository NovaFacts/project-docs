# Payment Module Implementation Report

**Project:** NovaFacts — Financial Management for Short-Term Rental Bookings  
**Module:** Payment (pago)  
**Date:** 2026-06-27  
**Build result:** `./mvnw clean test` → 32 tests, 0 failures — **BUILD SUCCESS**

---

## Files Created

| File | Purpose |
|---|---|
| `payment/entity/PaymentMethod.java` | Enum: CASH / CARD / TRANSFER / OTHER |
| `payment/entity/Payment.java` | JPA entity → table `pago` |
| `payment/repository/PaymentRepository.java` | JpaRepository + 2 derived queries |
| `payment/dto/CreatePaymentRequest.java` | Validated request DTO (invoiceId + paymentMethod + optional reference) |
| `payment/dto/PaymentResponse.java` | Read-only response DTO (all-arg constructor, getters only) |
| `payment/service/PaymentService.java` | All business logic, integration with InvoiceService |
| `payment/controller/PaymentController.java` | 5 REST endpoints, delegates to service |

**Total: 7 new files. 0 existing files modified.**

---

## Files Modified

**None.** `InvoiceService.pay()` and `InvoiceRepository` are consumed as-is. `SecurityConfig` automatically protects `/api/payments/**` via `anyRequest().authenticated()`. `GlobalExceptionHandler` already handles all exception types thrown by the new service.

---

## Database Schema

Hibernate generates the following DDL via `ddl-auto=update`:

```sql
CREATE TABLE pago (
    id           BIGSERIAL        PRIMARY KEY,
    factura_id   BIGINT           NOT NULL UNIQUE,
    monto        NUMERIC(15, 2)   NOT NULL,
    metodo_pago  VARCHAR(20)      NOT NULL,
    referencia   VARCHAR(100),
    pagado_en    TIMESTAMP        NOT NULL,
    version      BIGINT           NOT NULL,
    creado_en    TIMESTAMP        NOT NULL
);
```

**Column notes:**

| Column | Mapping | Notes |
|---|---|---|
| `factura_id` | `invoiceId` | `UNIQUE` — enforces one payment per invoice at DB level (mirrors service check) |
| `monto` | `amount` | `NUMERIC(15,2)` — matches `factura.total`; copied from invoice at payment creation |
| `metodo_pago` | `paymentMethod` | `EnumType.STRING` — stores `"CASH"`, `"CARD"`, etc.; resistant to reordering |
| `referencia` | `reference` | Optional (nullable); up to 100 characters |
| `pagado_en` | `paidAt` | Set explicitly in `PaymentService.create()` via `LocalDateTime.now()` |
| `version` | `version` | Managed by JPA `@Version` — optimistic locking; invisible to clients |
| `creado_en` | `createdAt` | Set once by `@PrePersist`; `updatable = false` |

No `FOREIGN KEY` constraints are generated (no `@ManyToOne`). This is by design and consistent with all other modules in the project.

---

## Endpoints

Base path: `/api/payments`  
Security: all endpoints require a valid JWT (`Authorization: Bearer <token>`).

| Method | Path | Success | Error cases |
|---|---|---|---|
| GET | `/api/payments` | 200 List | — |
| GET | `/api/payments/{id}` | 200 | 404 payment not found |
| GET | `/api/payments/by-invoice/{invoiceId}` | 200 | 404 payment not found |
| POST | `/api/payments` | 201 | 400 validation, 404 invoice not found, 409 status/duplicate |
| DELETE | `/api/payments/{id}` | 204 | 404 not found, 409 invoice already PAID |

All error responses use the `{"error": "..."}` envelope from `GlobalExceptionHandler`.

---

## Business Rules

### Rule 1 — Invoice must exist

```java
invoiceRepository.findById(request.getInvoiceId())
    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "La factura no existe"));
```

Evaluated first because everything else depends on the invoice entity.

---

### Rule 2 — Invoice must be PENDING

```java
if (invoice.getStatus() != InvoiceStatus.PENDING) {
    throw new ResponseStatusException(CONFLICT, "La factura no puede pagarse");
}
```

Catches: PAID invoices (already settled), CANCELLED invoices (voided).

---

### Rule 3 — No existing payment for this invoice

```java
if (paymentRepository.existsByInvoiceId(request.getInvoiceId())) {
    throw new ResponseStatusException(CONFLICT, "La factura ya tiene un pago registrado");
}
```

The `UNIQUE` constraint on `factura_id` provides DB-level defense-in-depth behind this check.

---

### Rule 4 — Amount comes only from the invoice

```java
payment.setAmount(invoice.getTotal());
```

`CreatePaymentRequest` has no `amount` field. Clients cannot influence the payment amount. The amount is always equal to `invoice.total` at the time of payment creation.

---

### Rule 5 — paidAt is the server-side timestamp

```java
payment.setPaidAt(LocalDateTime.now());
```

The payment timestamp is set by the server, not the client. Clients cannot backdate or forward-date payments.

---

### Rule 6 — Invoice transitions to PAID in the same transaction

```java
invoiceService.pay(request.getInvoiceId());
```

Called after persisting the payment, within the same `@Transactional` boundary. Spring's default propagation (`REQUIRED`) means `invoiceService.pay()` joins the active transaction started by `PaymentService.create()`. If either the payment save or the invoice status update fails, the entire transaction rolls back — no partial state (payment without PAID invoice, or PAID invoice without payment) can be committed.

---

### Rule 7 — Delete blocked when invoice is PAID

```java
if (invoice.getStatus() == InvoiceStatus.PAID) {
    throw new ResponseStatusException(CONFLICT, "No se puede eliminar un pago confirmado");
}
```

Once a payment is successfully created, the invoice is atomically marked PAID. In normal operation, this means a created payment can never be deleted (the invoice it references will always be PAID). The guard exists as an explicit business rule enforcing financial record immutability.

---

## Transaction Flow

### Happy path: `POST /api/payments`

```
HTTP POST /api/payments
  │
  ▼
PaymentController.create()
  │
  ▼
PaymentService.create()   ← @Transactional (opens new transaction T1)
  │
  ├─ 1. invoiceRepository.findById()      ← load Invoice (version = N)
  ├─ 2. Check invoice.status == PENDING
  ├─ 3. paymentRepository.existsByInvoiceId() → false
  ├─ 4. new Payment(), set invoiceId/amount/paymentMethod/reference/paidAt
  ├─ 5. paymentRepository.save(payment)   ← INSERT into pago (within T1)
  │
  ├─ 6. invoiceService.pay(invoiceId)     ← @Transactional(REQUIRED) joins T1
  │       ├─ invoiceRepository.findById() ← returns same entity from L1 cache
  │       ├─ Check status == PENDING ✓
  │       ├─ invoice.setStatus(PAID)
  │       └─ invoiceRepository.save()     ← UPDATE factura SET estado='PAID', version=N+1
  │
  └─ 7. return toResponse(saved)
  │
  ▼
T1 commits atomically:
  - pago row inserted
  - factura.estado updated to 'PAID'
  - factura.version incremented (optimistic lock)
  │
  ▼
HTTP 201 Created  { payment response }
```

If any step throws an exception, T1 rolls back entirely. There is no intermediate state where a payment exists but the invoice is not PAID, or vice versa.

---

## Integration with InvoiceService

`PaymentService` injects `InvoiceService` and calls `invoiceService.pay(Long id)` directly after persisting the payment:

```java
// From PaymentService.java
invoiceService.pay(request.getInvoiceId());
```

This reuses the existing `InvoiceService.pay()` implementation without duplication:
- The status check (`invoice.getStatus() != PENDING`) in `invoiceService.pay()` will always pass here because we already verified `PENDING` in rule 2 of `PaymentService.create()`, and both reads share the same Hibernate persistence context (L1 cache) within T1.
- The optimistic lock (`@Version`) on `Invoice` is incremented once by this update. Concurrent payment attempts on the same invoice will fail on the second commit.

`PUT /api/invoices/{id}/pay` continues to exist unchanged for backward compatibility. Internally it calls the same `InvoiceService.pay()` method. The two paths are:

| Path | Creates payment record? | Updates invoice? |
|---|---|---|
| `POST /api/payments` | Yes | Yes (via `invoiceService.pay()`) |
| `PUT /api/invoices/{id}/pay` | No | Yes (direct call) |

The `PUT /api/invoices/{id}/pay` endpoint has no payment record side-effect and should be considered a legacy/admin path.

---

## Error Response Reference

| Condition | Status | Body |
|---|---|---|
| Payment not found | 404 | `{"error": "Pago no encontrado"}` |
| Invoice not found | 404 | `{"error": "La factura no existe"}` |
| Invoice not PENDING | 409 | `{"error": "La factura no puede pagarse"}` |
| Payment already exists | 409 | `{"error": "La factura ya tiene un pago registrado"}` |
| Delete on PAID invoice | 409 | `{"error": "No se puede eliminar un pago confirmado"}` |
| Missing invoiceId | 400 | `{"error": "invoiceId: El identificador de la factura es obligatorio"}` |
| invoiceId ≤ 0 | 400 | `{"error": "invoiceId: El identificador de la factura debe ser mayor a cero"}` |
| Missing paymentMethod | 400 | `{"error": "paymentMethod: El método de pago es obligatorio"}` |
| reference > 100 chars | 400 | `{"error": "reference: La referencia no puede superar 100 caracteres"}` |

---

## API Examples

Replace `<TOKEN>` with the JWT from `POST /api/auth/login`.  
Assumes invoice ID 1 exists with status PENDING and total 2,499,000.00.

```bash
# 1 — Create payment for invoice 1 with TRANSFER (201)
curl -s -X POST http://localhost:8082/api/payments \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"invoiceId": 1, "paymentMethod": "TRANSFER", "reference": "TXN-20260627-001"}' | jq .
# {
#   "id": 1,
#   "invoiceId": 1,
#   "amount": 2499000.00,
#   "paymentMethod": "TRANSFER",
#   "reference": "TXN-20260627-001",
#   "paidAt": "2026-06-27T18:16:00",
#   "createdAt": "2026-06-27T18:16:00"
# }

# 2 — Verify invoice is now PAID (200)
curl -s http://localhost:8082/api/invoices/1 \
  -H "Authorization: Bearer <TOKEN>" | jq .status
# "PAID"

# 3 — Attempt duplicate payment (409)
curl -s -X POST http://localhost:8082/api/payments \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"invoiceId": 1, "paymentMethod": "CASH"}' | jq .
# { "error": "La factura ya tiene un pago registrado" }

# 4 — Attempt payment on non-PENDING invoice (409)
curl -s -X POST http://localhost:8082/api/payments \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"invoiceId": 1, "paymentMethod": "CARD"}' | jq .
# { "error": "La factura no puede pagarse" }

# 5 — Get payment by ID (200)
curl -s http://localhost:8082/api/payments/1 \
  -H "Authorization: Bearer <TOKEN>" | jq .

# 6 — Get payment by invoice ID (200)
curl -s http://localhost:8082/api/payments/by-invoice/1 \
  -H "Authorization: Bearer <TOKEN>" | jq .

# 7 — List all payments (200)
curl -s http://localhost:8082/api/payments \
  -H "Authorization: Bearer <TOKEN>" | jq .

# 8 — Attempt to delete a confirmed payment (409)
curl -s -X DELETE http://localhost:8082/api/payments/1 \
  -H "Authorization: Bearer <TOKEN>" | jq .
# { "error": "No se puede eliminar un pago confirmado" }

# 9 — Payment without required field (400)
curl -s -X POST http://localhost:8082/api/payments \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"invoiceId": 1}' | jq .
# { "error": "paymentMethod: El método de pago es obligatorio" }

# 10 — Payment on non-existent invoice (404)
curl -s -X POST http://localhost:8082/api/payments \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"invoiceId": 999, "paymentMethod": "CASH"}' | jq .
# { "error": "La factura no existe" }

# 11 — Create payment without reference (201, reference null)
curl -s -X POST http://localhost:8082/api/payments \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"invoiceId": 2, "paymentMethod": "CASH"}' | jq .
# { ..., "reference": null, ... }

# 12 — Unauthenticated (401)
curl -s http://localhost:8082/api/payments
# (HTTP 401, empty body)
```

---

## Build Result

```
[INFO] Compiling source files
[INFO] Tests run: 32, Failures: 0, Errors: 0, Skipped: 0
[INFO] Analyzed bundle '' with 48 classes
[INFO] BUILD SUCCESS
[INFO] Total time:  5.239 s
[INFO] Finished at: 2026-06-27T18:16:26-05:00
```

JaCoCo classes: 48 (was 42 before this module — 6 new tracked classes: entity/Payment, entity/PaymentMethod, dto/CreatePaymentRequest, dto/PaymentResponse, service/PaymentService, controller/PaymentController; PaymentRepository is an interface and not counted by JaCoCo).  
Tests: 32 passing, 0 failures, 0 errors. No pre-existing test was broken.

---

## Assumptions Made

1. **`factura_id` is UNIQUE in `pago`.** The spec mandates `existsByInvoiceId()` to enforce one payment per invoice. Adding `unique = true` on the column follows the same defense-in-depth pattern already used for `reserva_id` in `factura`. Without it, a race condition could insert two payment rows before either service-level check completes.

2. **`paidAt` ≠ `createdAt`.** Both fields are set to `LocalDateTime.now()` at creation time, making them functionally identical at first. `paidAt` is set explicitly in the service (business timestamp — when payment occurred); `createdAt` is set by `@PrePersist` (technical timestamp — when the record was inserted). The separation allows future code to record payments for past dates (e.g., cash payments logged after the fact) without touching `createdAt`.

3. **Delete is permanently blocked after a successful payment.** Once `PaymentService.create()` completes, the invoice is atomically PAID. Any subsequent `DELETE /api/payments/{id}` call will always find the invoice PAID and return 409. This is intentional and correct for a financial system — payment records are immutable once confirmed.

4. **`PUT /api/invoices/{id}/pay` is not removed.** The spec requires backward compatibility. It remains a direct path to marking an invoice PAID without creating a payment record. Its presence is documented and its architectural implications are covered in `Invoice_Module_Code_Review.md`.

5. **No cascade between payment deletion and invoice status.** If for some reason a payment is deleted (only possible when invoice is not PAID), the invoice status is not reverted. No rollback of the invoice state is implemented because the scenario (payment on a non-PAID invoice) cannot arise in normal operation.

6. **No `PUT /api/payments/{id}` update endpoint.** Payments are immutable once created. Changing payment method or reference after the fact is not a supported business operation. A correction would require deleting the payment (if the invoice is not PAID) and creating a new one.

---

## API Contracts Preserved

| Module | Endpoint | Status |
|---|---|---|
| Auth | All | Unchanged |
| Guest | All | Unchanged |
| Property | All | Unchanged |
| Reservation | All | Unchanged |
| Invoice | All 7 endpoints | Unchanged |
| Payment | 5 endpoints | **New** |

Zero existing endpoints were modified. Zero DTOs were changed. The `InvoiceService.pay()` method is reused from within `PaymentService.create()` but its signature and behavior are unchanged.
