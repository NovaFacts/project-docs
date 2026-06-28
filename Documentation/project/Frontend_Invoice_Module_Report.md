# Frontend Invoice Module Report

**Date:** 2026-06-28  
**Build result:** `npm run build` → 111 modules transformed — **BUILD SUCCESS (0 warnings)**

---

## 1. Files Created

| File | Purpose |
|---|---|
| `src/views/InvoicesView.vue` | Complete Invoice management page (generate, cancel, delete, lookup) |

---

## 2. Files Modified

| File | What changed |
|---|---|
| `src/assets/shared.css` | Added `.badge--pending` (amber), `.badge--paid` (green), `.lookup-bar`, `.page-error` |
| `src/components/AppNav.vue` | Added `<router-link to="/invoices">Facturas</router-link>` |
| `src/router/index.js` | Added `InvoicesView` import and `/invoices` route under `AppLayout` children |

No other files were modified. No business logic, API contracts, or backend code were touched.

---

## 3. Architectural Decisions

### useAsyncState — five instances

Invoices has more async operations than other modules (load, submit, cancel, delete, lookup). Five independent `useAsyncState()` instances keep every loading flag and error message isolated:

```typescript
const { loading: isLoading, error: errorMessage, run: runLoad }          = useAsyncState();
const { loading: isSubmitting, error: modalError, run: runSubmit }       = useAsyncState();
const { loading: isCancelling, error: cancelError, run: runCancel }      = useAsyncState();
const { loading: isDeleting, error: deleteError, run: runDelete }        = useAsyncState();
const { loading: isLookingUp, error: lookupError,
        clearError: clearLookupError, run: runLookup }                   = useAsyncState();
```

Each error is surfaced in the appropriate location: `errorMessage` replaces the page content on load failure; `modalError` appears inside the create modal; `deleteError` appears inside the delete confirmation modal; `cancelError` and `lookupError` appear as `.page-error` banners above the table.

### Promise.all for page load

Invoices and reservations are loaded together on mount:

```typescript
[invoices.value, reservations.value] = await Promise.all([
  getInvoices(),
  getReservations(),
]);
```

Reservations are needed to populate the "Generate Invoice" select dropdown at the moment the user opens the modal. Loading them eagerly avoids a second spinner when the modal opens.

### Lookup without page reload

The lookup (`GET /api/invoices/by-reservation/{id}`) uses its own async state and does not call `loadData()`. The result goes into `lookupResult` and `isLookupActive` is toggled to true on success. A computed property controls what the table shows:

```typescript
const displayedInvoices = computed<Invoice[]>(() =>
  isLookupActive.value
    ? (lookupResult.value ? [lookupResult.value] : [])
    : invoices.value
);
```

The main `invoices` list is never altered by a lookup — it reflects the full server state until the next load or mutation.

### In-place mutations (no full reload)

Following the established pattern from GuestsView, PropertiesView, and ReservationsView:

- **Generate (POST):** created invoice is pushed to `invoices.value`
- **Cancel (PUT):** updated invoice (status changed) is replaced in `invoices.value` at the matching index; if the lookup result is the same invoice, `lookupResult.value` is also updated
- **Delete (DELETE):** invoice is filtered out of `invoices.value`; if it was the current lookup result, the lookup is cleared and the full list is restored

### Cancel as inline action (no modal)

Cancel is an immediately visible button shown only on PENDING rows. There is no confirmation modal (the task does not request one, and cancel is reversible only by an admin — not by the user). The backend will reject non-PENDING cancels with a 409. A per-row loading flag (`cancellingId`) ensures only the clicked row shows a spinner; other rows remain interactive.

### Status badge reuse

`InvoiceStatus` values (PENDING, PAID, CANCELLED) map to CSS classes:

| Status | Class | Visual |
|---|---|---|
| `PENDING` | `.badge--pending` | Amber/yellow |
| `PAID` | `.badge--paid` | Green |
| `CANCELLED` | `.badge--cancelled` | Red (pre-existing class from shared.css) |

`.badge--pending` and `.badge--paid` were added to `shared.css` and are available to all future modules.

### Reservation selector in modal

The "Generate Invoice" modal uses a `<select>` that lists all reservations with their ID, date range, and status. No filtering is done on the frontend (e.g., hiding reservations that already have an invoice). The backend is the source of truth — it returns 409 with "Ya existe una factura para esta reserva" if a duplicate is attempted. The error surfaces verbatim in `modalError`.

