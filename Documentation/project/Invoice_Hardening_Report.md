# Invoice Hardening Report

**Date:** 2026-06-27  
**Scope:** Invoice module hardening — 4 targeted improvements  
**Build result:** `./mvnw clean test` → 32 tests, 0 failures — **BUILD SUCCESS**

---

## Files Modified

| File | Tasks applied |
|---|---|
| `invoice/entity/Invoice.java` | Task 2 — optimistic locking |
| `invoice/service/InvoiceService.java` | Task 1, 3, 4 |
| `invoice/controller/InvoiceController.java` | Task 3 — new endpoint |

**Files NOT modified:** `InvoiceRepository.java`, `CreateInvoiceRequest.java`, `InvoiceResponse.java`, `InvoiceStatus.java`. No module outside `invoice/` was touched.

---

## Exact Behavioral Changes

### Task 1 — `DELETE /api/invoices/{id}` now rejects PAID invoices

**Before:**

```java
@Transactional
public void delete(Long id) {
    invoiceRepository.delete(getOrThrow(id));
}
```

PAID invoices could be deleted with no restriction.

**After:**

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

**New behavior:**

| Invoice status | Response |
|---|---|
| PENDING | 204 No Content (deleted) |
| CANCELLED | 204 No Content (deleted) |
| PAID | 409 Conflict `{"error": "No se puede eliminar una factura pagada"}` |
| Not found | 404 Not Found `{"error": "Factura no encontrada"}` |

The endpoint URL, HTTP method, and all other responses are unchanged.

---

### Task 2 — `Invoice` entity now has optimistic locking via `@Version`

**Before:** No `@Version` field. Concurrent status transitions (`pay()` and `cancel()`) could produce undefined final state.

**After:**

```java
@Version
private Long version;
```

Added between `status` and `createdAt` in the entity. JPA manages this field automatically:
- Initialized to `0` on first INSERT.
- Incremented on every UPDATE.
- On concurrent update, the second transaction throws `ObjectOptimisticLockingFailureException`, which Spring's exception translation converts to a `RuntimeException` caught by `GlobalExceptionHandler.handleGeneric()` → 500.

No getter, setter, or DTO field was added. The `version` column is generated in the DB by Hibernate (`ddl-auto=update`) as a `BIGINT NOT NULL` column. It is invisible to API clients.

---

### Task 3 — New endpoint: `GET /api/invoices/by-reservation/{reservationId}`

**Service method added** (`InvoiceService`):

```java
@Transactional(readOnly = true)
public InvoiceResponse findByReservationId(Long reservationId) {
    return invoiceRepository.findByReservationId(reservationId)
            .map(this::toResponse)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Factura no encontrada"));
}
```

Uses the pre-existing `findByReservationId(Long)` repository method (already declared, was unused).

**Controller endpoint added** (`InvoiceController`):

```java
@GetMapping("/by-reservation/{reservationId}")
public ResponseEntity<InvoiceResponse> getByReservationId(@PathVariable Long reservationId) {
    return ResponseEntity.ok(invoiceService.findByReservationId(reservationId));
}
```

**Behavior:**

| Condition | Response |
|---|---|
| Invoice found for reservation | 200 OK with `InvoiceResponse` body |
| No invoice for that reservation | 404 `{"error": "Factura no encontrada"}` |

**No new DTO was created.** The response reuses `InvoiceResponse` exactly as returned by all other invoice endpoints. The endpoint is protected by the existing `anyRequest().authenticated()` JWT filter.

---

### Task 4 — `total` now has an explicit scale declaration

**Before:**

```java
BigDecimal total = subtotal.add(tax);
```

Scale was implicitly 2 (by Java BigDecimal addition contract), but relied on the caller knowing this.

**After:**

```java
BigDecimal total = subtotal.add(tax).setScale(2, RoundingMode.HALF_UP);
```

The result is arithmetically identical (subtotal scale 2 + tax scale 2 = total scale 2 by both the implicit and explicit paths). The change makes the intent explicit and eliminates a future maintenance trap if either operand's scale is changed.

---

## Endpoint Added

```
GET /api/invoices/by-reservation/{reservationId}
```

| Property | Value |
|---|---|
| Method | GET |
| Path | `/api/invoices/by-reservation/{reservationId}` |
| Auth | Required (JWT Bearer) |
| Path variable | `reservationId` — Long |
| 200 response | `InvoiceResponse` (same schema as all other invoice endpoints) |
| 404 response | `{"error": "Factura no encontrada"}` |
| No request body | — |

---

## API Contracts Preserved

| Endpoint | Status |
|---|---|
| `GET /api/invoices` | Unchanged |
| `GET /api/invoices/{id}` | Unchanged |
| `POST /api/invoices` | Unchanged |
| `PUT /api/invoices/{id}/pay` | Unchanged |
| `PUT /api/invoices/{id}/cancel` | Unchanged |
| `DELETE /api/invoices/{id}` | Contract preserved — 204/404 unchanged; 409 added for PAID invoices (new guard, not a breaking change) |
| `GET /api/invoices/by-reservation/{reservationId}` | **New** |

No field was added or removed from any DTO. No error message was modified. No existing endpoint URL or HTTP method was changed.

---

## Build Result

```
[INFO] Compiling 47 source files
[INFO] Tests run: 32, Failures: 0, Errors: 0, Skipped: 0
[INFO] Analyzed bundle '' with 42 classes
[INFO] BUILD SUCCESS
[INFO] Total time: 5.547 s
```

## Test Result

**32 tests, 0 failures, 0 errors.** All pre-existing tests continue to pass. No new tests were added (no test runner is configured for integration or controller tests in this project).
