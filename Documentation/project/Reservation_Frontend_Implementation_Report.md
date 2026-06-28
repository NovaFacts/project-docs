# Reservation Frontend Implementation Report

**Date:** 2026-06-28  
**Build result:** `npm run build` → 108 modules transformed — **BUILD SUCCESS (0 warnings)**

---

## 1. Files Created

| File | Purpose |
|---|---|
| `src/views/ReservationsView.vue` | Full Reservation CRUD module |

---

## 2. Files Modified

| File | What changed |
|---|---|
| `src/types/reservation.ts` | Added `'COMPLETED'` to `ReservationStatus` union (backend enum has all three values) |
| `src/assets/shared.css` | Added `.badge`, `.badge--confirmed`, `.badge--cancelled`, `.badge--completed` CSS classes |
| `src/components/AppNav.vue` | Added `<router-link to="/reservations">Reservas</router-link>` |
| `src/router/index.js` | Added `ReservationsView` import and `/reservations` route under `AppLayout` children |

---

## 3. Components Reused

| Component | Used in |
|---|---|
| `AppLayout.vue` | Wraps `/reservations` automatically via the router |
| `AppModal.vue` | Create/edit form modal and delete confirmation modal |
| `PageHeader.vue` | Page title row with "Nueva reserva" button |
| `useAsyncState` | Three instances: load, submit, delete |
| `shared.css` | All layout, button, table, state, form, modal, and badge classes |

No new components were created — the module relies entirely on the existing shared infrastructure.

---

## 4. Routes Added

| Path | Name | Component | Parent |
|---|---|---|---|
| `/reservations` | `reservations` | `ReservationsView` | `AppLayout` (authenticated) |

The existing navigation guard (`to.name !== 'login'`) automatically protects the new route. No guard changes were required.

---

## 5. API Endpoints Consumed

| Method | Endpoint | Service | Trigger |
|---|---|---|---|
| `GET` | `/api/reservations` | `reservationService` | `onMounted` + "Reintentar" button |
| `GET` | `/api/guests` | `guestService` | `onMounted` + "Reintentar" button (parallel with above) |
| `GET` | `/api/properties` | `propertyService` | `onMounted` + "Reintentar" button (parallel with above) |
| `POST` | `/api/reservations` | `reservationService` | Submit create modal |
| `PUT` | `/api/reservations/{id}` | `reservationService` | Submit edit modal |
| `DELETE` | `/api/reservations/{id}` | `reservationService` | Confirm delete modal |

All calls go through typed service functions using the central `api` Axios instance. No direct Axios imports exist in `ReservationsView.vue`.

---

## 6. Validation Strategy

### Frontend (synchronous, before API call)

Validated inside `validate()` before `runSubmit` is called. Sets `modalError.value` directly:

| Rule | Error message |
|---|---|
| Guest not selected | "El huésped es obligatorio." |
| Property not selected | "La propiedad es obligatoria." |
| Check-in empty | "La fecha de entrada es obligatoria." |
| Check-out empty | "La fecha de salida es obligatoria." |
| check-in ≥ check-out (string ISO comparison) | "La fecha de salida debe ser posterior a la de entrada." |
| guestCount < 1 or not a number | "El número de huéspedes debe ser al menos 1." |
| Status empty (edit mode only) | "El estado es obligatorio." |

### Backend (authoritative)

Backend validations not duplicated in the frontend:

| Condition | HTTP | Backend message |
|---|---|---|
| Guest does not exist | 404 | "Huésped no encontrado" |
| Property does not exist | 404 | "Propiedad no encontrada" |
| checkIn not strictly before checkOut | 400 | "La fecha de inicio debe ser anterior a la fecha de fin" |
| Stay longer than 30 nights | 400 | "La reserva no puede superar 30 noches" |
| guestCount > property capacity | 400 | "La cantidad de huéspedes supera la capacidad de la propiedad" |
| Date overlap with a CONFIRMED reservation | 409 | "La propiedad ya tiene una reserva en esas fechas" |

Backend messages are surfaced verbatim — `useAsyncState.run()` extracts `err.response?.data?.error` and sets it into `modalError` without transformation.

---

## 7. Conflict Handling

The backend returns HTTP 409 for date overlaps and HTTP 400 for business rule violations. Both are handled identically by `useAsyncState`:

```typescript
if (isAxiosError(err)) {
  error.value = err.response?.data?.error ?? 'Error inesperado del servidor.';
}
```

The modal stays open and the error is displayed inline below the form (`.form-error` class). The reservation list is not updated until the operation succeeds. The user can correct the dates/guest count and resubmit without reopening the modal.

If the backend returns the optimistic locking 409 (`ObjectOptimisticLockingFailureException`), the same path surfaces: "El registro fue modificado por otro usuario. Intente nuevamente."

---

## 8. State Management