### Currency formatting

All monetary fields (subtotal, tax, total) use:

```typescript
new Intl.NumberFormat('es-CO', { style: 'currency', currency: 'COP',
  minimumFractionDigits: 0, maximumFractionDigits: 0 }).format(amount)
```

This formats `1200000` as `$ 1.200.000`, consistent with Colombian currency conventions.

### Date formatting

- `createdAt` is a `LocalDateTime`: formatted with `toLocaleString('es-CO', { ... hour, minute })` to show the full timestamp
- `checkIn` / `checkOut` inside the reservation select are `LocalDate` (no time component): formatted with the timezone-safe `formatLocalDate()` helper (parses year/month/day directly to avoid UTC-midnight offset issues)

---

## 4. API Endpoints Consumed

| Method | Endpoint | Service function | Trigger |
|---|---|---|---|
| `GET` | `/api/invoices` | `getInvoices` | `onMounted` + "Reintentar" |
| `GET` | `/api/reservations` | `getReservations` | `onMounted` + "Reintentar" (parallel) |
| `GET` | `/api/invoices/by-reservation/{id}` | `getInvoiceByReservation` | "Buscar" button or Enter key |
| `POST` | `/api/invoices` | `createInvoice` | Submit "Generar factura" modal |
| `PUT` | `/api/invoices/{id}/cancel` | `cancelInvoice` | "Cancelar" button on PENDING rows |
| `DELETE` | `/api/invoices/{id}` | `deleteInvoice` | Confirm delete modal |

No Axios imports exist in `InvoicesView.vue`. All calls go through the typed service layer.

---

## 5. Validation Strategy

### Frontend (synchronous, before API call)

| Rule | Where | Error |
|---|---|---|
| Reservation not selected | Create modal | "Debes seleccionar una reserva." |
| Lookup ID empty or ≤ 0 | Lookup bar | "Ingresa un ID de reserva válido." |

### Backend (authoritative — messages surfaced verbatim)

| Condition | HTTP | Backend message |
|---|---|---|
| Reservation not found | 404 | "Reserva no encontrada" |
| Reservation is CANCELLED | 400 | "No se puede facturar una reserva cancelada" |
| Invoice already exists for reservation | 409 | "Ya existe una factura para esta reserva" |
| Cancel attempted on non-PENDING invoice | 409 | "Solo se pueden cancelar facturas pendientes" |
| Delete attempted on PAID invoice | 409 | "No se puede eliminar una factura pagada" |
| Invoice not found | 404 | "Factura no encontrada" |
| Lookup: no invoice for reservation | 404 | "Factura no encontrada" |
| Optimistic locking conflict | 409 | "El registro fue modificado por otro usuario. Intente nuevamente." |

`useAsyncState.run()` extracts `err.response?.data?.error` from every Axios error and stores it in the relevant error ref. No error message is transformed or replaced.

---

## 6. Manual Testing Examples

Assuming the backend is running on `localhost:8082` and a session token is stored in localStorage.

### 6.1 Generate a new invoice

1. Navigate to `/invoices`.
2. Click "Generar factura".
3. Select a CONFIRMED reservation from the dropdown.
4. Click "Generar".
5. **Expected:** modal closes; new row appears in the table with status **Pendiente**, calculated subtotal/IVA/total.

### 6.2 Try to generate a duplicate invoice

1. Follow 6.1 successfully.
2. Click "Generar factura" again and select the same reservation.
3. Click "Generar".
4. **Expected:** modal stays open; error displayed: "Ya existe una factura para esta reserva".

### 6.3 Try to generate an invoice for a cancelled reservation

1. In the Reservas module, cancel a reservation.
2. In Facturas, try to generate an invoice for that reservation.
3. **Expected:** error: "No se puede facturar una reserva cancelada".

### 6.4 Cancel a pending invoice

1. Find a row with status **Pendiente**.
2. Click "Cancelar".
3. **Expected:** button shows "Cancelando…" briefly; row status badge changes to **Cancelada**; Cancel button disappears from that row.

### 6.5 Try to cancel a non-pending invoice

This scenario is prevented at the UI level (the Cancel button is hidden for non-PENDING rows).  
Programmatic attempt via the API returns 409: "Solo se pueden cancelar facturas pendientes".

### 6.6 Delete a pending invoice

1. Click "Eliminar" on any row.
2. **Expected:** confirmation modal opens showing invoice ID.
3. Click "Eliminar" in the modal.
4. **Expected:** modal closes; row is removed from the table.

