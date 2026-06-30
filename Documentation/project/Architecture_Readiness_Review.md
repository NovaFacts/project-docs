# Architecture Readiness Review

**Date:** 2026-06-27
**Scope:** Full project architecture — Spring Boot 3.5 backend, Vue 3 frontend
**Trigger:** Pre-development review before implementing remaining business modules
**Modules excluded from scope:** Guest module functionality (previously audited and fixed)

---

## Executive Summary

The project has a clean, feature-package backend architecture, a consistent service-controller-repository layering, and a working JWT security stack. The Vue 3 frontend follows a clear views-and-services separation, now with a shared header component and route protection in place.

**Two critical issues must be resolved before new modules are implemented.** Both concern the service layer and will compound with every new module added. All other findings are improvements that reduce future maintenance cost but do not block development.

**Verdict: Architecture is conditionally suitable for continuing development.** Fix the two critical issues first.

---

## Critical Issues

### C-1 — `booking` package has no integration path and blocks the next module

**Category:** Package organization, architecture
**Files:** `booking/model/Booking.java`, `booking/service/BookingValidator.java`, `booking/service/InvoiceCalculator.java`

The `booking` package contains three plain Java classes with no Spring annotations, no JPA mapping, and no controller. They encode real business rules:

- `Booking` — immutable value object; constructor rejects `guestCount < 1 || > 4` and `duration < 1 || > 30`
- `BookingValidator` — validates a `Booking` against availability constraints
- `InvoiceCalculator` — computes totals with 19% IVA and a 10% discount for stays ≥ 7 nights

22 unit tests cover these classes with explicit boundary cases. These tests pass. The rules they encode are authoritative domain rules.

The upcoming modules — `reserva`, `anticipo`, `penalidad`, `factura`, `devolucion` — all build on booking as the root aggregate. Before any of these are implemented, the team must make an explicit architectural decision:

**Option A — Integrate:** Keep `Booking` as a domain value object, annotate `BookingValidator` and `InvoiceCalculator` as `@Service`, create a separate `Reserva` JPA entity, and wire these domain classes into the new service layer. The validation logic is reused; the persistence model is separate.

**Option B — Replace:** Delete the three classes (after porting their business rules into the new `ReservaService`) and the orphaned test classes. Start the booking module from scratch following the `Guest` module pattern. The 22 tests become the specification to reimplement.

**Option C — default (avoid):** Add a new `reserva/` package with a JPA `Reserva` entity while leaving the `booking/` package untouched. This silently creates two parallel booking models. Developers will not know which one to trust. Business rule duplications will appear immediately.

Without an explicit decision, Option C happens by default. Document the choice in a code comment or package-level README before booking development begins.

**Impact if deferred:** Guaranteed architectural split in the booking module. Duplicated business rules. Confusing test suite.

---

### C-2 — No `@Transactional` boundary established in the service layer

**Category:** Spring Boot best practices, layer separation, scalability
**Files:** `guest/service/GuestService.java`, `auth/service/UserService.java`

No service method in the project is annotated with `@Transactional`. For the existing modules, this is a latent risk that hasn't triggered a visible failure yet. For the next modules, it will produce silent data corruption.

**Why it becomes critical with multi-entity modules:**

The upcoming booking module involves operations that must be atomic:

```
createBooking():
    1. INSERT INTO reserva (...)        ← writes first entity
    2. INSERT INTO anticipo (...)       ← writes second entity, derived from first
```

If step 2 throws a `DataIntegrityViolationException` (or any other runtime exception), step 1 is already committed. The database now contains a booking with no advance payment — an inconsistent state that cannot be recovered automatically.

The same applies to invoice generation (must link to reserva + guest atomically) and refund processing (must update both factura and devolucion atomically).

**Why deferring the pattern now is more expensive:**

The `GuestService` is the reference implementation that future service authors will study and copy. It currently shows no `@Transactional`. Every new service written in its image will omit it. Adding `@Transactional` retroactively to six modules is more work than establishing the pattern now on two services.

