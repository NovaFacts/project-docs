# Property Frontend Implementation Report

**Date:** 2026-06-28  
**Build result:** `npm run build` → 105 modules transformed — **BUILD SUCCESS (0 warnings)**

---

## 1. Files Created

| File | Purpose |
|---|---|
| `src/composables/useAsyncState.ts` | Composable that encapsulates loading + error state for async operations |
| `src/components/PageHeader.vue` | Reusable page header (title, subtitle, create button) |
| `src/assets/shared.css` | Shared CSS utility classes used across all CRUD views |
| `src/views/PropertiesView.vue` | Full Property CRUD module |

---

## 2. Files Modified

| File | What changed |
|---|---|
| `src/main.js` | Added `import './assets/shared.css'` |
| `src/components/AppNav.vue` | Added `<router-link to="/properties">Propiedades</router-link>` |
| `src/router/index.js` | Added `PropertiesView` import and `/properties` route under `AppLayout` children |

---

## 3. Components Reused

| Component | Used in |
|---|---|
| `AppLayout.vue` | Wraps `/properties` automatically via the router |
| `AppHeader.vue` | Rendered by AppLayout — no change needed |
| `AppNav.vue` | Navigation — only added a link entry |
| `AppModal.vue` | Used for create/edit modal and delete confirmation modal |
| `PageHeader.vue` | New — used in PropertiesView for the title + create button row |

GuestsView was not modified.

---

## 4. New Route

| Path | Name | Component | Parent |
|---|---|---|---|
| `/properties` | `properties` | `PropertiesView` | `AppLayout` (authenticated) |

Navigation guard (`to.name !== 'login'`) automatically protects the new route. No guard changes were required.

All existing routes (`/`, `/dashboard`, `/guests`) continue working without change.

---

## 5. API Endpoints Consumed

| Method | Endpoint | Trigger |
|---|---|---|
| `GET` | `/api/properties` | Page load (`onMounted`) and after clicking "Reintentar" |
| `POST` | `/api/properties` | Submit create modal |
| `PUT` | `/api/properties/{id}` | Submit edit modal |
| `DELETE` | `/api/properties/{id}` | Confirm delete modal |

All calls go through `propertyService.ts` via the central `api` Axios instance (with Bearer token header). No Axios import exists inside `PropertiesView.vue`.

---

## 6. State Management Approach

`useAsyncState()` is called **three times** per view — once per distinct async operation — to keep loading and error states independent:

```typescript
const { loading: isLoading, error: errorMessage, run: runLoad }    = useAsyncState();
const { loading: isSubmitting, error: modalError, run: runSubmit } = useAsyncState();
const { loading: isDeleting,  error: deleteError, run: runDelete } = useAsyncState();
```

Each `run(asyncFn)` call:
1. Sets `loading = true` and clears the operation's own `error`.
2. Awaits the callback.
3. On Axios error: extracts `err.response?.data?.error` (backend Spanish message) into `error`.
4. On unknown error: sets a generic Spanish fallback message.
5. Sets `loading = false` in `finally`.

Success-only code (updating the list, closing the modal) lives inside the callback — it is never reached if the service call throws.

Synchronous form validation sets `modalError.value` directly (before calling `runSubmit`). When the user retries, `runSubmit` clears the error at the start of the next call.

---

## 7. Responsive Behavior

| Element | Responsive behavior |
|---|---|
| Form grid (`.form-row`) | 2 columns on desktop; 1 column below 480 px (from `shared.css`) |
| Full-width fields (`.form-group--full`) | Span both columns on desktop; single column on mobile |
| Table (`.table-wrapper`) | Horizontal scroll on overflow — same as GuestsView |
| Modal | Max-width 560 px (form) / 420 px (confirm); padding reduces on mobile (AppModal.vue) |
| Page layout | `max-width: 1200px; margin: 0 auto` — same as GuestsView |

---

## 8. Error Handling Strategy

### Load errors (page-level)

If `GET /api/properties` fails, `errorMessage` shows a red state-box with the backend message and a "Reintentar" button that calls `loadProperties()` again.

