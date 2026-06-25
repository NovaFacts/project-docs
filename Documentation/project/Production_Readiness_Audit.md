# NovaFacts — Production Readiness Audit

**Date:** 2026-06-24
**Scope:** `project-backend`, `project-frontend`, `project-docs` (3 independent git repos), plus root-level workspace clutter.
**Method:** Every finding below was verified by directly opening the referenced file(s) (not inferred from prior summaries). Lockfile-reported dependency versions (Vue 3.5.38, Vue Router 5.1.0, Vite 8.0.16, TypeScript 6.0.3, Axios 1.18.0) were cross-checked against `package-lock.json` and installed `node_modules`, not assumed from training data.

---

# Executive Summary

**Overall project health:** Academic prototype, pre-MVP. One feature slice (`auth`) is implemented end-to-end but is broken at the integration seam (CORS, response contract, dead navigation). The domain core (bookings, invoicing, penalties, billing — the actual stated purpose of the system) exists only as disconnected POJOs with no controller, persistence, or API surface. Two unrelated UI implementations of "login" coexist, only one of which is reachable.

**Production readiness:** **Not production ready.** This is pre-alpha / coursework-stage code. There is no deployable, internally-consistent vertical slice you could put in front of real users today — the one user-facing flow that exists (login) is functionally broken end-to-end when run through the documented Docker stack or through the actually-mounted Vue view.

