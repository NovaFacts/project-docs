# Final Backend Cleanup Report

**Date:** 2026-06-28  
**Build result:** `./mvnw clean test` → 52 tests, 0 failures — **BUILD SUCCESS**

---

## Overview

Two architectural cleanup tasks were performed immediately before frontend work begins. No business logic was modified. No DTOs, entities, or service methods were changed. The public API surface was reduced by one endpoint.

---

## TASK 1 — Handle optimistic locking globally

### Problem

Both `Invoice` and `Payment` entities carry a `@Version` field for optimistic locking. When a concurrent update causes a version mismatch, Hibernate throws `ObjectOptimisticLockingFailureException`. Before this change, that exception propagated up to the catch-all `Exception.class` handler in `GlobalExceptionHandler`, which returned:

```json
HTTP 500
{"error": "Error interno del servidor"}
```

A concurrent-modification conflict is not a server error — it is a client-actionable condition. The correct HTTP status is `409 Conflict`, and the response should tell the client what happened and what to do.

### Change

**File:** `src/main/java/com/novafacts/backend/common/GlobalExceptionHandler.java`

Added one import and one handler. No existing handler was modified.

**Import added:**
```java
import org.springframework.orm.ObjectOptimisticLockingFailureException;
```

**Handler added** (inserted before the catch-all `Exception.class` handler):
```java
@ExceptionHandler(ObjectOptimisticLockingFailureException.class)
public ResponseEntity<Map<String, String>> handleOptimisticLocking(ObjectOptimisticLockingFailureException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(Map.of("error", "El registro fue modificado por otro usuario. Intente nuevamente."));
}
```

### Before / After

| Scenario | Before | After |
|---|---|---|
| Concurrent invoice update (version mismatch) | HTTP 500, `{"error": "Error interno del servidor"}` | HTTP 409, `{"error": "El registro fue modificado por otro usuario. Intente nuevamente."}` |
| Concurrent payment update (version mismatch) | HTTP 500, `{"error": "Error interno del servidor"}` | HTTP 409, `{"error": "El registro fue modificado por otro usuario. Intente nuevamente."}` |

### Rationale

- `ObjectOptimisticLockingFailureException` is a recoverable condition: the client should re-fetch the resource, apply its changes, and retry. Returning 500 hides this fact.
- The handler is placed before the `Exception.class` catch-all so Spring picks the most specific handler.
- The Spanish message is consistent with the project's domain language convention.
- No service or entity code was touched.

---

## TASK 2 — Remove the legacy invoice pay endpoint

### Problem

The API exposed two independent ways to transition an invoice to `PAID`:

1. `PUT /api/invoices/{id}/pay` — called `InvoiceService.pay()` directly, with no payment record created.
2. `POST /api/payments` — created a `Payment` entity, then called `InvoiceService.pay()` in the same transaction.

This violated the domain invariant: **an invoice must only become PAID as a consequence of a payment being recorded**. The direct endpoint allowed invoices to be marked PAID without any corresponding payment record, creating a silent data inconsistency.

### Change

**File:** `src/main/java/com/novafacts/backend/invoice/controller/InvoiceController.java`

Removed the following endpoint entirely (5 lines):

```java
// REMOVED:
@PutMapping("/{id}/pay")
public ResponseEntity<InvoiceResponse> pay(@PathVariable Long id) {
    return ResponseEntity.ok(invoiceService.pay(id));
}
```

**What was NOT changed:**
- `InvoiceService.pay(Long id)` — method remains exactly as implemented; it is still called by `PaymentService.create()` inside the same transaction.
- `PaymentService` — no change of any kind.
- All DTOs — no change.
- All entities — no change.
- `InvoiceService` — no change.

### PaymentService still calls InvoiceService.pay()

`PaymentService.create()` contains (unchanged):

```java
invoiceService.pay(invoiceId);   // inside same @Transactional method
```

`POST /api/payments` remains the **only public API** capable of transitioning an invoice from `PENDING` to `PAID`.

### Rationale

- Architectural correctness: every payment must produce a `Payment` record. The direct endpoint bypassed this.
- Single source of truth: clients have exactly one way to settle an invoice.
- `InvoiceService.pay()` is an internal service method, not a public contract. Removing its HTTP exposure does not break any service-layer consumer.