### Create/edit errors (modal-level)

Two error paths:
1. **Frontend validation** (synchronous): checks name, city, address, capacity > 0, pricePerNight > 0 before the API call. Sets `modalError.value` directly.
2. **Backend validation** (async): 409 for duplicate name. `runSubmit` catches the Axios error, reads `err.response.data.error`, and surfaces it in `modalError`. The modal stays open.

### Delete errors (delete modal)

If `DELETE /api/properties/{id}` fails (e.g., the property has associated reservations), `deleteError` shows the backend message inside the confirmation modal. The list is NOT updated.

### Optimistic locking (409 Conflict from concurrent edit)

If a 409 is returned due to the optimistic locking handler added in the backend cleanup, the Spanish message "El registro fue modificado por otro usuario. Intente nuevamente." is displayed inside the modal via the same error path.

---

## 9. Build Result

```
vite v8.0.16 building client environment for production...
✓ 105 modules transformed.

dist/assets/index-DYicMtnk.css    11.36 kB │ gzip: 2.39 kB
dist/assets/index-DHDZmqn1.js    155.93 kB │ gzip: 57.93 kB

✓ built in 294ms
```

+8 modules vs. the previous build (97), reflecting the 4 new files added. Zero TypeScript errors. Zero Vite warnings.

---

## 10. Assumptions Made

1. **No integer-only constraint on `capacity` at the API level**: the backend uses Java `int`, so the frontend sends a parsed integer. The form uses `type="number" step="1"` to guide the user, and `parseInt()` is used on submit.

2. **`pricePerNight` is sent as a JavaScript `number`**: the backend stores it as `NUMERIC(15,2)`. Sending `150000` (not `"150000"`) is correct JSON for a numeric column.

3. **No property has dependent entities yet**: the delete endpoint returns 204 unconditionally. If future FK constraints cause a 409 or 422, the error is already surfaced through the standard `deleteError` path — no additional handling is needed.

4. **`style.css` is a Vite default placeholder and is NOT imported**: it contains CSS variables and layout rules (`#app { width: 1126px }`) that would conflict with the application's design system. `shared.css` was created separately and imported explicitly.

5. **`UpdatePropertyRequest` is identical to `CreatePropertyRequest`** (a type alias in `property.ts`): the same payload is sent for both create and edit.

6. **Currency formatting uses `es-CO` locale and `COP`**: consistent with the project's Spanish domain language. `Intl.NumberFormat` formats `150000` as `$ 150.000`.

---

## 11. Confirmation That No Existing Module Behavior Changed

| Module | Status |
|---|---|
| Login (`/`) | Unchanged — `LoginView.vue` not modified |
| Dashboard (`/dashboard`) | Unchanged — `DashboardView.vue` not modified |
| Guests (`/guests`) | Unchanged — `GuestsView.vue` not modified |
| Route guard | Unchanged — guard logic `to.name !== 'login'` not modified |
| Logout | Unchanged — `AppHeader.vue` logout not modified |
| Auth service | Unchanged |
| Guest service | Unchanged |

The only modifications to existing files were additive:
- `main.js`: one `import` line added
- `AppNav.vue`: one `<router-link>` added
- `router/index.js`: one import + one route object added

No existing component, service, type, or route was removed or altered.

---

## Architecture Pattern for Future Modules

`PropertiesView` establishes the reference pattern for Reservations, Invoices, and Payments:

```
views/XxxView.vue
  ├── imports: useAsyncState, AppModal, PageHeader, xxxService, types/xxx
  ├── state: three useAsyncState() instances (load / submit / delete)
  ├── form: interface XxxFormState { string fields }
  ├── onMounted: loadXxx()
  ├── handlers: openCreateModal, openEditModal, closeModal,
  │             handleSubmit, confirmDelete, cancelDelete, handleDelete
  └── helpers: formatDate, formatCurrency (where applicable)
```

CSS comes entirely from `shared.css` (global). No scoped styles are needed unless a view has unique layout requirements.
