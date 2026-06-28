# Frontend Payment Module Report

**Date:** 2026-06-28  
**Build result:** `npm run build` → 115 modules transformed — **BUILD SUCCESS (0 warnings)**

---

## 1. Files Created

| File | Purpose |
|---|---|
| `src/views/PaymentsView.vue` | Complete Payment management page (register, delete, lookup by invoice) |

---

## 2. Files Modified

| File | What changed |
|---|---|
| `src/components/AppNav.vue` | Added `<router-link to="/payments">Pagos</router-link>` |
| `src/router/index.js` | Added `PaymentsView` import and `/payments` route under `AppLayout` children |

No service files, type files, shared CSS, or backend code were modified.

---

## 3. Architectural Decisions

### Three `useAsyncState` instances

Payments has three distinct async operations. Each gets its own independent `useAsyncState()`:

```typescript
const { loading: isLoading, error: errorMessage, run: runLoad }         = useAsyncState();
const { loading: isSubmitting, error: modalError, run: runSubmit }      = useAsyncState();
const { loading: isDeleting, error: deleteError, run: runDelete }       = useAsyncState();
const { loading: isLookingUp, error: lookupError,
        clearError: clearLookupError, run: runLookup }                  = useAsyncState();
```

Load errors replace the page content. Modal errors appear inside the create modal. Delete errors appear inside the delete confirmation modal. Lookup errors appear as `.page-error` banners above the table.

### `Promise.all` for page load

Payments and invoices are fetched together on mount:

```typescript
[payments.value, invoices.value] = await Promise.all([
  getPayments(),
  getInvoices(),
]);
```

Invoices are loaded eagerly to populate the "Factura" select in the register modal without any extra delay. This follows the same approach as ReservationsView (guests + properties) and InvoicesView (invoices + reservations).

### Invoice list refreshed after payment creation

When a payment is created, the backend atomically marks the associated invoice as PAID. The frontend reflects this immediately by re-fetching invoices inside the `runSubmit` callback after the payment is pushed to the list:

```typescript
const created = await createPayment(payload);
payments.value.push(created);
invoices.value = await getInvoices();  // sync invoice status (PENDING → PAID)
```

Without this step, the invoice select would continue to show the now-PAID invoice as PENDING until the user reloads the page.

### Lookup without page reload

`GET /api/payments/by-invoice/{invoiceId}` uses its own `runLookup` async state. The result is stored in `lookupResult` and shown through the `displayedPayments` computed:

```typescript
const displayedPayments = computed<Payment[]>(() =>
  isLookupActive.value
    ? (lookupResult.value ? [lookupResult.value] : [])
    : payments.value
);
```

The main `payments` list is never altered by a lookup. Clearing the lookup restores the full list with no API call.

### Delete modal always shown (backend rejects)

Once a payment is created, the backend atomically marks the invoice PAID. Because `PaymentService.delete()` checks `invoice.getStatus() == InvoiceStatus.PAID` and throws 409, the delete operation will always be rejected. The delete button and modal are still present so the frontend remains honest about the endpoint's existence and the backend error is surfaced verbatim. This also handles edge cases (e.g., a partially failed transaction leaving an orphaned payment on a non-PAID invoice).

### No update endpoint — payments are immutable

There is no `PUT /api/payments/{id}` endpoint. The create modal does not have an edit mode. Following the backend's design, once a payment is registered it cannot be modified.

### `reference` field is optional — sent only when non-empty

```typescript
const ref = form.value.reference.trim();
const payload: CreatePaymentRequest = {
  invoiceId: parseInt(form.value.invoiceId, 10),
  paymentMethod: form.value.paymentMethod as PaymentMethod,
  ...(ref ? { reference: ref } : {}),
};
```

Omitting `reference` when empty matches the `CreatePaymentRequest` type (`reference?: string`). The backend stores it as nullable.

### Payment method labels (Spanish)

| Value | Label |
|---|---|
| `CASH` | Efectivo |
| `CARD` | Tarjeta |
| `TRANSFER` | Transferencia |
| `OTHER` | Otro |

Labels are defined as a `Record<PaymentMethod, string>` constant, not inline ternaries.

### Invoice select shows all invoices with status