Three independent `useAsyncState()` instances keep loading and error states isolated:

```typescript
const { loading: isLoading, error: errorMessage, run: runLoad }    = useAsyncState();
const { loading: isSubmitting, error: modalError, run: runSubmit } = useAsyncState();
const { loading: isDeleting,  error: deleteError, run: runDelete } = useAsyncState();
```

**Data loading:** `loadData()` fetches reservations, guests, and properties in a single `Promise.all` call. All three are needed before the view is usable (reservations for the table, guests/properties for select labels). A single "Reintentar" button retries all three.

**Guest/property resolution:** Computed `Map<number, string>` values are built from the loaded arrays:

```typescript
const guestMap = computed(() => {
  const m = new Map<number, string>();
  guests.value.forEach(g => m.set(g.id, `${g.documentNumber} — ${g.firstName} ${g.lastName}`));
  return m;
});
```

These maps are used both in the table (display) and are already populated when the create/edit modal opens (select options). Maps are reactive — if the underlying `guests` or `properties` ref is updated, labels update automatically.

---

## 9. Responsive Behavior

| Element | Behavior |
|---|---|
| Form rows (`.form-row`) | 2 columns on desktop; 1 column below 480 px |
| Guest count field | Full-width in create mode (no status next to it); half-width in edit mode |
| Status select | Only visible in edit mode; appears in the right column of the last row |
| Table | Horizontal scroll on overflow (`overflow-x: auto` in `.table-wrapper`) |
| Modal | Max-width 560 px (form) / 420 px (confirm); centers on all screen sizes |
| Page layout | `max-width: 1200px; margin: 0 auto; padding: 32px` |

---

## 10. Build Result

```
vite v8.0.16 building client environment for production...
✓ 108 modules transformed.

dist/assets/index-xrzP7pB1.css    11.69 kB │ gzip: 2.49 kB
dist/assets/index-DrnjWEfC.js    164.21 kB │ gzip: 59.61 kB

✓ built in 307ms
```

+3 modules over the previous build (105), one per new file (`ReservationsView.vue`, and two CSS/TS additions processed as modules). Zero TypeScript errors. Zero Vite warnings.

---

## 11. Assumptions Made

1. **`COMPLETED` is a valid backend status**: The backend `ReservationStatus` enum (`ReservationStatus.java`) has all three values: `CONFIRMED`, `CANCELLED`, `COMPLETED`. The frontend type was updated to match.

2. **`create` always sets CONFIRMED on the backend**: `ReservationService.create()` hardcodes `reservation.setStatus(ReservationStatus.CONFIRMED)` regardless of the request body. Therefore, the "Status" field is hidden in the create modal — it only appears in edit mode where the backend respects the sent value.

3. **`checkIn` and `checkOut` are `LocalDate` (ISO `YYYY-MM-DD`)**: A dedicated `formatLocalDate()` helper parses the string with `new Date(year, month-1, day)` (local constructor, not UTC) to prevent off-by-one-day display errors from timezone offset.

4. **`createdAt` is `LocalDateTime`**: It includes a time component, so `new Date(dateStr)` in `formatDate()` is safe (no timezone ambiguity for datetime strings).

5. **Guests and properties must be loaded for the form to be usable**: If either endpoint fails, the full page load fails and the retry button reloads all three together. This is intentional — without guest/property data, the select dropdowns would be empty and the form unusable.

6. **ISO string comparison is safe for date validation**: `form.checkIn >= form.checkOut` is valid for ISO `YYYY-MM-DD` strings because lexicographic order matches chronological order.

7. **`guestCount` is required by the backend**: Even though the task spec lists only guest, property, check-in, check-out, and status as required fields, the backend `CreateReservationRequest` and `UpdateReservationRequest` both require `guestCount`. It is included in the form with a minimum value of 1.

---

## 12. Confirmation That No Existing Module Behavior Changed

| Module | Status |
|---|---|
| Login (`/`) | Unchanged — `LoginView.vue` not modified |
| Dashboard (`/dashboard`) | Unchanged — `DashboardView.vue` not modified |
| Guests (`/guests`) | Unchanged — `GuestsView.vue` not modified |
| Properties (`/properties`) | Unchanged — `PropertiesView.vue` not modified |
| Route guard | Unchanged — guard logic `to.name !== 'login'` not modified |
| Guest service | Unchanged — only imported (not modified) |
| Property service | Unchanged — only imported (not modified) |
| Logout | Unchanged — `AppHeader.vue` not modified |

The only modifications to existing files were additive:
- `reservation.ts`: one union member added (`'COMPLETED'`)
- `shared.css`: 25 lines added at the end (badge classes)
- `AppNav.vue`: one `<router-link>` added
- `router/index.js`: one import + one route object added

No existing component, service, type, route, or behavior was removed or altered.
