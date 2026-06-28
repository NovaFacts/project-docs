# Frontend Infrastructure Refactor Report

**Date:** 2026-06-28  
**Build result:** `npm run build` → 97 modules transformed — **BUILD SUCCESS (0 warnings)**

---

## Objective

Establish a reusable frontend foundation before implementing the Properties, Reservations, Invoices, and Payments modules. No new module was implemented. No visual design was changed. No API contract was modified. Every existing feature (login, route protection, Guests CRUD) continues to work identically.

---

## Files Created

| File | Purpose |
|---|---|
| `src/layouts/AppLayout.vue` | Authenticated page shell — AppHeader + `<router-view />` |
| `src/components/AppNav.vue` | Navigation links extracted from AppHeader |
| `src/components/AppModal.vue` | Reusable modal wrapper (backdrop + Teleport + slot) |
| `src/types/property.ts` | `Property`, `CreatePropertyRequest`, `UpdatePropertyRequest` |
| `src/types/reservation.ts` | `Reservation`, `ReservationStatus`, `CreateReservationRequest`, `UpdateReservationRequest` |
| `src/types/invoice.ts` | `Invoice`, `InvoiceStatus`, `CreateInvoiceRequest` |
| `src/types/payment.ts` | `Payment`, `PaymentMethod`, `CreatePaymentRequest` |
| `src/services/propertyService.ts` | HTTP layer for `/api/properties` (full CRUD) |
| `src/services/reservationService.ts` | HTTP layer for `/api/reservations` (full CRUD) |
| `src/services/invoiceService.ts` | HTTP layer for `/api/invoices` (create, get, cancel, delete, by-reservation) |
| `src/services/paymentService.ts` | HTTP layer for `/api/payments` (create, get, delete, by-invoice) |

---

## Files Modified

| File | What changed |
|---|---|
| `src/services/api.ts` | Re-exports `isAxiosError` from axios so views never import axios directly |
| `src/components/AppHeader.vue` | Replaced inline `<nav>` block with `<AppNav />` import |
| `src/router/index.js` | Added `AppLayout` as parent wrapper for authenticated routes using nested routing |
| `src/views/DashboardView.vue` | Removed `AppHeader` import + `page-container` wrapper div; now just renders content |
| `src/views/GuestsView.vue` | Removed `AppHeader`, removed `import axios`, replaced modal divs with `<AppModal>`, template root is `<main>` |

---

## Folder Structure After Refactor

```
src/
├── App.vue                    (unchanged)
├── main.js                    (unchanged)
├── style.css                  (unchanged)
├── assets/
│   ├── background.jpg
│   └── logo.png
├── layouts/
│   └── AppLayout.vue          ← NEW
├── components/
│   ├── AppHeader.vue          ← modified (uses AppNav)
│   ├── AppModal.vue           ← NEW
│   └── AppNav.vue             ← NEW
├── router/
│   └── index.js               ← modified (nested routes)
├── services/
│   ├── api.ts                 ← modified (re-exports isAxiosError)
│   ├── authService.ts         (unchanged)
│   ├── guestService.ts        (unchanged)
│   ├── invoiceService.ts      ← NEW
│   ├── paymentService.ts      ← NEW
│   ├── propertyService.ts     ← NEW
│   └── reservationService.ts  ← NEW
├── types/
│   ├── auth.ts                (unchanged)
│   ├── guest.ts               (unchanged)
│   ├── invoice.ts             ← NEW
│   ├── payment.ts             ← NEW
│   ├── property.ts            ← NEW
│   └── reservation.ts         ← NEW
└── views/
    ├── DashboardView.vue      ← modified (no AppHeader, no wrapper div)
    ├── GuestsView.vue         ← modified (uses AppModal, no axios import)
    └── LoginView.vue          (unchanged)
```

---

## TASK 1 — Authenticated layout (`AppLayout.vue`)

### Problem

Both `DashboardView.vue` and `GuestsView.vue` independently imported `AppHeader` and wrapped everything in a `div.page-container` / `div.dashboard-container` with identical `min-height: 100vh; display: flex; flex-direction: column` styles. Every future module would have duplicated this.

### Solution

`AppLayout.vue` owns the full-page shell:

```html
<template>
  <div class="app-layout">       <!-- min-height: 100vh, flex, column -->
    <AppHeader />
    <router-view />              <!-- authenticated views render here -->
  </div>
</template>
```

The router nests authenticated routes under it using absolute child paths (Vue Router 4/5 feature — absolute paths in `children` are resolved from root, not from the parent path):

