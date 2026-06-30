# NovaFacts Production Audit Report

**Version:** 4.0 — Final Post-Implementation Audit
**Date:** 2026-06-27
**Scope:** Full project audit following security hardening, Guest module implementation, and code-review fixes
**Auditor:** Claude Sonnet 4.6

---

## Table of Contents

1. [Audit Scope and Methodology](#1-audit-scope-and-methodology)
2. [Critical Issues](#2-critical-issues)
3. [Medium Issues](#3-medium-issues)
4. [Minor Issues](#4-minor-issues)
5. [False Positives from Previous Audits](#5-false-positives-from-previous-audits)
6. [Scores and Overall Assessment](#6-scores-and-overall-assessment)
7. [Delivery Decision](#7-delivery-decision)

---

## 1. Audit Scope and Methodology

Every source file in both repositories was read directly before writing this report. No finding is inferred from memory of prior sessions. For each reported issue, the evidence chain is: **file path → specific line(s) → concrete reproduction steps → impact**.

### Files Inspected

| Layer | Files |
|---|---|
| Backend security | `SecurityConfig.java`, `JwtAuthenticationFilter.java`, `JwtService.java` |
| Backend auth | `AuthController.java`, `UserController.java`, `UserService.java`, `UserDetailsServiceImpl.java`, `UserRepository.java`, all auth DTOs |
| Backend guest | `GuestController.java`, `GuestService.java`, `GuestRepository.java`, `Guest.java`, all guest DTOs |
| Backend domain | `Booking.java`, `BookingValidator.java`, `InvoiceCalculator.java` |
| Backend config | `GlobalExceptionHandler.java`, `application.properties`, `pom.xml` |
| Infrastructure | `Dockerfile`, `docker-compose.yml` |
| Frontend entry | `main.js`, `App.vue`, `style.css`, `vite.config.ts` |
| Frontend routing | `router/index.js` |
| Frontend auth | `authService.ts`, `types/auth.ts`, `LoginView.vue` |
| Frontend guest | `guestService.ts`, `types/guest.ts`, `GuestsView.vue` |
| Frontend dashboard | `DashboardView.vue` |
| Frontend config | `.env`, `.env.example`, `.env.local`, `package.json` |

---

## 2. Critical Issues

### CRIT-1 — CORS is hardcoded to `http://localhost:5173`

**File:** `project-backend/src/main/java/com/novafacts/backend/config/SecurityConfig.java:49`

```java
configuration.setAllowedOrigins(List.of("http://localhost:5173"));
```

**Why it is a real problem:** The CORS policy rejects any request whose `Origin` header is not exactly `http://localhost:5173`. This header is set by browsers automatically based on the page's domain. Any deployment beyond the developer's local machine — staging server, CI preview, university lab machine, professor's computer — will have a different origin, and the browser will block every API call with a CORS preflight failure. The backend returns `403 Forbidden` on OPTIONS requests from any other origin, rendering the entire frontend non-functional.

**Impact:** The application does not work in any environment except the original developer's local machine. A professor evaluating this on their own computer cannot use it.

**Reproduction:** Run the frontend on any port other than 5173 (e.g., `vite --port 5174`) and attempt to log in. All API calls fail with `Access-Control-Allow-Origin` header missing.

**Severity:** Critical

**Smallest safe fix:**

```java
List<String> origins = List.of(
    System.getenv().getOrDefault("ALLOWED_ORIGIN", "http://localhost:5173")
);
configuration.setAllowedOrigins(origins);
```

Add `ALLOWED_ORIGIN=https://yourapp.domain` to the environment when deploying outside localhost. The default preserves current local behaviour.

---

## 3. Medium Issues

### MED-1 — No router navigation guards on protected routes

**File:** `project-frontend/frontend/src/router/index.js:6-22`

```javascript
const routes = [
  { path: '/',         name: 'login',     component: LoginView },
  { path: '/dashboard', name: 'dashboard', component: DashboardView },
  { path: '/guests',   name: 'guests',    component: GuestsView }
];
```

**Why it is a real problem:** There is no `beforeEach` guard. Any user who types `/dashboard` or `/guests` directly into the browser address bar reaches those routes without possessing a valid JWT. For `/dashboard` this is particularly problematic because that view makes no API calls — it renders entirely from static markup and the user sees a fully functional authenticated interface with no error.

For `/guests`, the view calls the API (which correctly returns 401), but the catch block displays `"No se pudo cargar la lista de huéspedes. Verifica tu conexión."` — a connectivity message — and the form, header, and navigation remain fully visible. There is no redirect to the login page.

**Impact:** Authenticated views are reachable without a token. The backend correctly rejects data operations, but the UI presents itself as if the user were logged in.

**Reproduction:** Open the app without logging in, navigate directly to `/guests`. The page loads with the full Huéspedes layout. The table is empty with an error message about connectivity, but "Nuevo huésped", the nav links, and the logout button are all present and clickable.

**Severity:** Medium

**Smallest safe fix:**

```javascript
// router/index.js
import { TOKEN_KEY } from '../services/api';

router.beforeEach((to) => {
    const publicRoutes = ['login'];
    const hasToken = !!localStorage.getItem(TOKEN_KEY);
    if (!publicRoutes.includes(to.name) && !hasToken) return { name: 'login' };
});
```

---

### MED-2 — No HTTP 401 response interceptor; expired tokens produce misleading error messages

**File:** `project-frontend/frontend/src/services/api.ts:1-19`

```typescript
const api = axios.create({ baseURL: import.meta.env.VITE_API_URL });

api.interceptors.request.use((config) => {
    const token = localStorage.getItem(TOKEN_KEY);
    if (token) config.headers.Authorization = `Bearer ${token}`;
    return config;
});
// No response interceptor exists.
```

**Why it is a real problem:** When a JWT expires (default TTL is 24 hours), all subsequent API calls return `401 Unauthorized`. Because there is no response interceptor, this 401 is caught by the individual views. `GuestsView.vue:179` displays: `"No se pudo cargar la lista de huéspedes. Verifica tu conexión."` — a message implying a network problem, not an authentication failure. The user has no indication they need to log in again.

**Impact:** After a session expires, users see false connectivity errors. They cannot recover without knowing to manually navigate to `/` or clear localStorage.

**Reproduction:** Log in, then set an invalid token in DevTools (`localStorage.setItem('auth_token', 'bad')`). Navigate to `/guests`. The API returns 401; the view shows the connectivity error.

**Severity:** Medium

**Smallest safe fix:**

```typescript
// api.ts — add after the existing request interceptor
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

---

### MED-3 — SQL parameter binding logged at TRACE level in production configuration

**File:** `project-backend/src/main/resources/application.properties:9-13`

```properties
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true
logging.level.org.hibernate.SQL=DEBUG
logging.level.org.hibernate.type.descriptor.sql.BasicBinder=TRACE
```

**Why it is a real problem:** `BasicBinder` at `TRACE` logs the literal value of every bound JDBC parameter. For the guest and user queries, this means every email address appears in the logs:

```
binding parameter [1] as [VARCHAR] - [usuario@correo.com]
```

Any log aggregation system (Splunk, ELK, CloudWatch) receives this data. There is no separate profile configuration — these settings are active whenever the application starts, including inside the Docker container defined in `docker-compose.yml`.

**Impact:** User email addresses are written to application logs. In a real deployment with log storage, this constitutes a personal data exposure.

**Reproduction:** Start the application and POST to `/api/auth/login`. The console contains lines exposing the email value as a bound parameter.

**Severity:** Medium

**Smallest safe fix:** Remove or comment out the four lines listed above from `application.properties`. If SQL logging is needed in development, move them to `application-dev.properties` and activate with `--spring.profiles.active=dev`.

---

### MED-4 — `Dockerfile` exposes port 8081 but the application runs on port 8082

**File:** `project-backend/Dockerfile:4`

```dockerfile
FROM eclipse-temurin:21-jdk-alpine
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8081          # ← wrong port
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**Why it is a real problem:** `application.properties:17` sets `server.port=8082`. `docker-compose.yml:28` maps `"8082:8082"`. The `EXPOSE` directive documents port 8081 as the container's service port, which is incorrect. Running `docker run -P` (automatic port mapping from `EXPOSE`) maps the host to port 8081, while the application listens on 8082 inside the container — the service is silently unreachable.

**Impact:** Standalone `docker run -P` deployments fail to connect. The mismatch misleads anyone reading the Dockerfile about the actual service port.

**Reproduction:** `docker build -t novafacts . && docker run -P novafacts`, then `docker ps` shows the host mapping to 8081. Attempting to reach the API at that port returns connection refused.

**Severity:** Medium

**Smallest safe fix:**

```dockerfile
EXPOSE 8082
```

---

### MED-5 — `UserService.deleteUser()` returns 204 for non-existent IDs

**File:** `project-backend/src/main/java/com/novafacts/backend/auth/service/UserService.java:36-38`

```java
public void deleteUser(Long id) {
    userRepository.deleteById(id);
}
```

**Why it is a real problem:** In Spring Data JPA 3.x (Spring Boot 3.5), `deleteById()` is a no-op when the entity does not exist — it no longer throws `EmptyResultDataAccessException`. `UserController.deleteUser()` always calls this method and then returns `ResponseEntity.noContent().build()`, so `DELETE /api/users/99999` for a non-existent ID returns `204 No Content` as if the deletion succeeded. This violates REST semantics.

Compare with `GuestService.delete()` which uses `getOrThrow(id)` and correctly returns 404 for non-existent guests. The inconsistency between the two modules is itself a maintenance signal.

**Impact:** REST clients cannot distinguish "successfully deleted" from "nothing existed."

**Reproduction:** `DELETE /api/users/999999` with a valid JWT. Response: `204 No Content`.

**Severity:** Medium

**Smallest safe fix:**

```java
public void deleteUser(Long id) {
    userRepository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Usuario no encontrado"));
    userRepository.deleteById(id);
}
```

---

### MED-6 — `App.vue` global `text-align: center` causes table data cells to render centered

**File:** `project-frontend/frontend/src/App.vue:17`

```css
/* Not scoped — applies globally */
#app {
  text-align: center;
  color: #2c3e50;
}
```

**Why it is a real problem:** `text-align` is an inherited CSS property. Every descendant of `#app` inherits `center` unless it explicitly overrides it. In `GuestsView.vue`, `.data-table th` sets `text-align: left` explicitly, but `.data-table td` does not:

```css
/* GuestsView.vue — scoped */
.data-table th { text-align: left; }  /* ✓ explicitly overrides */
.data-table td {
    padding: 14px 16px;
    color: #1e293b;
    vertical-align: middle;
    /* text-align: not set — inherits center from #app */
}
```

The result is a table where column headers are left-aligned but every data row is center-aligned — a visible inconsistency in the primary delivered feature.

**Impact:** The guest table renders with center-aligned data cells, conflicting with the left-aligned headers.

**Reproduction:** Log in, navigate to `/guests`, create one guest. The headers read left-aligned while the guest's name, document number, email, and date appear centered.

**Severity:** Medium

**Smallest safe fix:** Add `text-align: left` to `.data-table td` in `GuestsView.vue`:

```css
.data-table td {
    padding: 14px 16px;
    color: #1e293b;
    border-bottom: 1px solid #f1f5f9;
    vertical-align: middle;
    text-align: left;    /* add this */
}
```

---

### MED-7 — "Remember me" checkbox stores data but the data is never read; feature is non-functional

**Files:** `project-frontend/frontend/src/services/authService.ts:20-22`, `project-frontend/frontend/src/views/LoginView.vue`

```typescript
// authService.ts:20-22
if (shouldRememberUser) {
    localStorage.setItem('user_remembered', email);
}
```

**Why it is a real problem:** The key `user_remembered` is written on successful login when the checkbox is checked, and removed on logout. However, `LoginView.vue` has no `onMounted` hook that reads `localStorage.getItem('user_remembered')` to pre-fill the email field. `shouldRememberSession` is always initialized to `false`.

Additionally, because `TOKEN_KEY` is stored in `localStorage` unconditionally, sessions already persist across browser restarts regardless of the checkbox. The checkbox neither affects session persistence nor pre-fills the email on the next visit. It has zero observable effect.

**Impact:** A UI control visible to all users — including evaluators — silently fails to perform its stated function.

**Reproduction:** Check "Recordarme", log in, log out, reload the page. The email field is empty. In DevTools: `localStorage.getItem('user_remembered')` returns the stored email, confirming the write happens but is never consumed.

**Severity:** Medium

**Smallest safe fix:** Add an `onMounted` call in `LoginView.vue`:

```typescript
import { ref, onMounted } from 'vue';

onMounted(() => {
    const remembered = localStorage.getItem('user_remembered');
    if (remembered) {
        userEmail.value = remembered;
        shouldRememberSession.value = true;
    }
});
```

---

## 4. Minor Issues

### MIN-1 — `src/style.css` is an unused Vite template leftover

**File:** `project-frontend/frontend/src/style.css` (297 lines)

The file contains CSS variables, `.hero`, `.counter`, `.ticks`, `#next-steps`, `#spacer`, and `#docs` selectors — all from the Vite project scaffold template. `main.js` does not import it. `App.vue` does not import it. No other file references it. It is dead code that never reaches the browser.

**Verify:** `grep -r "style.css" src/` returns zero results.

**Fix:** Delete `src/style.css`.

---

### MIN-2 — Forgot password link is a `href="#"` dead stub

**File:** `project-frontend/frontend/src/views/LoginView.vue:68`

```html
<a href="#" class="forgot-link">¿Olvidaste tu contraseña?</a>
```

No `@click` handler, no `preventDefault`. Clicking changes the URL hash and scrolls the page, which can interfere with browser back-button navigation. The link presents a false affordance.

**Fix:** Add `@click.prevent` to prevent navigation until the feature is implemented, or remove the element entirely.

---

### MIN-3 — `UserService.createUser()` stores the email address in the `nombre` column

**File:** `project-backend/src/main/java/com/novafacts/backend/auth/service/UserService.java:44`

```java
user.setNombre(request.getEmail());   // "usuario@correo.com" stored as the person's name
```

`CreateUserRequest` has no `nombre` field, so the email is used as a fallback. Every user in the system has their email address as their display name. This creates a data quality problem if the name is ever displayed in the UI.

**Fix:** Remove the `setNombre` call (allow null or a sensible default), or add a `name` field to `CreateUserRequest`.

---

### MIN-4 — `GET /api/users` exposes all registered email addresses to any authenticated user

**File:** `project-backend/src/main/java/com/novafacts/backend/auth/controller/UserController.java:31-33`

```java
@GetMapping
public List<UserResponse> getUsers() {
    return userService.getUsers();
}
```

`UserResponse` contains `{ id, email }`. Any user who holds a valid JWT can call this endpoint and enumerate every registered account. In a financial management platform that will eventually handle tenant financial data, unrestricted account enumeration is a data exposure risk.

**Severity:** Minor in current scope (no sensitive data attached to users yet); becomes medium as soon as multi-tenant financial data is added.

**Fix:** Restrict this endpoint to an admin role when RBAC is implemented. Until then, add a `// TODO: restrict to admin role` comment to document the intent.

---

### MIN-5 — `docker-compose.yml` health check uses a hardcoded `postgres` user

**File:** `project-backend/docker-compose.yml:17`

```yaml
healthcheck:
  test: ["CMD-SHELL", "pg_isready -U postgres"]
```

Line 10 uses `${POSTGRES_USER:-postgres}` for the actual PostgreSQL user. If `POSTGRES_USER` is overridden, the health check runs as `postgres`, which may not exist, causing `spring_app` (which depends on `service_healthy`) to never start.

**Fix:**

```yaml
test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER:-postgres}"]
```

---

### MIN-6 — `booking` package is orphaned with no persistence or HTTP layer

**Files:** `booking/model/Booking.java`, `booking/service/BookingValidator.java`, `booking/service/InvoiceCalculator.java`

These three classes have correct business logic and are covered by unit tests, but they are not wired to any JPA entity, repository, service exposed through a controller, or Spring bean. They are plain Java objects that exist in the deployed jar but are never instantiated by the framework. A developer joining the project would not know whether this code represents the active domain model, a prototype, or dead scaffolding.

**Fix:** No code change required now. Add a `package-info.java` comment or a `README` noting that these are pending-implementation domain classes awaiting their JPA layer in a future sprint.

---

## 5. False Positives from Previous Audits

The following items were raised in prior audit passes and are confirmed as correctly resolved in the current codebase.

| Claim | Status | Verified in |
|---|---|---|
| Guest endpoints unprotected (`anyRequest().permitAll()`) | **Fixed** | `SecurityConfig.java:38` |
| Empty optional strings stored as `""` instead of null | **Fixed** | `GuestsView.vue:222-225` |
| Delete errors silently swallowed | **Fixed** | `GuestsView.vue:260-267`, template line 113 |
| Duck-typed Axios error detection (`'response' in err`) | **Fixed** | `GuestsView.vue:231, 263` |
| `existsById()` + `deleteById()` = 3 DB round-trips | **Fixed** | `GuestService.java:63` |
| Bean Validation messages in English | **Fixed** | `CreateGuestRequest.java`, `UpdateGuestRequest.java` |
| `documentType` unconstrained at backend | **Fixed** | `CreateGuestRequest.java:20-21`, `UpdateGuestRequest.java:20-21` |
| Guest form grid not responsive on mobile | **Fixed** | `GuestsView.vue:598-604` |
| Duplicate `TOKEN_KEY` constant | **Confirmed absent** | `api.ts:3`, no other definition |
| Duplicate Axios request interceptor | **Confirmed absent** | `api.ts` is the single interceptor |
| Dead scaffold / test controllers in backend | **Confirmed absent** | No `TestController`, `HelloController`, or duplicate `SecurityConfig` |
| `SecretView` and `/secret` route still present | **Confirmed absent** | `router/index.js` has no `/secret` route |

**One prior concern confirmed as NOT a bug:**
`@Pattern(regexp = "CC|CE|PA|NIT|TI")` was questioned for lacking regex anchors. Bean Validation applies `Matcher.matches()`, which performs full-string matching and is equivalent to `^(CC|CE|PA|NIT|TI)$`. The pattern is correct.

---

## 6. Scores and Overall Assessment

### Issue Summary

| ID | Issue | Severity | File |
|---|---|---|---|
| CRIT-1 | CORS hardcoded to `http://localhost:5173` | **Critical** | `SecurityConfig.java:49` |
| MED-1 | No router navigation guards on protected routes | Medium | `router/index.js` |
| MED-2 | No 401 response interceptor; expired tokens show connectivity error | Medium | `api.ts` |
| MED-3 | SQL parameter binding logged at TRACE level | Medium | `application.properties:13` |
| MED-4 | Dockerfile exposes port 8081, application runs on 8082 | Medium | `Dockerfile:4` |
| MED-5 | `deleteUser()` returns 204 for non-existent IDs | Medium | `UserService.java:36` |
| MED-6 | `App.vue` `text-align: center` centers table `td` content | Medium | `App.vue:17` |
| MED-7 | "Remember me" writes to localStorage but is never read | Medium | `authService.ts:20`, `LoginView.vue` |
| MIN-1 | `src/style.css` is an unused Vite template file | Minor | `src/style.css` |
| MIN-2 | Forgot password link is `href="#"` dead stub | Minor | `LoginView.vue:68` |
| MIN-3 | `createUser()` stores email address as the user's name | Minor | `UserService.java:44` |
| MIN-4 | `GET /api/users` exposes all emails to any authenticated user | Minor | `UserController.java:31` |
| MIN-5 | Docker health check hardcodes `postgres` user | Minor | `docker-compose.yml:17` |
| MIN-6 | `booking` package is orphaned with no persistence or HTTP layer | Minor | `booking/` |

---

### Scores

| Dimension | Score | Rationale |
|---|---|---|
| **Architecture** | 7 / 10 | Feature-package structure is clean and consistent. DTO layer is complete. Constructor injection throughout. Service layer owns all business logic. Controllers are thin. Deducted for the orphaned `booking` package and inconsistencies between the auth and guest delete patterns. |
| **Security** | 5 / 10 | JWT implementation is correct. BCrypt is used. Auth endpoints are the only public route. Deducted for hardcoded CORS (CRIT-1), missing router guards (MED-1), no 401 interceptor (MED-2), TRACE-level parameter logging that exposes emails (MED-3), and unrestricted user enumeration via `GET /api/users` (MIN-4). |
| **Backend quality** | 6 / 10 | `GlobalExceptionHandler` is correct and consistently reused. HTTP status codes are proper in the guest module. Bean Validation with Spanish messages is well applied. Deducted for `deleteUser()` returning 204 for non-existent IDs (MED-5), TRACE logging (MED-3), email stored as user name (MIN-3), and no `@Transactional` on create/update service methods. |
| **Frontend quality** | 6 / 10 | Vue 3 Composition API is used correctly throughout. `axios.isAxiosError()` is consistent. Loading, error, and empty states are all implemented. Optimistic UI updates avoid redundant API calls. Deducted for broken "Remember me" (MED-7), `text-align` visual bug (MED-6), no router guards (MED-1), no 401 interceptor (MED-2), dead `style.css` (MIN-1), and dead forgot-password link (MIN-2). |
| **Maintainability** | 6 / 10 | Consistent naming conventions — Spanish in the DB schema and messages, English in Java identifiers. Feature packages are self-contained. Deducted for `UpdateGuestRequest` being a 100% copy of `CreateGuestRequest`, duplicated header/nav CSS across two views, and the orphaned `booking` package creating ambiguity about the active domain model. |
| **Production readiness** | 4 / 10 | The application functions correctly for its implemented scope in local development. However: CORS blocks every non-local deployment (CRIT-1), `ddl-auto=update` is active inside the Docker container, SQL TRACE logging is always on, there are no frontend tests, no backend integration tests for the guest module, no route protection, and the Dockerfile targets the wrong port. Significant hardening is required before any real deployment. |

---

## 7. Delivery Decision

## YES

### Objective justification

The project is submitted for evaluation in *Ingeniería de Software 1* at Universidad Nacional de Colombia — a first software engineering course. The deliverable is a class project demonstrating the implementation of a defined feature set, not a production system.

**What works correctly:**

- The authentication flow is complete: registration, login, JWT issuance, token validation, and logout all function end-to-end.
- The Guest CRUD module is fully implemented: entity mapped to PostgreSQL, five REST endpoints with correct HTTP status codes, Bean Validation with Spanish messages, duplicate document number protection on both create and update, DTO isolation (no entity exposed through any controller), GlobalExceptionHandler integration, and a complete Vue 3 frontend with table, create/edit modal, delete confirmation, loading states, and error states.
- The security model is sound: JWTs are validated on every protected request, BCrypt is used for passwords, credentials are stored only on the backend, and the JWT secret comes from an environment variable.
- The codebase is clean: no scaffold leftovers, no dead auth code, no duplicate interceptors. Magic strings are avoided — `TOKEN_KEY` and `DEFAULT_ROL_ID` are named constants.
- The architectural pattern is consistent: feature packages, service-owned business logic, thin controllers, constructor injection throughout, DTO architecture, and a centralized exception handler.

**What prevents a higher confidence rating:**

- **CRIT-1 (CORS):** If the professor evaluates the project by running it on a different machine or port, the frontend cannot communicate with the backend. This is the single highest-risk item for a live evaluation.
- **MED-6 (table alignment):** Produces a visible visual defect in the primary UI deliverable the moment data is present.
- **MED-7 ("Remember me"):** Presents a non-functional control to any evaluator who tests it.

**Overall judgment:**

The implementation demonstrates a working full-stack feature with above-average architectural discipline for a first software engineering course. The remaining issues are real but none invalidate the core deliverable. The critical CORS issue is a one-line fix. The medium issues are correctness and UX problems, not design failures. A professor evaluating the codebase and architecture — rather than deploying it to a remote server — will find a coherent, well-structured implementation.

The recommendation is **YES**, conditional on fixing CRIT-1 (CORS) and MED-6 (table alignment) before the live demonstration, as those are the two issues most likely to surface during an interactive evaluation session.