All invoices (PENDING, PAID, CANCELLED) are shown in the select with their status label. Only PENDING ones can be paid — the backend is the enforcer (409: "La factura no puede pagarse"). This avoids any risk of the frontend state being stale (e.g., an invoice that was just paid in another tab would correctly be rejected by the backend).

### Scoped `.form-optional` style

A single scoped style (`.form-optional`) was added to indicate the Reference field is optional. It is intentionally scoped to `PaymentsView` rather than added to `shared.css` because it is presentation-only and not part of any reusable pattern.

---

## 4. API Endpoints Consumed

| Method | Endpoint | Service function | Trigger |
|---|---|---|---|
| `GET` | `/api/payments` | `getPayments` | `onMounted` + "Reintentar" |
| `GET` | `/api/invoices` | `getInvoices` | `onMounted` + "Reintentar" (parallel); again after payment creation |
| `GET` | `/api/payments/by-invoice/{invoiceId}` | `getPaymentByInvoice` | "Buscar" button or Enter key in lookup bar |
| `POST` | `/api/payments` | `createPayment` | Submit register modal |
| `DELETE` | `/api/payments/{id}` | `deletePayment` | Confirm delete modal |

No Axios imports in `PaymentsView.vue`. All calls go through the typed service layer.

---

## 5. Validation Strategy

### Frontend (synchronous, before API call)

| Rule | Where | Error |
|---|---|---|
| Invoice not selected | Register modal | "Debes seleccionar una factura." |
| Payment method not selected | Register modal | "Debes seleccionar un método de pago." |
| Lookup ID empty or ≤ 0 | Lookup bar | "Ingresa un ID de factura válido." |

### Backend (authoritative — messages surfaced verbatim)

| Condition | HTTP | Backend message |
|---|---|---|
| Invoice not found | 404 | "La factura no existe" |
| Invoice is not PENDING | 409 | "La factura no puede pagarse" |
| Invoice already has a payment | 409 | "La factura ya tiene un pago registrado" |
| Payment not found | 404 | "Pago no encontrado" |
| Delete: invoice is PAID | 409 | "No se puede eliminar un pago confirmado" |
| Lookup: no payment for invoice | 404 | "Pago no encontrado" |
| Optimistic locking conflict | 409 | "El registro fue modificado por otro usuario. Intente nuevamente." |

`useAsyncState.run()` extracts `err.response?.data?.error` and stores it in the relevant error ref without transformation.

---

## 6. Manual Testing Examples

Assuming the backend is running on `localhost:8082` and a valid session token is stored in localStorage.

### 6.1 Register a payment successfully

1. Navigate to `/payments`.
2. Create a reservation and generate a PENDING invoice (in Facturas module) if none exists.
3. Click "Registrar pago".
4. Select the PENDING invoice from the dropdown (shows total + "Pendiente").
5. Select "Transferencia" as method.
6. Enter a reference, e.g. "TXN-001".
7. Click "Registrar".
8. **Expected:** modal closes; new row appears in the table with the correct amount, method "Transferencia", reference "TXN-001", and a `paidAt` timestamp. In the Facturas module, the invoice now shows status **Pagada**.

### 6.2 Register a payment without a reference

1. Follow steps 1–5 of 6.1.
2. Leave the Reference field empty.
3. Click "Registrar".
4. **Expected:** payment registered; "Referencia" column in the table shows "—".

### 6.3 Try to pay the same invoice twice

1. Follow 6.1 to register a payment.
2. Click "Registrar pago" again and select the same invoice (now shown as "Pagada").
3. Click "Registrar".
4. **Expected:** modal stays open; error displayed: "La factura no puede pagarse".

### 6.4 Try to pay a CANCELLED invoice

1. Cancel an invoice in the Facturas module.
2. In Pagos, open the register modal and select that invoice (shows "Cancelada").
3. Click "Registrar".
4. **Expected:** error: "La factura no puede pagarse".

### 6.5 Frontend validation — missing fields

1. Click "Registrar pago".
2. Click "Registrar" without selecting an invoice.
3. **Expected:** error: "Debes seleccionar una factura." (no API call made).
4. Select an invoice, then click "Registrar" without selecting a payment method.
5. **Expected:** error: "Debes seleccionar un método de pago." (no API call made).