| Dimension | Score (1–10) | Rationale |
|---|---|---|
| Architecture | 4 | Clean feature-package intent in `auth/`, but `booking/` domain is fully disconnected from persistence/API, and dead config classes sit outside the Spring component-scan boundary |
| Security | 2 | No real authentication (no token issued), all endpoints `permitAll()`, unauthenticated user enumeration + deletion endpoint, hardcoded DB credentials committed to git, login error messages leak user existence |
| Frontend | 3 | The mounted login view performs no navigation on success; an entire parallel login/secret-reveal flow (components, view, route) is unreachable dead code; two incompatible `authService` modules coexist |
| Backend | 4 | `auth` slice itself is reasonably clean (constructor injection, DTOs, BCrypt), but no `@Valid`, no global exception handling, generic 500s, money modeled as `double` |
| Infrastructure | 3 | `docker-compose.yml` has a container-to-container DB connection string that points at the *host-mapped* port instead of the internal one — the dockerized backend cannot reach Postgres as configured |
| Documentation | 3 | Frontend `README.md` is a single empty heading; `estructura_repo.txt` files are stale (don't mention the TS migration files); the backend's own self-audit (`skill_back.md`) asserts secrets are externalized, which is contradicted by the plaintext password in both `application.properties` and `docker-compose.yml` |
| Maintainability | 5 | Small, single-responsibility classes and consistent naming inside the `auth` slice; dragged down by dead code, duplicate config classes, and an unfinished JS→TS migration left in place rather than removed |

**Main architectural risks:** the `booking` domain (the system's actual business purpose — bookings, invoicing, penalties) has no entity, repository, or controller; it is unreachable from any API. The relational schema in `Esquema_BD.sql` (roles, reservas, facturas, anticipos, penalidades) has no corresponding JPA model anywhere in the codebase.

**Main security risks:** unauthenticated `GET /api/users` (full user enumeration) and `DELETE /api/users/{id}` (arbitrary account deletion) with zero auth checks; no JWT/session token is ever issued despite the frontend expecting one; hardcoded production-shaped DB credentials in version control.

**Main maintainability risks:** duplicate/dead `SecurityConfig` and `PasswordConfig` classes outside the Spring scan path; duplicate `authService.js`/`authService.ts` with incompatible signatures both still wired into different components; an entire orphaned UI flow (`LoginForm.vue` → `SecretDisplay.vue`/`SecretView.vue`).

**Main production blockers:** (1) dockerized backend cannot connect to its database as configured; (2) the only implemented feature (login) does not actually authenticate anything meaningful or navigate anywhere on success; (3) no authorization model exists at all, so every endpoint, including destructive ones, is public.

---

# Critical Issues

## 1. Unauthenticated user enumeration and account deletion

**Repository:** project-backend
**Files:** `src/main/java/com/novafacts/backend/auth/controller/UserController.java`, `src/main/java/com/novafacts/backend/config/SecurityConfig.java`
**Relevant Class/Function:** `UserController.getUsers()`, `UserController.deleteUser(Long id)`, `SecurityConfig.securityFilterChain`
**Severity:** Critical
**Confidence:** High

### Problem
`SecurityConfig` configures `.anyRequest().permitAll()` with no exceptions. `UserController` exposes `GET /api/users` (returns every username/id in the system) and `DELETE /api/users/{id}` (deletes any user by id) with no authentication or authorization check anywhere in the call chain (Controller → `UserService` → `UserRepository`).

### Evidence
```
SecurityConfig.java:19-21   .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
UserController.java:28-36   @GetMapping public List<UserResponse> getUsers() { ... }
                             @DeleteMapping("/{id}") public void deleteUser(@PathVariable Long id) { userService.deleteUser(id); }
```

### Impact
Any unauthenticated client can enumerate all usernames and delete any account, including future admin accounts, with a single HTTP request.

### Recommended Resolution
Introduce real authentication (see Issue 2) and restrict `/api/users` (especially `DELETE`) to an authenticated, authorized role before any deployment beyond local dev.

---

## 2. No real authentication is ever issued — login is a dead end

**Repository:** project-backend / project-frontend
**Files:** `auth/dto/LoginResponse.java`, `auth/service/UserService.java`, `frontend/src/services/authService.ts`, `frontend/src/views/LoginView.vue`
**Relevant Class/Function:** `UserService.login`, `LoginResponse`, `authenticateUser`
**Severity:** Critical
**Confidence:** High

### Problem
`UserService.login` returns `new LoginResponse("Login exitoso")` — a static success string with no token, session id, or claims of any kind. There is no JWT/session mechanism anywhere in the backend (no `jjwt`/OAuth2 dependency in `pom.xml`, no filter, no `security/` package contents — the package exists but is empty). The frontend's TypeScript service nonetheless expects a token: `authService.ts` line `token: response.data.token` reads a field the backend never sends, so a "successful" login on the frontend always produces `token: undefined`, and `LoginView.vue` does nothing with it beyond a `console.log` — there is no `router.push` to any protected area on success (the redirect is left as a comment).

### Evidence
```
LoginResponse.java:3-9              private String message;  (no token field)
UserService.java:76                 return new LoginResponse("Login exitoso");
authService.ts:16                   token: response.data.token,   // backend never sends this
LoginView.vue:126-128                console.log('Login exitoso para el token:', result.token);
                                     // Aquí puedes redirigir con tu vue-router ej: router.push('/dashboard')
```

### Impact
There is no authenticated session anywhere in the system. Once a user "logs in," the application has no way to know they're logged in on any subsequent request, and the UI doesn't even navigate away from the login screen. This is not a partially-built JWT system — it's an unimplemented one with a frontend contract written against an API that doesn't exist.

### Recommended Resolution
Decide on a session strategy (stateless JWT vs. server session), implement it fully on the backend (issuance, validation filter, expiry, refresh if needed), and align the frontend contract (`AuthResult`/`authenticateUser`) to the actual response shape.

---

## 3. Dockerized backend cannot reach its own database

**Repository:** project-backend
**Files:** `docker-compose.yml`
**Relevant Class/Function:** `spring_app.environment.SPRING_DATASOURCE_URL`
**Severity:** Critical
**Confidence:** High

### Problem
`postgres_db` maps host port 5434 to the container's internal port 5432 (`"5434:5432"`). Container-to-container traffic on the Docker network uses the *internal* port, not the host-mapped one. `spring_app`'s `SPRING_DATASOURCE_URL` is set to `jdbc:postgresql://postgres_db:5434/novafacts_db` — port 5434, which Postgres is not listening on inside the network namespace.

### Evidence
```
docker-compose.yml:12-13   ports: ["5434:5432"]
docker-compose.yml:30      SPRING_DATASOURCE_URL=jdbc:postgresql://postgres_db:5434/novafacts_db
```

### Impact
Running `docker compose up` as documented in `setup.sh` will start a backend container that fails to connect to the database (connection refused on 5434 inside the network) — a hard deployment blocker for anyone following the documented Docker workflow.

### Recommended Resolution
Use the container's internal port (5432) for the inter-service connection string; keep 5434 only as the host-facing mapping for local tools (psql, IDE).

---

## 4. Hardcoded database credentials committed to version control

**Repository:** project-backend
**Files:** `src/main/resources/application.properties`, `docker-compose.yml`
**Severity:** Critical → **RESOLVED**
**Confidence:** High

### Problem (Historical)
The Postgres password was hardcoded in plaintext in two files, both tracked by git.

### Resolution
Credentials are now fully externalized via environment variables. No secret value is committed to version control.

`application.properties` (current):
```properties
spring.datasource.username=${POSTGRES_USER:postgres}
spring.datasource.password=${POSTGRES_PASSWORD}
```

`docker-compose.yml` (current):
```yaml
# postgres_db service
POSTGRES_USER: ${POSTGRES_USER:-postgres}
POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}

# spring_app service
- SPRING_DATASOURCE_USERNAME=${POSTGRES_USER:-postgres}
- SPRING_DATASOURCE_PASSWORD=${POSTGRES_PASSWORD}
```

A `.env.example` template is committed at `project-backend/.env.example`.
The real `.env` file (containing actual credentials) is listed in `.gitignore` and is never committed.

### Manual step required
Developers must create their own `.env` file before running the stack:
```bash
cd project-backend
cp .env.example .env
# edit .env and set POSTGRES_PASSWORD to a real value
docker compose up -d
```

---

## 5. Core domain (bookings, invoicing) has no API, persistence, or entity

**Repository:** project-backend
**Files:** `booking/model/Booking.java`, `booking/service/BookingValidator.java`, `booking/service/InvoiceCalculator.java`, `Esquema_BD.sql`
**Severity:** Critical (for production readiness of the *stated* product)
**Confidence:** High

### Problem
NovaFacts' stated purpose (per `project-docs/README.md`) is financial/booking management — anticipos, penalidades, facturación, devoluciones. The only code implementing this domain is three plain Java classes (`Booking`, `BookingValidator`, `InvoiceCalculator`) with no `@Entity`, no repository, no controller, and no wiring to Spring at all. `Esquema_BD.sql` defines a full relational model (`reserva`, `factura`, `anticipo`, `penalidad`, `nota_credito`, `devolucion`, `log_transaccion`) that has zero corresponding JPA classes anywhere in `src/main`.

### Evidence
`find src/main/java -path "*booking*"` returns only `model/Booking.java` and `service/{BookingValidator,InvoiceCalculator}.java` — no `controller`, `repository`, or `entity` subpackage exists for `booking`, unlike the `auth` slice. `Esquema_BD.sql` tables have no matching Java classes (verified by inspecting all of `src/main/java`).

### Impact
The product's actual business value is unbuilt. What exists today is an authentication scaffold plus pure unit-tested business-rule logic with no way to invoke it over HTTP or persist its results.

### Recommended Resolution
This isn't a bug to patch — it's the next phase of development. Track it explicitly as scope, not technical debt, when assessing "how close to production" the system is.

---

# High Priority Issues

## 6. Two competing login implementations; one is fully unreachable dead code

**Repository:** project-frontend
**Files:** `src/views/LoginView.vue`, `src/components/LoginForm.vue`, `src/components/SecretDisplay.vue`, `src/views/SecretView.vue`, `src/router/index.js`
**Severity:** High
**Confidence:** High

### Problem
The router mounts `LoginView.vue` at `/`. `LoginView.vue` implements its own self-contained form inline and calls `authenticateUser` from `authService.ts` — it never imports `LoginForm.vue`. `LoginForm.vue` (which calls `authService.js` and emits `login-success`) is imported by nothing (`grep -rn "LoginForm"` across `src` returns only its own declaration). Consequently `SecretDisplay.vue` and the `/secret/:secretPhrase?` route's intended consumer are also unreachable from the live login path, since nothing emits the event chain that would lead there.

### Evidence
```
grep -rn "LoginForm" src   → only LoginForm.vue itself (no importer)
grep -rn "router.push"     → SecretView.vue:27,36 (login flow never calls these)
```

### Impact
A second, fully-built feature (`LoginForm`/`SecretDisplay`/`SecretView`/secret-route) was built and then abandoned in place rather than removed or wired up, doubling the surface area a new contributor has to understand for zero functional benefit.

### Recommended Resolution
Pick one login implementation, delete the other and its now-orphaned dependents (`SecretDisplay.vue`, `SecretView.vue`, the `/secret` route) unless that flow is intentionally being revived.

---

## 7. Router param/query mismatch in the abandoned secret-phrase flow

**Repository:** project-frontend
**Files:** `src/router/index.js`, `src/views/SecretView.vue`
**Severity:** High (if this flow is ever revived) / Low (given it's currently unreachable, see Issue 6)
**Confidence:** High

### Problem
The route is declared with a path parameter: `/secret/:secretPhrase?`. `SecretView.vue` instead reads `route.query.phrase` (a query string parameter), not `route.params.secretPhrase`. Even if the dead code from Issue 6 were wired back up, navigating to `/secret/<value>` would never populate `secretPhrase`, and the component would immediately redirect to `/` via its own guard.

### Evidence
```
router/index.js:14   path: '/secret/:secretPhrase?'
SecretView.vue:23     const phrase = route.query.phrase;
```

### Impact
Confirms this code path was never exercised end-to-end (verified via Issue 6) — the contract between the route definition and the component that reads it doesn't match.

### Recommended Resolution
Decide on params vs. query and make the router and component agree, or remove the flow entirely (preferred, given Issue 6).

---

## 8. CORS is configured inconsistently — the login endpoint isn't covered

**Repository:** project-backend
**Files:** `auth/controller/AuthController.java`, `auth/controller/UserController.java`
**Severity:** High
**Confidence:** High

### Problem
`UserController` has `@CrossOrigin(origins = "http://localhost:5173")`. `AuthController` (the login endpoint, `/api/auth/login`) has no `@CrossOrigin` annotation, and there is no global `CorsConfigurationSource` bean or `WebMvcConfigurer` anywhere in the codebase (verified via repo-wide grep for `CrossOrigin`/`WebMvcConfigurer`/`CorsConfiguration`).

### Evidence
```
UserController.java:12   @CrossOrigin(origins = "http://localhost:5173")
AuthController.java       (no CORS annotation at all)
grep -rn "CrossOrigin|WebMvcConfigurer|CorsConfiguration" → 1 match, in UserController only
```

### Impact
A browser-based frontend calling `POST /api/auth/login` cross-origin (e.g., Vite dev server on 5173 calling the API on 8082, exactly the documented local dev setup) will have the response blocked by the browser's CORS policy, since Spring never sends `Access-Control-Allow-Origin` for that endpoint. This breaks the one functioning feature in the documented local dev workflow.

### Recommended Resolution
Centralize CORS configuration (one `CorsConfigurationSource` bean covering all `/api/**` routes) rather than per-controller annotations, and make it environment-aware rather than hardcoding `localhost:5173`.

---

## 9. Login error messages leak whether a username exists

**Repository:** project-backend
**Files:** `auth/service/UserService.java`
**Relevant Class/Function:** `UserService.login`
**Severity:** High
**Confidence:** High

### Problem
`login` throws a distinct message depending on failure cause: `"Usuario no encontrado"` if the username doesn't exist, vs. `"Contraseña incorrecta"` if it exists but the password is wrong. Both propagate as uncaught `RuntimeException` → generic Spring 500, but with different messages in the response body (no `GlobalExceptionHandler` masks them — see Issue 11).

### Evidence
```
UserService.java:64-65   .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
UserService.java:72-74   if (!passwordMatches) { throw new RuntimeException("Contraseña incorrecta"); }
```

### Impact
Combined with the unauthenticated `GET /api/users` (Issue 1), this is a low-effort user enumeration vector even without that endpoint — attackers can directly probe `/api/auth/login` to confirm valid usernames.

### Recommended Resolution
Return a single generic "invalid credentials" message for both failure cases.

---

## 10. Duplicate, dead configuration classes outside the Spring component-scan boundary

**Repository:** project-backend
**Files:** `com.novafacts.SecurityConfig`, `com.novafacts.config.PasswordConfig`, vs. `com.novafacts.backend.config.SecurityConfig`
**Severity:** High (architecture/maintainability — easy to misread as active config)
**Confidence:** High

### Problem
`@SpringBootApplication` lives in `com.novafacts.backend` (`BackendApplication.java`), so Spring's default component scan only covers `com.novafacts.backend` and its sub-packages. `com.novafacts.SecurityConfig` and `com.novafacts.config.PasswordConfig` live in `com.novafacts` and `com.novafacts.config` respectively — siblings/parents of the scanned package, not sub-packages — so neither is ever registered as a bean. Only `com.novafacts.backend.config.SecurityConfig` (with its own `PasswordEncoder` bean) is actually active.

### Evidence
```
BackendApplication.java:1   package com.novafacts.backend;
SecurityConfig.java         package com.novafacts;                 (NOT a sub-package — dead)
PasswordConfig.java         package com.novafacts.config;          (NOT a sub-package — dead)
config/SecurityConfig.java  package com.novafacts.backend.config;  (the only one that's live)
```

### Impact
A maintainer reading `com.novafacts.SecurityConfig` could reasonably believe it's the active security policy (it has no annotation marking it inactive) and waste time debugging or duplicating logic there. This is exactly the gap the project's own `skill_back.md` flags under "AR3: Config duplicada eliminada — No".

### Recommended Resolution
Delete the two dead classes; keep a single `SecurityConfig` in the scanned package tree.

---

## 11. No global exception handling — all failures surface as generic 500s

**Repository:** project-backend
**Files:** `auth/service/UserService.java`, `auth/controller/AuthController.java`, `auth/controller/UserController.java`
**Severity:** High
**Confidence:** High

### Problem
There is no `@ControllerAdvice`/`@ExceptionHandler` anywhere in the codebase (verified by repo-wide search). `UserService` throws raw `RuntimeException` for both "user not found" and "wrong password"; Spring's default behavior turns any uncaught exception into an HTTP 500. There is no distinction between client error (400/401/404) and server error.

### Evidence
`grep -rn "ExceptionHandler" src/main/java` → no matches. `UserService.java:65,73` throw plain `RuntimeException`.

### Impact
Clients (and the frontend) cannot distinguish "bad credentials" from "server crashed" by status code — only by parsing the message body, which is also inconsistent in shape (`LoginResponse` has no error field; a thrown exception produces Spring's default error JSON instead).

### Recommended Resolution
Add a `@ControllerAdvice` mapping domain exceptions to appropriate HTTP status codes (401 for bad credentials, 404/409 for missing/conflicting resources, etc.).

---

## 12. No request validation anywhere

**Repository:** project-backend
**Files:** `auth/dto/CreateUserRequest.java`, `auth/dto/LoginRequest.java`, `auth/controller/UserController.java`, `auth/controller/AuthController.java`
**Severity:** High
**Confidence:** High

### Problem
No DTO has any `jakarta.validation` annotation (`@NotBlank`, `@Email`, `@Size`, etc.), and no controller method has `@Valid`. `pom.xml` doesn't even include `spring-boot-starter-validation`. `CreateUserRequest`/`LoginRequest` accept any string, including blank/null usernames and passwords.

### Evidence
`CreateUserRequest.java`, `LoginRequest.java` — plain getters/setters, no annotations. `pom.xml` dependency list (lines 32-65) has no validation starter.

### Impact
A `POST /api/users` with an empty username/password will succeed and create a user with a blank credential pair (limited only by the DB's `nullable = false`, which still permits empty strings).

### Recommended Resolution
Add `spring-boot-starter-validation`, annotate DTOs, and add `@Valid` to controller method parameters.

---

# Medium Priority Issues

## 13. Money modeled as `double` instead of a fixed-point type

**Repository:** project-backend
**Files:** `booking/model/Booking.java`, `booking/service/InvoiceCalculator.java`
**Severity:** Medium
**Confidence:** High

### Problem
`pricePerNight` and every derived monetary calculation (`calculateSubtotal`, `calculateTax`, `calculateDiscount`, `calculateTotal`) use `double`. `Esquema_BD.sql` correctly models money as `decimal(12,2)` for the equivalent fields (e.g., `factura.total`, `anticipo.monto`), so the intended schema and the actual calculation logic disagree on representation.

### Evidence
```
Booking.java:10        private final double pricePerNight;
InvoiceCalculator.java:7-9   TAX_RATE = 0.19; ... (all double arithmetic)
Esquema_BD.sql:65,86,97-99,113   decimal(12,2) NOT NULL  (the schema's equivalent fields)
```

### Impact
Floating-point binary representation introduces rounding error in financial calculations (tax, discounts, totals) — a correctness risk for a system whose entire purpose is financial accuracy/auditability.

### Recommended Resolution
Switch monetary fields and arithmetic to `BigDecimal` to match the schema's `decimal` columns.

---

## 14. `getUsers()` has no pagination and is unauthenticated

**Repository:** project-backend
**Files:** `auth/service/UserService.java`, `auth/controller/UserController.java`
**Severity:** Medium (compounds Issue 1)
**Confidence:** High

### Problem
`getUsers()` calls `userRepository.findAll()` and maps the entire result set with no `Pageable`/limit.

### Evidence
```
UserService.java:51-60   userRepository.findAll().stream().map(...).toList();
```

### Impact
Combined with Issue 1 (no auth), this is both a security exposure and, independently of auth, a scalability problem once the `users` table grows.

### Recommended Resolution
Add pagination (`Pageable`) once the endpoint is also properly authorized.

---

## 15. No Spring Profiles — single `application.properties` for all environments

**Repository:** project-backend
**Files:** `src/main/resources/application.properties`
**Severity:** Medium
**Confidence:** High

### Problem
There is exactly one properties file; no `application-dev.properties`/`application-prod.properties`/profile-based overrides exist. `spring.jpa.show-sql=true` and full Hibernate SQL/binder `DEBUG`/`TRACE` logging are unconditionally enabled.

### Evidence
```
application.properties:7        spring.jpa.show-sql=true
application.properties:10-11    logging.level.org.hibernate.SQL=DEBUG
                                 logging.level.org.hibernate.type.descriptor.sql.BasicBinder=TRACE
```

### Impact
If this configuration ships as-is, production logs would include full SQL statements and bound parameter values (which may include passwords/PII) at TRACE level — both a performance cost and a sensitive-data-exposure risk via logs.

### Recommended Resolution
Introduce Spring Profiles; keep verbose SQL/binder logging dev-only.

---

## 16. `spring.jpa.hibernate.ddl-auto=update` — no migration tool

**Repository:** project-backend
**Files:** `application.properties`, `docker-compose.yml`
**Severity:** Medium
**Confidence:** High

### Problem
Schema is generated/evolved by Hibernate (`ddl-auto=update`) rather than versioned migrations (no Flyway/Liquibase dependency in `pom.xml`). `Esquema_BD.sql` exists as a hand-written reference schema but is not executed anywhere in the app or compose stack (verified: no init-script mount in `docker-compose.yml`, no migration tool to run it).

### Evidence
`docker-compose.yml` postgres service has no `volumes`/`docker-entrypoint-initdb.d` mount of `Esquema_BD.sql`; `pom.xml` has no Flyway/Liquibase dependency.

### Impact
Schema drift between environments is silent and uncontrolled; `Esquema_BD.sql` is effectively documentation with no enforcement, and (per Issue 5) doesn't even match the one entity that does exist.

### Recommended Resolution
Adopt Flyway or Liquibase with versioned migration scripts derived from `Esquema_BD.sql` before the `booking` domain is implemented against real tables.

---

## 17. Dockerfile port (8081) doesn't match the application's actual port (8082)

**Repository:** project-backend
**Files:** `Dockerfile`, `application.properties`, `docker-compose.yml`
**Severity:** Medium
**Confidence:** High

### Problem
`Dockerfile` declares `EXPOSE 8081`. The app actually listens on 8082 (`server.port=8082` in `application.properties`, not overridden by any env var in `docker-compose.yml`), and `docker-compose.yml` maps `"8082:8082"`. `EXPOSE` is non-binding documentation in Docker, so the container still works via the compose port mapping — but the Dockerfile is internally inconsistent with the rest of the stack.

### Evidence
```
Dockerfile:4                EXPOSE 8081
application.properties:13   server.port=8082
docker-compose.yml:28       ports: ["8082:8082"]
```

### Impact
Misleading for anyone running the image standalone (`docker run -p 8081:8081 ...` would silently fail to expose the right port); a deployment-config consistency smell.

### Recommended Resolution
Align `EXPOSE` with `server.port`.

---

## 18. Frontend has no environment-variable layer; API URLs are hardcoded twice, inconsistently

**Repository:** project-frontend
**Files:** `src/services/authService.js`, `src/services/authService.ts`
**Severity:** Medium
**Confidence:** High

### Problem
`authService.js` hardcodes an absolute base URL (`http://localhost:8082/api`). `authService.ts` instead posts to a relative path (`/api/auth/login`) with no `baseURL` configured on its Axios call, relying on whatever host the app is served from. Neither uses Vite's `import.meta.env` mechanism (no `.env` file exists in the project at all, despite `vite.config.js` being present and capable of supporting it).

### Evidence
```
authService.js:4     baseURL: 'http://localhost:8082/api'
authService.ts:8      axios.post('/api/auth/login', ...)   // no baseURL set anywhere in this file
```

### Impact
The two services would behave differently in any environment other than "Vite dev proxy not configured, talking directly to a backend on localhost:8082" — `authService.ts`'s relative URL would hit whatever origin serves the frontend (likely 404 in production unless a reverse proxy is set up to forward `/api`), while `authService.js` would always try `localhost:8082` even in production.

### Recommended Resolution
Introduce `.env`/`.env.production` with `VITE_API_BASE_URL`, and use it consistently — applicable to whichever single service module survives the Issue 6 cleanup.

---

## 19. No router guards — route protection lives inside a component

**Repository:** project-frontend
**Files:** `src/router/index.js`, `src/views/SecretView.vue`
**Severity:** Medium
**Confidence:** High

### Problem
There is no `beforeEach`/`meta.requiresAuth` guard in `router/index.js`. The only access control for the "protected" `/secret` route is a check inside `SecretView.vue`'s `onMounted` that redirects to `/` if a query param is missing — not actual authentication state. This is also called out in `skill_front.md` (S1).

### Evidence
```
router/index.js     no beforeEach, no meta fields
SecretView.vue:21-31   onMounted(() => { if (!phrase) router.push('/'); ... })
```

### Impact
Moot today given Issue 6 (the route is unreachable from the live flow), but represents an anti-pattern that would resurface the moment any real protected route is added.

### Recommended Resolution
Implement router-level navigation guards keyed off actual auth state once a real session mechanism exists (Issue 2).

---

# Low Priority Issues

## 20. Stale `estructura_repo.txt` documentation snapshots

**Repository:** project-frontend
**Files:** `project-frontend/estructura_repo.txt`
**Severity:** Low
**Confidence:** High

### Problem
The documented tree omits `src/services/authService.ts`, `src/types/auth.ts`, and the `assets/background.jpg`/`assets/logo.png` files that currently exist in `src/`.

### Evidence
Diff between `estructura_repo.txt` contents and current `find src -type f` output (performed during this audit) — the TS migration files and brand assets are absent from the documented tree.

### Impact
Onboarding friction; the documented structure no longer matches reality.

### Recommended Resolution
Regenerate or remove these structure snapshots; consider not committing generated tree dumps at all.

---

## 21. Empty placeholder packages (`common/`, `security/`)

**Repository:** project-backend
**Files:** `com.novafacts.backend.common`, `com.novafacts.backend.security`
**Severity:** Low
**Confidence:** High

### Problem
Both package directories exist with zero files.

### Evidence
`find ... common security` returns only the empty directory paths.

### Impact
Negligible on its own; notable mainly as corroborating evidence that a `security` package (likely intended for the JWT work referenced in Issue 2) was scaffolded and never built.

### Recommended Resolution
Remove if not imminently used, or use them as the actual destination for the auth/security work in the remediation roadmap.

---

## 22. Orphaned root-level `package-lock.json` with no `package.json`

**Repository:** workspace root (`NovaFacts/`)
**Files:** `package-lock.json`
**Severity:** Low
**Confidence:** High

### Problem
An empty lockfile (`"packages": {}`) exists at the workspace root with no corresponding `package.json` anywhere in that directory.

### Evidence
`cat package-lock.json` → `{"name":"NovaFacts","lockfileVersion":3,"requires":true,"packages":{}}`; `find -maxdepth 1 -iname package.json` → no result.

### Impact
Clutter; likely the result of an accidental `npm install` run at the wrong directory level.

### Recommended Resolution
Delete it.

---

## 23. Stray duplicate "estructura repo" files and an empty leftover `backend/` directory

**Repository:** workspace root / project-backend
**Files:** `NovaFacts/backend/` (only `.idea`/`.gitignore`), `project-backend/estructura repo.txt`
**Severity:** Low
**Confidence:** High

### Problem
`NovaFacts/backend/` contains no source, only IDE metadata and a `.gitignore` — a leftover from an earlier scaffold, distinct from the real `project-backend/`.

### Evidence
`find backend -type f` → only `.gitignore`, `.idea/workspace.xml`, `.idea/misc.xml`.

### Impact
Confusing for anyone navigating the workspace ("which backend is real?").

### Recommended Resolution
Delete the stray `backend/` directory.

---

## 24. `HelloWorld.vue`, `TestController`, `HelloController` — scaffold/test code left in `main`

**Repository:** project-frontend / project-backend
**Files:** `src/components/HelloWorld.vue`; `com.novafacts.backend.TestController`, `com.novafacts.backend.controller.HelloController`
**Severity:** Low
**Confidence:** High

### Problem
`HelloWorld.vue` is the unmodified Vite/Vue scaffold component, imported nowhere (verified by grep). `TestController` (`GET /api/test`) and `HelloController` (`GET /api/hello`, `GET /api/health`) are framework-verification endpoints left in production source, also called out by the project's own `skill_back.md` (AR4).

### Evidence
`grep -rn "HelloWorld" src` → only its own file. `TestController.java`, `HelloController.java` full contents reviewed — trivial hardcoded responses, no business value.

### Impact
Minor public-surface clutter; `/api/health` is arguably useful but should be intentional, not leftover.

### Recommended Resolution
Delete `HelloWorld.vue` and `TestController`; keep `/api/health` only if intentionally adopted as a real health-check endpoint (ideally via Spring Boot Actuator instead of a hand-rolled one).

---

## 25. Language-convention inconsistency between the two parallel login implementations

**Repository:** project-frontend
**Files:** `src/components/LoginForm.vue`, `src/views/LoginView.vue`
**Severity:** Low
**Confidence:** High

### Problem
The project's evident convention is English for code identifiers, Spanish for user-facing text. `LoginForm.vue` uses Spanish identifiers in code (`correo`, and a payload field `result.secret_phrase`), while `LoginView.vue` (built later, per the TS migration) uses English identifiers (`userEmail`, `userPassword`, `isSubmitting`) for the same concept.

### Evidence
```
LoginForm.vue:44    const correo = ref('');
LoginView.vue:88    const userEmail = ref<string>('');
```

### Impact
Minor; mostly relevant as further evidence the two implementations were written independently rather than one evolving from the other.

### Recommended Resolution
Moot once Issue 6 is resolved (one implementation removed); otherwise, standardize identifier language.

---

# Technical Debt & Cleanup

| Item | Location | Type |
|---|---|---|
| `com.novafacts.SecurityConfig`, `com.novafacts.config.PasswordConfig` | project-backend | Dead code (outside component scan) |
| `TestController`, `HelloController` | project-backend | Scaffold/test leftovers in `main` |
| Empty `common/`, `security/` packages | project-backend | Unfulfilled scaffolding |
| `LoginForm.vue`, `SecretDisplay.vue`, `SecretView.vue`, `/secret` route | project-frontend | Orphaned/unreachable feature |
| `HelloWorld.vue` | project-frontend | Unmodified framework scaffold |
| `services/authService.js` **or** `services/authService.ts` (one of the two) | project-frontend | Duplicate implementation of the same concern |
| `NovaFacts/backend/` (root) | workspace | Stale leftover directory |
| `NovaFacts/package-lock.json` (root) | workspace | Orphaned lockfile, no `package.json` |
| `estructura_repo.txt` files (frontend, possibly others) | project-frontend | Stale generated documentation |
| `Booking`/`BookingValidator`/`InvoiceCalculator` using `double` for money | project-backend | Refactor candidate once persisted |
| `application.properties` single-environment config | project-backend | Needs profile split before any real deployment |

---

# Cross-Repository Findings

- **API contract mismatch:** `project-frontend`'s `authService.ts` reads `response.data.token`; `project-backend`'s `LoginResponse` never includes a `token` field (Issue 2). Neither repo's `skill_*.md` documentation flags this, despite both documents being dated after the relevant code existed.
- **CORS origin hardcoding mismatch:** `UserController.java` hardcodes `http://localhost:5173` (Vite's default port), but nothing in `project-frontend` enforces that port — `vite.config.js` has no `server.port` override, so this is fragile by convention only, and doesn't cover `AuthController` at all (Issue 8).
- **Schema/code drift across repos:** `Esquema_BD.sql` (project-backend) models `usuario` with `nombre`, `email`, `rol_id`, `activo` — the actual `User` JPA entity has only `username`/`password`. The richer schema is reference documentation with no enforcement (Issues 5, 16).
- **Documentation vs. code drift:** `project-docs/Documentation/project/skill_back.md` and `skill_front.md` are dated self-assessments (June 2025) that either no longer hold (S4's "externalized config" claim, contradicted by Issue 4) or were already accurate self-criticism that has not been acted on since (duplicate `SecurityConfig`, missing router guards, missing `GlobalExceptionHandler` — all still present as of this audit).
- **Deployment inconsistency:** `setup.sh` (project-backend) assumes a specific relative path to `project-frontend/frontend` (`cd .. && cd project-frontend && cd frontend`) that only works if both repos are checked out as siblings under the same parent directory — true in this workspace, but not guaranteed by anything in either repo (no documented workspace-level setup instructions in either README).
- **Port convention:** Backend consistently uses 8082 (properties, compose, frontend services) except the Dockerfile (8081, Issue 17) — otherwise consistent across repos.

---

# Remediation Roadmap

## Phase 1 — Critical Fixes
*Goal: make the one existing feature (login) actually work and stop the most severe security exposures.*

1. **Fix the Docker network DB connection string** (Issue 3) — no other Docker-based work is verifiable until this is correct.
2. **Rotate and externalize the hardcoded DB credentials** (Issue 4) — do this before any other infra change touches `docker-compose.yml`, to avoid re-committing the same secret.
3. **Decide on and implement a real session/auth mechanism** (Issue 2) — this is the prerequisite for every authorization fix below; nothing in Phase 2 makes sense until there's something to authorize against.
4. **Lock down `GET /api/users` and `DELETE /api/users/{id}`** (Issue 1) — depends on #3 being at least partially in place (need a concept of "authenticated request" to gate on).
5. **Resolve the duplicate login implementations** (Issue 6) by choosing one and deleting the other, *before* wiring real auth into it — otherwise the auth work gets built twice.

## Phase 2 — Security & Architecture Stabilization
*Goal: close remaining gaps now that real auth exists.*

1. Fix login error-message user enumeration (Issue 9) — quick, do alongside #3 above if not already folded in.
2. Centralize and correct CORS configuration (Issue 8) — depends on knowing the final frontend deployment origin(s), so sequence after any hosting decisions.
3. Add global exception handling (Issue 11) and request validation (Issue 12) — these are independent of auth and can proceed in parallel with security work.
4. Remove dead config classes (Issue 10) — low-risk, can happen anytime, but do it before onboarding new contributors to avoid confusion.
5. Delete the orphaned secret-phrase flow's remnants (Issue 7) or resolve the param/query mismatch if the flow is revived instead.

## Phase 3 — Configuration & Technical Debt
*Goal: make the system safe to actually deploy somewhere real.*

1. Introduce Spring Profiles and disable verbose SQL/binder logging outside dev (Issue 15).
2. Adopt a migration tool (Flyway/Liquibase) and decide whether `Esquema_BD.sql` becomes the actual migration baseline or is retired (Issue 16) — sequence this *before* building the `booking` persistence layer (Phase 4), since that work should be built against managed migrations from the start, not `ddl-auto=update`.
3. Fix the Dockerfile/port inconsistency (Issue 17).
4. Add a frontend `.env` layer and consolidate the two API-base-URL strategies into one (Issue 18) — depends on Phase 1's service-deduplication (Issue 6) being done first.
5. General cleanup pass: dead scaffold files (Issues 20–24), language-convention pass (Issue 25).

## Phase 4 — Long-Term Improvements
1. Build the `booking`/invoicing domain into the application proper: JPA entities matching (a finalized version of) `Esquema_BD.sql`, repositories, services, controllers — the actual product scope (Issue 5).
2. Convert monetary fields/arithmetic from `double` to `BigDecimal` as part of that persistence work (Issue 13).
3. Add pagination to list endpoints as data volume grows (Issue 14).
4. Implement router-level auth guards once real session state exists (Issue 19).
5. Re-establish documentation as a living artifact: update `estructura_repo.txt`-style snapshots (or drop them in favor of relying on the repo itself), give `project-frontend/README.md` actual content, and refresh `skill_back.md`/`skill_front.md` against the state after Phases 1–3 land.