```js
{ path: '/', name: 'login', component: LoginView },
{
  path: '/',
  component: AppLayout,
  children: [
    { path: '/dashboard', name: 'dashboard', component: DashboardView },
    { path: '/guests',    name: 'guests',    component: GuestsView    }
  ]
}
```

All existing URLs (`/`, `/dashboard`, `/guests`) are preserved exactly. The navigation guard (`to.name !== 'login'`) is unchanged. Logout (`router.push('/')`) is unchanged.

`DashboardView` and `GuestsView` now render directly into the layout's `<router-view />`. Their root elements are `<main>` with `flex: 1` — they fill the remaining height because AppLayout's flex column container provides the context.

### How to add new authenticated routes

```js
{ path: '/properties', name: 'properties', component: PropertiesView }
```
Add as a sibling `children` entry. The layout is inherited automatically.

---

## TASK 2 — Navigation component (`AppNav.vue`)

### Problem

The navigation links (`Dashboard`, `Huéspedes`) were hardcoded inside `AppHeader.vue`. To add a new module's nav link, you'd have to edit the header — mixing concerns.

### Solution

`AppNav.vue` is a single-responsibility component that owns only the navigation links:

```html
<router-link to="/dashboard" class="nav-link" exact-active-class="nav-link--active">Dashboard</router-link>
<router-link to="/guests"    class="nav-link" exact-active-class="nav-link--active">Huéspedes</router-link>
```

`AppHeader.vue` now imports and uses `<AppNav />` in place of the inline `<nav>`. The visual result is identical. To add a new module to the navigation, only `AppNav.vue` is edited.

All active-route highlighting (`nav-link--active` via `exact-active-class`) continues to work.

---

## TASK 3 — Services layer

### Existing services (unchanged)

| Service | Endpoints |
|---|---|
| `authService.ts` | `POST /api/auth/login`, `logout()` |
| `guestService.ts` | `GET/POST /api/guests`, `PUT/DELETE /api/guests/{id}` |

### New services (stubs for future modules)

Each service follows the exact same pattern as `guestService.ts`: imports the central `api` instance, typed with the module's interfaces, no axios imported directly.

| Service | Endpoints covered |
|---|---|
| `propertyService.ts` | `GET /api/properties`, `GET /api/properties/{id}`, `POST`, `PUT`, `DELETE` |
| `reservationService.ts` | `GET /api/reservations`, `GET /api/reservations/{id}`, `POST`, `PUT`, `DELETE` |
| `invoiceService.ts` | `GET /api/invoices`, `GET /api/invoices/{id}`, `GET /api/invoices/by-reservation/{id}`, `POST`, `PUT /{id}/cancel`, `DELETE` |
| `paymentService.ts` | `GET /api/payments`, `GET /api/payments/{id}`, `GET /api/payments/by-invoice/{id}`, `POST`, `DELETE` |

No endpoints were added or removed from the backend. All URLs, HTTP methods, and payloads match the backend API exactly.

---

## TASK 4 — GuestsView refactored

### Changes

1. **No `AppHeader` import** — the layout provides it.
2. **No `import axios from 'axios'`** — replaced with `import { isAxiosError } from '@/services/api'`. Every `axios.isAxiosError(err)` call became `isAxiosError(err)`.
3. **`<AppModal>` instead of raw divs** — the modal backdrop and box are now encapsulated.
4. **Template root is `<main class="page-main">`** — no outer wrapper div.
5. **All business logic preserved**: form validation, loading states, error handling, optimistic list update, delete confirmation, date formatting, retry button.

### Before (imports in GuestsView.vue)

```ts
import axios from 'axios';
import AppHeader from '@/components/AppHeader.vue';
import { getGuests, createGuest, updateGuest, deleteGuest } from '@/services/guestService';
```

### After

```ts
import { isAxiosError } from '@/services/api';
import AppModal from '@/components/AppModal.vue';
import { getGuests, createGuest, updateGuest, deleteGuest } from '@/services/guestService';
```

### Before (modal in template)

```html
<div v-if="showModal" class="modal-backdrop" @click.self="closeModal">
  <div class="modal">
    ...form content...
  </div>
</div>
```

### After

```html
<AppModal v-if="showModal" @close="closeModal">
  ...form content...
</AppModal>
```

The Guests view is now the reference implementation that all future CRUD views will follow.

---

## TASK 5 — Folder structure

No files were moved. The `layouts/` directory was created to hold `AppLayout.vue`. All other files remained in their existing locations. The `services/` and `types/` directories already existed and were extended.

---

## TASK 6 — Reusable CRUD infrastructure

### `AppModal.vue`

The only component extracted, because the justification was clear and immediate: both modal patterns in GuestsView (form modal + confirm modal) share identical structure, and all 4 future modules will need modals.