### 6.6 Lookup by invoice ID

1. In the lookup bar, type the ID of an invoice that has a payment.
2. Press Enter or click "Buscar".
3. **Expected:** table shows only that payment row; "Limpiar filtro" button appears.
4. Click "Limpiar filtro".
5. **Expected:** full payment list is restored; input field is cleared.

### 6.7 Lookup for invoice with no payment

1. Type the ID of an invoice that has no associated payment.
2. Click "Buscar".
3. **Expected:** `lookupError` banner displays "Pago no encontrado"; table unchanged.

### 6.8 Delete a payment

1. Click "Eliminar" on any row.
2. **Expected:** confirmation modal opens showing payment ID.
3. Click "Eliminar" in the modal.
4. **Expected:** modal stays open; error displayed: "No se puede eliminar un pago confirmado". This is expected — the backend prevents deletion of payments whose invoice is PAID (which is always the case after a successful payment creation).

### 6.9 Load error / retry

1. Stop the backend.
2. Navigate to `/payments`.
3. **Expected:** spinner → load error message.
4. Restart backend, click "Reintentar".
5. **Expected:** payments and invoices reload successfully.

---

## 7. Assumptions Made

1. **Payments are effectively immutable.** The backend's `delete()` checks `invoice.getStatus() == PAID` and always rejects deletion because creating a payment atomically marks the invoice PAID. The Delete button is still shown to be honest about the endpoint's existence, and the 409 error is surfaced verbatim. This matches the established pattern — the backend is the source of truth.

2. **Invoices are refreshed after payment creation.** After `POST /api/payments` succeeds, `GET /api/invoices` is called again to sync invoice statuses. Without this, a PENDING invoice that was just paid would still appear as PENDING in the select dropdown until a page reload.

3. **All invoices are shown in the select, not just PENDING ones.** Filtering to PENDING on the frontend could hide invoices if the local `invoices` list is stale. The backend rejects non-PENDING invoices with a 409, which is surfaced as `modalError`. This is consistent with InvoicesView's approach (showing all reservations in the invoice select).

4. **`reference` is omitted from the payload when empty**, rather than sent as `null` or `""`. The type is `reference?: string` (optional), so the spread conditional ensures only a truthy reference is included.

5. **`paidAt` and `createdAt` are both `LocalDateTime`.** Both are formatted with `toLocaleString('es-CO', { ... hour, minute })` to show full timestamps. `paidAt` specifically records when the payment transaction occurred (set to `LocalDateTime.now()` in the backend), not just the creation date.

6. **No edit mode.** There is no `PUT /api/payments/{id}` endpoint in the backend. The view has no edit modal, consistent with payments being an immutable financial record.

7. **After deleting the lookup result, the filter is cleared.** If the user is viewing a specific payment via lookup and deletes it, `isLookupActive` is set to false and the full list is restored. This avoids a confusing empty filter state.

8. **`methodLabel` and `invoiceStatusLabel` use `Record` constants**, not inline ternary chains or switch statements. This keeps the template clean and makes adding new values trivial.

---

## 8. Confirmation That No Existing Module Behavior Changed

| Module | Status |
|---|---|
| Login (`/`) | Unchanged |
| Dashboard (`/dashboard`) | Unchanged |
| Guests (`/guests`) | Unchanged |
| Properties (`/properties`) | Unchanged |
| Reservations (`/reservations`) | Unchanged |
| Invoices (`/invoices`) | Unchanged |
| Route guard | Unchanged |
| Any existing service file | Unchanged |
| Any existing type file | Unchanged |
| `shared.css` | Unchanged |

Modifications to existing files were purely additive:
- `AppNav.vue`: one `<router-link>` appended
- `router/index.js`: one import + one route entry appended

---

## 9. Build Result

```
vite v8.0.16 building client environment for production...
✓ 115 modules transformed.

dist/assets/index-DU1j8Mql.css    12.36 kB │ gzip: 2.60 kB
dist/assets/index-BgzrtR4v.js    177.84 kB │ gzip: 62.32 kB

✓ built in 330ms
```

+4 modules over the previous build (111). Zero TypeScript errors. Zero Vite warnings.