### 6.7 Try to delete a paid invoice

1. Click "Eliminar" on a PAID row.
2. Confirm in the modal.
3. **Expected:** modal stays open; error: "No se puede eliminar una factura pagada".

### 6.8 Lookup by reservation ID

1. In the lookup bar, type a reservation ID that has an invoice (e.g., `1`).
2. Click "Buscar" or press Enter.
3. **Expected:** table shows only that invoice; "Limpiar filtro" button appears.
4. Click "Limpiar filtro".
5. **Expected:** full invoice list restored; input cleared.

### 6.9 Lookup for a reservation with no invoice

1. Type the ID of a reservation that has no invoice.
2. Click "Buscar".
3. **Expected:** `lookupError` banner: "Factura no encontrada"; table unchanged (full list still shown or previous lookup cleared).

### 6.10 Load error / retry

1. Stop the backend.
2. Navigate to `/invoices`.
3. **Expected:** spinner → error message from network failure.
4. Restart backend, click "Reintentar".
5. **Expected:** invoices and reservations reload successfully.

---

## 7. Assumptions Made

1. **Reservations are loaded in `onMounted` to populate the select.** Loading them lazily (on modal open) would require a separate async state and would delay the modal opening. Eager loading is consistent with how ReservationsView handles guests/properties.

2. **The "Generate Invoice" modal does not filter reservations.** All reservations are shown in the select regardless of whether they already have an invoice. The backend is the enforcer (409 on duplicates). Filtering would require an extra comparison against the invoices list, which is fragile if the list is stale.

3. **Cancel has no confirmation modal.** The task says "each pending invoice has Cancel — calls PUT /api/invoices/{id}/cancel — refresh list afterwards." No confirmation step is specified. The Cancel button is only visible on PENDING rows, so accidental use requires finding the correct row.

4. **"Refresh list afterwards" for cancel means in-place update.** The task uses the word "refresh" but the established pattern in the project is to update in-place using the API response — avoiding an extra round-trip to `GET /api/invoices`. The updated invoice returned by `PUT /api/invoices/{id}/cancel` is used directly.

5. **`formatCurrency` truncates decimals.** The backend stores amounts as `NUMERIC(15,2)` (e.g., 1200000.00). `minimumFractionDigits: 0` avoids showing ".00" in the UI, which is standard for COP amounts.

6. **`createdAt` is `LocalDateTime`.** The backend's `InvoiceResponse` serializes `LocalDateTime createdAt` as an ISO-8601 string with time (e.g., "2026-06-28T14:30:00"). `toLocaleString()` is appropriate here (not `toLocaleDateString()`), showing date + time.

7. **`checkIn`/`checkOut` in the reservation select use `formatLocalDate()`.** These are `LocalDate` fields (no time component). The timezone-safe helper prevents off-by-one errors when the browser's UTC offset is negative.

8. **The lookup input validates positivity before the API call.** A value of `0` or negative is meaningless as a reservation ID, so it's rejected with a frontend message before sending a request.

9. **Deleting the current lookup result clears the filter.** When the user deletes the invoice currently shown by a lookup, `isLookupActive` is set to false and the full list is shown. This is cleaner than showing an empty filter state, which could confuse the user into thinking no invoices exist.

---

## 8. Confirmation That No Existing Module Behavior Changed

| Module | Status |
|---|---|
| Login (`/`) | Unchanged |
| Dashboard (`/dashboard`) | Unchanged |
| Guests (`/guests`) | Unchanged |
| Properties (`/properties`) | Unchanged |
| Reservations (`/reservations`) | Unchanged |
| Route guard | Unchanged |
| Any existing service file | Unchanged |
| Any existing type file | Unchanged |

Modifications to existing files were purely additive:
- `shared.css`: new badge variants + lookup-bar + page-error utilities appended
- `AppNav.vue`: one `<router-link>` appended
- `router/index.js`: one import + one route entry appended

---

## 9. Build Result

```
vite v8.0.16 building client environment for production...
✓ 111 modules transformed.

dist/assets/index-sEW1IyF3.css    12.28 kB │ gzip: 2.58 kB
dist/assets/index-BMUx79Cu.js    171.08 kB │ gzip: 61.09 kB

✓ built in 311ms
```

+3 modules over the previous build (108). Zero TypeScript errors. Zero Vite warnings.