**Minimum required fix — add to all state-modifying service methods:**

```java
// GuestService.java
@Transactional
public GuestResponse create(CreateGuestRequest request) { ... }

@Transactional
public GuestResponse update(Long id, UpdateGuestRequest request) { ... }

@Transactional
public void delete(Long id) { ... }
```

```java
// UserService.java
@Transactional
public UserResponse createUser(CreateUserRequest request) { ... }

@Transactional
public void deleteUser(Long id) { ... }
```

Read-only methods should add `@Transactional(readOnly = true)` to allow the JPA provider to optimize dirty checking:

```java
@Transactional(readOnly = true)
public List<GuestResponse> findAll() { ... }

@Transactional(readOnly = true)
public GuestResponse findById(Long id) { ... }
```

`UserService.login()` is a read with a side-effect (token generation) — annotate as `@Transactional(readOnly = true)` since it performs no writes.

**Import required:** `import org.springframework.transaction.annotation.Transactional;`

**Impact if deferred:** Booking and invoice creation will produce orphaned records on any failure in a multi-step write. These records cannot be automatically cleaned up and will corrupt reporting and financial calculations.

---

## Recommended Issues

### R-1 — CORS origin is hardcoded

**Category:** Security configuration, scalability
**File:** `config/SecurityConfig.java:50`

```java
configuration.setAllowedOrigins(List.of("http://localhost:5173"));
```

Any environment where the frontend runs on a different host, port, or protocol receives `403 Forbidden` on all preflight requests. This includes: a staging server, a professor's machine, a CI preview environment, and any Docker-based deployment of the frontend.

**Fix:**

```java
String allowedOrigin = System.getenv().getOrDefault("ALLOWED_ORIGIN", "http://localhost:5173");
configuration.setAllowedOrigins(List.of(allowedOrigin));
```

Add `ALLOWED_ORIGIN=http://localhost:5173` to `docker-compose.yml` under `spring_app.environment` and document in a backend `.env.example`.

---

### R-2 — `UserDetailsServiceImpl` grants empty authorities; RBAC is structurally blocked

**Category:** Security configuration, scalability
**File:** `auth/service/UserDetailsServiceImpl.java:25-29`

```java
return org.springframework.security.core.userdetails.User
        .withUsername(user.getUsername())
        .password(user.getPassword())
        .authorities(Collections.emptyList())   // ← no roles granted
        .build();
```

The `User` entity has a `rolId` field (`@Column(name = "rol_id")`). The `Esquema_BD.sql` defines a `rol` table. Despite this, the role is never loaded into the Spring Security context. As a result:

- `@PreAuthorize("hasRole('ADMIN')")` would evaluate to `false` for every user
- `SecurityContextHolder.getContext().getAuthentication().getAuthorities()` always returns an empty collection
- Any future endpoint that needs to be restricted by role cannot be protected with standard Spring Security method annotations

When the booking module introduces operations that should be admin-only (e.g., applying penalties, issuing refunds), there is no functional security mechanism to enforce them.

**Fix — wire `rolId` into a `GrantedAuthority`:**

```java
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.List;

@Override
public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new UsernameNotFoundException("Usuario no encontrado: " + username));
    String role = "ROLE_USER_" + user.getRolId();  // or map rolId to a name via enum/table
    return org.springframework.security.core.userdetails.User
            .withUsername(user.getUsername())
            .password(user.getPassword())
            .authorities(List.of(new SimpleGrantedAuthority(role)))
            .build();
}
```

The exact role names depend on the `rol` table schema. Establishing the authority-mapping pattern now ensures `@PreAuthorize` works from the first endpoint that needs it.

---

### R-3 — No global 401 response handler; every module will need its own fallback

**Category:** Vue application structure, maintainability
**File:** `services/api.ts`

The Axios instance has a request interceptor (attaches the Bearer token) but no response interceptor. When a JWT expires, all API calls return 401. Each view catches this in its own `catch` block and displays a generic message:

```typescript
// GuestsView.vue — current behavior on 401
errorMessage.value = 'No se pudo cargar la lista de huéspedes. Verifica tu conexión.';
```

The user sees a connectivity error with a "Retry" button. There is no path to recovery except navigating manually to `/`.

Every new module view will reproduce this pattern, accumulating per-module fallback messages that all mean "your session expired."

**Fix — add a response interceptor to `api.ts`:**

```typescript
import router from '../router';

api.interceptors.response.use(
    (response) => response,
    (error) => {
        if (axios.isAxiosError(error) && error.response?.status === 401) {
            localStorage.removeItem(TOKEN_KEY);
            router.push('/');
        }
        return Promise.reject(error);
    }
);
```

This handles expired sessions globally. Individual view catch blocks continue to handle domain-level errors (404, 409, 422) as before.

---

### R-4 — Controller response type inconsistency will spread to new modules

**Category:** Code consistency, REST API design
**Files:** `guest/controller/GuestController.java`, `auth/controller/AuthController.java`

Within the same `GuestController`, response types are inconsistent:

| Method | Return type | HTTP status |
|---|---|---|
| `getAll()` | `List<GuestResponse>` (raw) | 200 (implicit) |
| `getById()` | `GuestResponse` (raw) | 200 (implicit) |
| `create()` | `ResponseEntity<GuestResponse>` | 201 (explicit) |
| `update()` | `GuestResponse` (raw) | 200 (implicit) |
| `delete()` | `ResponseEntity<Void>` | 204 (explicit) |

`AuthController.login()` also returns a raw `LoginResponse` (implicit 200) rather than `ResponseEntity`.

The inconsistency is that status codes requiring explicit control (`201`, `204`) use `ResponseEntity`, while others use raw returns. This creates two competing patterns that new module developers will follow arbitrarily.

**Fix — standardize `update()` to use `ResponseEntity`:**

```java
// GuestController.java
@PutMapping("/{id}")
public ResponseEntity<GuestResponse> update(
        @PathVariable Long id,
        @Valid @RequestBody UpdateGuestRequest request) {
    return ResponseEntity.ok(guestService.update(id, request));
}
```

Apply the same to `AuthController.login()`. Read-only methods returning collections (`getAll()`, `getUsers()`) can remain as raw returns — this is idiomatic Spring Boot.

---

### R-5 — `GlobalExceptionHandler` silently discards unexpected exceptions

**Category:** Maintainability, Spring Boot best practices
**File:** `common/GlobalExceptionHandler.java:41-44`

```java
@ExceptionHandler(Exception.class)
public ResponseEntity<Map<String, String>> handleGeneric(Exception ex) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of("error", "Error interno del servidor"));
}
```

The generic handler catches all unhandled exceptions and returns 500, but never logs the exception. A `NullPointerException`, a misconfigured dependency, a failed database connection, or a bug in a new service all produce the same response with no server-side trace.

During development of new modules, this makes debugging significantly harder. The only visible indication of an error is the 500 response to the client.

**Fix — add structured logging:**

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Error interno del servidor"));
    }
}
```

This is one line of configuration and one line of code. The payoff during new module development is immediate.

---

### R-6 — `App.vue` global `text-align: center` will misalign content in every future view with a table

**Category:** Vue application structure, maintainability
**File:** `App.vue:17`

```css
#app {
  text-align: center;   /* not scoped — applies to all descendants */
  color: #2c3e50;
}
```

`text-align` is inherited. Every element in the application inherits `center` unless it explicitly overrides it. In `GuestsView.vue`, `th` cells explicitly set `text-align: left`, preventing them from being centered. However, `td` cells do not — they inherit `center` from `#app` and display data center-aligned.

Every new module that renders a table will exhibit the same misalignment unless developers remember to add `text-align: left` to their `td` elements.

**Fix:**

```css
/* App.vue */
#app {
  /* remove text-align: center */
  color: #2c3e50;
}
```

No component in the project relies on `text-align: center` from `#app`. The login card is centered via flexbox, not text alignment. The dashboard welcome text is centered via `align-items: center` on the flex container.

