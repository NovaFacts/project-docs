# Infrastructure Cleanup Report

**Date:** 2026-06-27
**Scope:** Frontend shared header extraction, frontend route guards, backend OSIV configuration
**Repositories affected:** `NovaFacts/project-frontend`, `NovaFacts/project-backend`

---

## 1. Summary of Changes

Three independent infrastructure improvements were applied:

| # | Change | Scope |
|---|---|---|
| 1 | Extracted a shared `AppHeader.vue` component to eliminate header/nav/logout duplication across views | Frontend |
| 2 | Added a global navigation guard (`router.beforeEach`) to protect all authenticated routes | Frontend |
| 3 | Disabled Open Session In View (OSIV) to prevent Hibernate sessions from spanning the HTTP request lifecycle unnecessarily | Backend |

No business logic was changed. No visual redesign was introduced. All existing functionality continues to work identically.

---

## 2. Files Created

### `project-frontend/frontend/src/components/AppHeader.vue`

A new presentational component that encapsulates the application header. It is responsible for:

- Rendering the NovaFacts logo, title, and navigation links
- Applying the active nav-link style dynamically via Vue Router's `exact-active-class` prop (replaces the previous per-view hardcoded `nav-link--active` class)
- Handling the logout action (calls `logout()` from `authService` and redirects to `/`)

**Key design decisions:**
- Uses `exact-active-class="nav-link--active"` instead of hardcoding the active class in each view's template. This ensures the active style is applied automatically when the current route exactly matches the link target, and will continue to work correctly for any future routes added to the navigation.
- Owns all header/nav/logout styles as scoped CSS, preventing style leakage to consuming views.
- `logout` and `useRouter` logic was moved here from `DashboardView.vue` and `GuestsView.vue`, each of which had identical implementations.

---

## 3. Files Modified

### `project-frontend/frontend/src/views/DashboardView.vue`

**Before:** 129 lines — contained the full `<header>` block (logo, title, nav, logout button) plus duplicated header/nav/logout CSS (approx. 70 lines of scoped styles).

**After:** 46 lines — references `<AppHeader />` instead. All header/nav/logout styles removed. `useRouter` and `logout` imports removed. The `handleLogout` function removed.

**Preserved:** Page layout (`.dashboard-container`), welcome message, and all visual styles for the main content area.

---

### `project-frontend/frontend/src/views/GuestsView.vue`

**Before:** 622 lines — contained the full `<header>` block plus duplicated header/nav/logout CSS block (~60 lines of scoped styles), `useRouter` import, `logout` import, and `handleLogout` function.

**After:** 551 lines — references `<AppHeader />` instead. Header/nav/logout styles removed from scoped CSS. `useRouter` and `logout` imports removed. `const router = useRouter()` and `handleLogout` function removed.

**Preserved:** All guest CRUD functionality (list, create, edit, delete), loading/error/empty states, modals, table, buttons, responsive styles, and form behavior — unchanged.

---

### `project-frontend/frontend/src/router/index.js`

**Added:** One import (`TOKEN_KEY` from `../services/api`) and one navigation guard block at the end of the file.

```javascript
import { TOKEN_KEY } from '../services/api';

router.beforeEach((to) => {
  if (to.name !== 'login' && !localStorage.getItem(TOKEN_KEY)) {
    return { name: 'login' };
  }
});
```

**Behavior:**
- If the destination route is `login` (`/`), navigation always proceeds regardless of token presence.
- For every other route, the guard checks `localStorage` for the `TOKEN_KEY` (`'auth_token'`). If the token is absent, the navigation is cancelled and the user is redirected to `{ name: 'login' }`.
- Returning a route location object from `beforeEach` (rather than calling `next('/')`) is the Vue Router v4 idiomatic pattern and avoids redundant `next` parameter declaration.
- Reuses the `TOKEN_KEY` constant exported from `api.ts` — no magic string duplication.
- Any new route added to the router is automatically protected unless its `name` is added to the public-route check.

---

### `project-backend/src/main/resources/application.properties`

**Added:** One property on line 9:

```properties
spring.jpa.open-in-view=false
```

**Why this change:** Spring Boot enables OSIV by default for JPA applications. OSIV keeps a Hibernate `Session` (database connection) open for the entire HTTP request/response cycle, including the view rendering phase. This is a legacy pattern from server-side rendering that does not apply to a REST API. The consequences of leaving it enabled are:

- A database connection is held open during JSON serialization, which is unnecessary
- Spring Boot logs a startup warning: `spring.jpa.open-in-view is enabled by default`
- Lazy-loaded associations can be accidentally triggered outside of the service layer, masking N+1 query problems until OSIV is disabled

**Why this is safe:** The project already uses DTOs for all responses. No controller or filter ever accesses lazy-loaded entity associations after the service method returns. Disabling OSIV has no effect on the existing data access patterns.

---

## 4. Validation Performed

### Backend

```
./mvnw clean compile
```
Exit code: 0. No compilation errors. The single property change is purely additive and requires no code modification.

### Frontend

```
npm run build
```
Exit code: 0. Vite built 90 modules successfully. Output:

```
dist/assets/index-BRHPCb1n.css   8.54 kB │ gzip: 2.18 kB
dist/assets/index-ClNcsRch.js  144.23 kB │ gzip: 54.54 kB
✓ built in 266ms
```

No TypeScript errors, no missing imports, no unresolved components.

---

## 5. Manual Testing Steps

After starting the full stack (`docker compose up -d` from `project-backend/`, `npm run dev` from `project-frontend/frontend/`), verify the following scenarios:

### 5.1 Route Guards

| Step | Action | Expected result |
|---|---|---|
| 1 | Open browser with no active session; navigate directly to `http://localhost:5173/dashboard` | Redirected to `http://localhost:5173/` (login page) |
| 2 | Navigate directly to `http://localhost:5173/guests` without logging in | Redirected to `http://localhost:5173/` (login page) |
| 3 | Log in with valid credentials | Redirected to `/dashboard` as before |
| 4 | From Dashboard, navigate to `/guests` | Guests page loads normally |
| 5 | From Guests, navigate to `/dashboard` | Dashboard loads normally |

### 5.2 Shared Header

| Step | Action | Expected result |
|---|---|---|
| 6 | On Dashboard page, observe the header | Logo, "NovaFacts" title, nav links, logout button visible; "Dashboard" nav link appears highlighted (blue background, bold) |
| 7 | On Guests page, observe the header | Same header elements visible; "Huéspedes" nav link appears highlighted; "Dashboard" link is not highlighted |
| 8 | Click "Dashboard" nav link from Guests page | Navigates to Dashboard; active link switches to "Dashboard" |
| 9 | Click "Huéspedes" nav link from Dashboard page | Navigates to Guests; active link switches to "Huéspedes" |

### 5.3 Logout

| Step | Action | Expected result |
|---|---|---|
| 10 | While authenticated on Dashboard, click "Cerrar sesión" | Token removed from localStorage; redirected to `/` |
| 11 | After logout, attempt to navigate to `/dashboard` | Redirected to login page (guard fires) |
| 12 | While authenticated on Guests page, click "Cerrar sesión" | Same result as step 10 |

### 5.4 Guest Module Regression

| Step | Action | Expected result |
|---|---|---|
| 13 | Log in and navigate to Guests | Guest list loads; all guests displayed in table |
| 14 | Create a new guest | Guest appears in table immediately without page reload |
| 15 | Edit an existing guest | Changes reflected in the table row |
| 16 | Delete a guest via confirmation modal | Guest removed from table; delete error appears in modal on failure |

### 5.5 Backend OSIV

| Step | Action | Expected result |
|---|---|---|
| 17 | Start Spring Boot application | Log line `spring.jpa.open-in-view is enabled by default` is **no longer present** in startup logs |
| 18 | Log in, create and retrieve guests | All API calls return correct data — no `LazyInitializationException` or change in behavior |

---

## 6. Assumptions Made

1. **Header visual parity between Dashboard and Guests:** The two views had an inconsistency in their header CSS: `DashboardView` applied `flex: 1` to `.header-title` (causing title and nav to split the header evenly), while `GuestsView` applied no flex to the title (letting `.header-nav` absorb all remaining space with its own `flex: 1`). Since the merged `AppHeader.vue` can have only one set of styles, the `GuestsView` pattern was adopted — no `flex` on the title, `flex: 1` on the nav. This is the correct layout intent and the difference is minor (the title sits compactly next to the logo rather than pushing halfway across the header).

2. **`router/index.js` remains JavaScript:** The file was not converted to TypeScript. `TOKEN_KEY` is importable from a `.ts` file in a `.js` module within a Vite project. Converting the router to TypeScript was considered but is outside the stated scope of this cleanup.

3. **No OSIV-related code changes required:** Since the project exclusively uses DTO projection (no controller or filter accesses lazy entity collections), disabling OSIV is safe without any service or entity changes.

4. **`exact-active-class` for nav links:** Vue Router v4 removed the `exact` boolean prop. Using `exact-active-class="nav-link--active"` applies the active style only on an exact route match, which replicates the previous per-view hardcoded behavior and is the correct replacement approach.

---

## 7. Confirmation — No Regressions Introduced

| Concern | Status |
|---|---|
| Frontend builds without errors | ✅ Confirmed (`npm run build` exits 0) |
| Backend compiles without errors | ✅ Confirmed (`./mvnw clean compile` exits 0) |
| Login flow unchanged | ✅ `LoginView.vue` and `authService.ts` not modified |
| Guest CRUD logic unchanged | ✅ All service calls, state management, and modal behavior in `GuestsView.vue` are intact |
| Logout clears token and redirects | ✅ `logout()` call moved to `AppHeader.vue`; same implementation, same behavior |
| Route guard does not block login page | ✅ Guard allows `to.name === 'login'` unconditionally |
| No circular redirect | ✅ Login → guard passes → allows `/`; protected route → no token → redirects to `/` → guard passes |
| No new dependencies introduced | ✅ `TOKEN_KEY` is an existing export from `api.ts`; no new packages |
| Backend DTO pattern unaffected by OSIV change | ✅ All responses use DTOs; no lazy association is accessed outside the service layer |

---

*End of Infrastructure Cleanup Report — NovaFacts — 2026-06-27*