---

## Test updates required by Task 2

Two tests in `InvoiceControllerTest` called the removed endpoint. They were updated to achieve the same assertions via `POST /api/payments`, which is the correct public path.

**File:** `src/test/java/com/novafacts/backend/invoice/InvoiceControllerTest.java`

### Changes in the test file

1. Added `PaymentRepository` import and field injection.
2. Added `paymentRepository.deleteAll()` at the start of `@BeforeEach` (before invoice cleanup, to respect logical deletion order).
3. Renamed and rewrote `pay_invoice_returns_200_with_status_paid` → `invoice_becomes_paid_via_payment_creation`:

**Before:**
```java
@Test
void pay_invoice_returns_200_with_status_paid() throws Exception {
    // ... create invoice ...
    mockMvc.perform(put("/api/invoices/" + invoiceId + "/pay"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PAID"));
}
```

**After:**
```java
@Test
void invoice_becomes_paid_via_payment_creation() throws Exception {
    // ... create invoice ...
    mockMvc.perform(post("/api/payments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"invoiceId\": %d, \"paymentMethod\": \"TRANSFER\"}".formatted(invoiceId)))
            .andExpect(status().isCreated());

    mockMvc.perform(get("/api/invoices/" + invoiceId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PAID"))
            .andExpect(jsonPath("$.id").value(invoiceId));
}
```

4. Updated `delete_paid_invoice_returns_409` to pay via `POST /api/payments` (same pattern as above) instead of the removed endpoint.

---

## Files modified

| File | Type | What changed |
|---|---|---|
| `src/main/java/com/novafacts/backend/common/GlobalExceptionHandler.java` | Source | Added `ObjectOptimisticLockingFailureException` import and handler |
| `src/main/java/com/novafacts/backend/invoice/controller/InvoiceController.java` | Source | Removed `PUT /{id}/pay` endpoint (5 lines) |
| `src/test/java/com/novafacts/backend/invoice/InvoiceControllerTest.java` | Test | Added `PaymentRepository` injection; updated 2 tests to use `POST /api/payments` |

---

## API contract changes

| Endpoint | Before | After |
|---|---|---|
| `PUT /api/invoices/{id}/pay` | HTTP 200, returns `InvoiceResponse` | **Removed — returns HTTP 404** |
| `POST /api/payments` | Unchanged | Unchanged — sole path to PAID |
| `PUT /api/invoices/{id}/cancel` | Unchanged | Unchanged |
| All other endpoints | Unchanged | Unchanged |
| `ObjectOptimisticLockingFailureException` | HTTP 500 | HTTP 409 |

**Frontend impact:** Any frontend code calling `PUT /api/invoices/{id}/pay` directly must be updated to use `POST /api/payments` instead. Since frontend work has not started yet, there is no existing code to migrate.

---

## Confirmation that PaymentService still calls InvoiceService.pay()

`PaymentService.create()` contains the following (unmodified):

```java
// inside @Transactional create() method:
InvoiceResponse invoice = invoiceService.findById(request.getInvoiceId());
// ... guards ...
invoiceService.pay(request.getInvoiceId());   // ← unchanged
```

The payment and the invoice status transition share the same transaction. If either fails, both roll back. This invariant is not affected by either task.

---

## Build result

```
[INFO] Compiling 54 source files
[INFO] Compiling 8 test source files
[INFO] Tests run: 52, Failures: 0, Errors: 0, Skipped: 0
[INFO] Analyzed bundle '' with 48 classes
[INFO] BUILD SUCCESS
[INFO] Total time: 14.897 s
[INFO] Finished at: 2026-06-28T13:16:05-05:00
```

## Test result

All 52 tests pass. No regressions.

| Test class | Tests | Result |
|---|---|---|
| `PaymentControllerTest` | 5 | PASS |
| `BackendApplicationTests` | 1 | PASS |
| `Booking — constructor and night calculation` | 9 | PASS |
| `InvoiceCalculator — subtotal, tax, discount and total` | 12 | PASS |
| `BookingValidator — business rules and date conflict detection` | 10 | PASS |
| `ReservationControllerTest` | 5 | PASS |
| `PropertyControllerTest` | 5 | PASS |
| `InvoiceControllerTest` | 5 | PASS |
| **Total** | **52** | **PASS** |