---

## Optional Issues

### O-1 — `router/index.js` is plain JavaScript in a TypeScript project

**Category:** Code consistency
**File:** `router/index.js`

All view files, service files, and type definitions use TypeScript. The router is the only file in `src/` that remains `.js`. The navigation guard's `to.name` comparison (`to.name !== 'login'`) is untyped — a typo in the route name string fails silently at runtime rather than at compile time.

**Fix:** Rename to `router/index.ts`. The import in `main.js` (`import router from './router'`) requires no change; Vite resolves `.ts` transparently.

---

### O-2 — `GuestsView.vue` at 551 lines establishes a pattern that will be replicated

**Category:** Vue application structure, scalability
**File:** `views/GuestsView.vue`

At 551 lines, `GuestsView.vue` manages the list, the create/edit modal, the delete confirmation modal, all reactive state, all API calls, and all local error handling in a single component. For the guest module in isolation this is functional, but this will be the template authors of new modules follow.

A booking module following the same pattern would manage the reservation list, a booking creation form (more fields than guest), a status-update modal, a payment confirmation modal, and multiple error states in a single 700+ line component.

**Suggested convention to establish now (not required, but prevents future pain):**

- Views own: data loading, state orchestration, API calls
- Feature-specific modals (form modals, confirmation modals) are extracted as sub-components under `components/[module]/`

This does not require refactoring `GuestsView.vue` now. It means the booking module would have `BookingFormModal.vue` and `BookingList.vue` as separate components from day one, rather than being retrofitted later.

---

### O-3 — `JwtService.extractClaim` is declared `public`

**Category:** Code consistency
**File:** `auth/jwt/JwtService.java:50`

```java
public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
```

This is an internal parsing utility. Its public visibility allows any class in the project to call it directly, bypassing the intended interface (`extractUsername`, `isTokenValid`). Change to `private`.

---

### O-4 — `LoginResponse` carries a `message` field that the frontend never reads

**Category:** API design, maintainability
**Files:** `auth/dto/LoginResponse.java`, `auth/controller/AuthController.java`, `services/authService.ts`

```java
return new LoginResponse(token, "Login exitoso");
```

```typescript
// authService.ts — only token is read
const token: string = response.data.token;
```

The `"Login exitoso"` string is always the same and is never read by the client. It adds a field to the API contract that implies a user-facing message system that doesn't exist.

**Fix:** Remove `message` from `LoginResponse` and its constructor.

---

### O-5 — `UserService.deleteUser()` returns 204 for non-existent IDs

**Category:** Code consistency, REST API design
**File:** `auth/service/UserService.java:36-38`

```java
public void deleteUser(Long id) {
    userRepository.deleteById(id);  // no-op in Spring Data JPA 3 if ID not found
}
```

`GuestService.delete()` throws 404 for missing IDs (via `getOrThrow`). `UserService.deleteUser()` silently succeeds. The same HTTP verb on the same type of operation produces different semantics depending on which module handles it.

---

### O-6 — `AppHeader` is imported per-view; a layout component would scale better

**Category:** Vue application structure, scalability
**Files:** `DashboardView.vue`, `GuestsView.vue`

Every authenticated view must currently import and include `<AppHeader />`:

```typescript
import AppHeader from '@/components/AppHeader.vue';
```

An alternative is an `AppLayout.vue` wrapper registered at the router level:

```javascript
// router/index.js — alternative approach
{
  path: '/dashboard',
  component: () => import('../views/DashboardView.vue'),
  meta: { layout: 'AppLayout' }
}
```

The per-import approach is simpler at two views. At five or six views, the layout component becomes obviously worth the upfront investment. This is optional now; reassess when the third authenticated view is added.

---

### O-7 — `style.css` is unused dead code

**Category:** Maintainability
**File:** `src/style.css` (297 lines)

This is the Vite project template's default stylesheet. It is not imported by `main.js`, `App.vue`, or any other file. It is never loaded by the browser. Safe to delete without any functional effect.

---