`AppModal.vue` provides:
- `<Teleport to="body">` — ensures the modal renders at the top of the DOM tree, avoiding z-index stacking context issues regardless of where it's used in the component tree.
- Backdrop with `@click.self` → emits `close` (backdrop click to dismiss).
- Centered white box with `size` prop (`'default'` = 560px max-width, `'sm'` = 420px).
- `<slot />` for arbitrary modal content.

**Props:** `size?: 'default' | 'sm'`  
**Emits:** `close`

### What was NOT extracted

- `LoadingSpinner` — one element, one line of CSS, no justification yet.
- `AppButton` — buttons have 4 variants (primary, ghost, danger, sm) but abstraction would require props for all combinations with no benefit at this stage.
- `AppInput` / `AppSelect` — form inputs are simple and context-specific. The Guests form would not be simplified.
- `ConfirmDialog` — only one confirm dialog exists. Extract when a second module adds one.

The principle applied: three instances of a pattern justify extraction; one does not.

---

## TASK 7 — TypeScript typing

All new files are strictly typed:

- Services use generic `api.get<T>()` calls — no `any`.
- Type files use discriminated union types where appropriate (`ReservationStatus`, `InvoiceStatus`, `PaymentMethod`).
- `AppModal` props are typed with `defineProps<{ size?: 'default' | 'sm' }>()`.
- `AppModal` emits are typed with `defineEmits<{ close: [] }>()`.
- `GuestsView` retains all existing explicit types (`Guest`, `CreateGuestRequest`, `UpdateGuestRequest`).
- `isAxiosError` from `@/services/api` is fully typed (Axios provides the type guard signature).

---

## TASK 8 — New modules NOT implemented

No view was created for Properties, Reservations, Invoices, or Payments. The infrastructure prepared for them:

| Future module | Type file | Service file | Route slot |
|---|---|---|---|
| Properties | `types/property.ts` ✓ | `services/propertyService.ts` ✓ | Add to `AppLayout` children + `AppNav` |
| Reservations | `types/reservation.ts` ✓ | `services/reservationService.ts` ✓ | Add to `AppLayout` children + `AppNav` |
| Invoices | `types/invoice.ts` ✓ | `services/invoiceService.ts` ✓ | Add to `AppLayout` children + `AppNav` |
| Payments | `types/payment.ts` ✓ | `services/paymentService.ts` ✓ | Add to `AppLayout` children + `AppNav` |

To add a new module, the implementer:
1. Creates `src/views/XxxView.vue` following the GuestsView pattern (no AppHeader, no outer div, AppModal for modals, `isAxiosError` from api.ts).
2. Adds a route to the `children` array in `router/index.js`.
3. Adds a `<router-link>` to `AppNav.vue`.

---

## Architectural improvements summary

| Before | After |
|---|---|
| AppHeader + page-container duplicated in every view | AppLayout owns the shell once |
| Navigation links hardcoded in AppHeader | AppNav.vue — single place to edit for new modules |
| `import axios` in GuestsView | `isAxiosError` re-exported from `api.ts`; views never import axios |
| Modal backdrop + box duplicated per view | AppModal.vue — reusable, teleported to body |
| No type definitions for future modules | Typed interfaces for all 4 future modules |
| No service layer for future modules | Full HTTP service per future module |

---

## Validation

| Check | Result |
|---|---|
| Login still works | ✓ (LoginView unchanged, auth flow unchanged) |
| Route protection still works | ✓ (guard uses `to.name !== 'login'`, unchanged) |
| Logout still works | ✓ (`router.push('/')` in AppHeader, unchanged) |
| Guests CRUD still works | ✓ (same service calls, same validation, same UX) |
| No `axios` import in GuestsView | ✓ (replaced with `isAxiosError` from api.ts) |
| No duplicated navigation | ✓ (single source: AppNav.vue) |
| API contracts unchanged | ✓ (no endpoint URL or payload modified) |
| `npm run build` succeeds | ✓ (97 modules, 0 warnings) |

---

## Build result

```
vite v8.0.16 building client environment for production...
✓ 97 modules transformed.

dist/assets/index-DcL-yJHq.css    8.40 kB │ gzip: 2.20 kB
dist/assets/index-DqOOxABz.js   148.54 kB │ gzip: 56.28 kB

✓ built in 287ms
```

---

## Behavioral changes

**None.** Every user-visible behavior is identical to the pre-refactor state:
- Same login form, same credentials flow, same error messages.
- Same URLs for all routes.
- Same navigation links, same active-link highlighting.
- Same Guests table, same modals, same form validation, same delete confirmation.
- Same logout behavior.