### O-8 — `getGuest(id)` is exported but never consumed

**Category:** Maintainability
**File:** `services/guestService.ts:9-12`

The function is defined and exported, but no view imports it. The edit modal pre-fills from the already-loaded list without a detail API call. Remove until a guest detail view is implemented.

---

## Potential Regressions from Recent Refactors

The following changes were made in the preceding cleanup session. Each is verified to be regression-free.

### `AppHeader.vue` extraction

**Risk assessed:** Logout function and router dependency moved from `DashboardView` and `GuestsView` to `AppHeader`. If the move introduced a missing import or an incorrect path, the logout button would fail silently or throw a runtime error.

**Verification:** `npm run build` completed with 90 modules, zero errors, zero warnings. The `@/assets/logo.png` path resolves correctly from `src/components/AppHeader.vue` because `@/` is always rooted at `src/` regardless of component location in Vite.

**Status: No regression.**

---

### Navigation guard in `router/index.js`

**Risk assessed:** Incorrect guard logic could cause an infinite redirect loop (login page redirecting to itself), or could block authenticated users from accessing protected routes.

**Logic verified:**

```javascript
router.beforeEach((to) => {
  if (to.name !== 'login' && !localStorage.getItem(TOKEN_KEY)) {
    return { name: 'login' };
  }
});
```

- Unauthenticated user navigates to `/dashboard` → `to.name !== 'login'` is true, token absent → redirect to login ✓
- Unauthenticated user navigates to `/` (login) → `to.name !== 'login'` is false → guard passes, login renders ✓
- No circular redirect possible: the redirect target is the login route, and the login route always passes the guard ✓
- Authenticated user navigates to `/dashboard` → token present → guard passes ✓
- On successful login, `localStorage.setItem(TOKEN_KEY, token)` is called synchronously before `router.push('/dashboard')`, so the token is present when the guard fires for the subsequent navigation ✓
- `TOKEN_KEY` is imported from `api.ts` rather than duplicated as a magic string ✓

**One minor observation (not a regression, optional fix):** No catch-all route (`/:pathMatch(.*)*`) is defined. An authenticated user navigating to an undefined URL sees a blank `<router-view />` rather than a redirect. Add a catch-all redirect to `/dashboard` if this matters.

**Status: No regression.**

---

### `spring.jpa.open-in-view=false`

**Risk assessed:** Disabling OSIV could expose lazy-loading issues if any code path accesses entity associations outside of a service method.

**Verification:** The entire project uses DTO projection exclusively. Every controller receives a DTO from the service, never an entity. No `@OneToMany`, `@ManyToOne`, or other association is mapped on any entity (`User.java` and `Guest.java` have only simple column fields). There is no code path that could trigger lazy loading.

The only behavioral difference is that Hibernate now closes the session as soon as the service method returns, which is the intended behavior.

**Status: No regression.**

---

## Architecture Verdict

| Layer | Status |
|---|---|
| Backend package organization | Solid — feature-package structure scales cleanly to new modules |
| Backend layer separation | Correct — controllers delegate, services own logic, repositories are pure interfaces |
| Security configuration | Functional — JWT filter, BCrypt, stateless sessions, correct entry point handling |
| Exception handling | Consistent — `@RestControllerAdvice` covers all exception types; logging gap is Recommended |
| Vue application structure | Clean — views-services-types separation, shared header extracted, route guards active |
| Router architecture | Functional — guard prevents unauthenticated access; JS/TS inconsistency is Optional |
| Shared components | Established — `AppHeader.vue` correctly centralized; pattern is scalable |

**The two Critical issues (C-1, C-2) must be resolved before implementing the `reserva`, `anticipo`, `factura`, `penalidad`, or `devolucion` modules.** The `booking` package decision is a prerequisite for any booking-related development. The `@Transactional` pattern must be in place before any service method performs multi-entity writes.

Once C-1 and C-2 are addressed, the project architecture is ready for continued module development.

---

*End of Architecture Readiness Review — NovaFacts — 2026-06-27*
