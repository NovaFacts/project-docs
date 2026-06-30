# NovaFacts — Comprehensive Technical Audit Report

**Date:** 2026-06-27
**Scope:** Full codebase — Spring Boot 3.5 backend + Vue 3 frontend
**Repositories:** `NovaFacts/project-backend`, `NovaFacts/project-frontend`
**Auditor:** Claude Sonnet 4.6

---

## Table of Contents

1. [Project Context](#1-project-context)
2. [Backend Audit](#2-backend-audit)
   - 2.1 [Architecture & Package Structure](#21-architecture--package-structure)
   - 2.2 [Security Configuration](#22-security-configuration)
   - 2.3 [JWT Implementation](#23-jwt-implementation)
   - 2.4 [Authentication & Authorization](#24-authentication--authorization)
   - 2.5 [REST API Design](#25-rest-api-design)
   - 2.6 [Controllers](#26-controllers)
   - 2.7 [Services](#27-services)
   - 2.8 [Repositories](#28-repositories)
   - 2.9 [Entities](#29-entities)
   - 2.10 [DTOs & Validation](#210-dtos--validation)
   - 2.11 [Exception Handling](#211-exception-handling)
   - 2.12 [Database Configuration](#212-database-configuration)
   - 2.13 [Test Coverage](#213-test-coverage)
3. [Frontend Audit](#3-frontend-audit)
   - 3.1 [Architecture & Component Structure](#31-architecture--component-structure)
   - 3.2 [Routing & Navigation Guards](#32-routing--navigation-guards)
   - 3.3 [Authentication Flow](#33-authentication-flow)
   - 3.4 [API Services & Axios](#34-api-services--axios)
   - 3.5 [TypeScript Usage](#35-typescript-usage)
   - 3.6 [Component Analysis](#36-component-analysis)
   - 3.7 [State Management](#37-state-management)
   - 3.8 [Form Validation](#38-form-validation)
   - 3.9 [CSS Organization](#39-css-organization)
   - 3.10 [Accessibility](#310-accessibility)
4. [Infrastructure Audit](#4-infrastructure-audit)
   - 4.1 [Docker Configuration](#41-docker-configuration)
   - 4.2 [Environment Configuration](#42-environment-configuration)
   - 4.3 [Build Configuration](#43-build-configuration)
5. [Dead Code & Unused Assets](#5-dead-code--unused-assets)
6. [What Is Already Well Implemented](#6-what-is-already-well-implemented)
7. [Overall Project Assessment](#7-overall-project-assessment)
8. [Recommended Next Priorities](#8-recommended-next-priorities)

---

## 1. Project Context

NovaFacts is a financial management system for short-term rental bookings, built as a class project for *Ingeniería de Software 1* at Universidad Nacional de Colombia. The current implementation covers:

- **Implemented:** JWT authentication (login, registration, logout), Guest CRUD module
- **Pending:** Bookings, invoicing, penalties, advances, refunds, role-based access

The project uses a feature-package architecture in Spring Boot 3.5 with Java 21, PostgreSQL, and a Vue 3 SPA with TypeScript. The audit evaluates the project against production software engineering standards while acknowledging the academic context.

---

## 2. Backend Audit

### 2.1 Architecture & Package Structure

#### ✅ Feature-package layout is clean and scalable

The backend is organized by feature domain, not by technical layer:

```
com.novafacts.backend
├── auth/        (controller, dto, entity, filter, jwt, repository, service)
├── booking/     (model, service)
├── common/      (GlobalExceptionHandler)
├── config/      (SecurityConfig)
└── guest/       (controller, dto, entity, repository, service)
```

This structure allows each feature to be understood and modified independently. It is appropriate for the project size and a significant improvement over the typical beginner pattern of separating by layer (all controllers in one package, all services in another).

---

#### MEDIUM | Architecture | `booking/` package has no persistence layer, no HTTP endpoint, and no Spring wiring

**Files:** `booking/model/Booking.java`, `booking/service/BookingValidator.java`, `booking/service/InvoiceCalculator.java`

**Issue:** These three classes are plain Java POJOs. Neither `BookingValidator` nor `InvoiceCalculator` is annotated with `@Service` or any Spring stereotype. There is no JPA entity, no repository, no controller, and no Spring bean that references them. They exist in the deployed jar but are never instantiated by the framework at runtime.

**Impact:**
- Any developer reading the codebase cannot determine whether this is the active domain model, a future prototype, or dead scaffolding. It creates ambiguity about the project's actual domain model.
- The booking logic calculates totals and applies discounts on in-memory `Booking` objects that have no persistence connection — when the real booking persistence is implemented, these classes may need significant refactoring.
- **Technical debt:** the business rules encoded in `BookingValidator` (max 4 guests, max 30 nights) and `InvoiceCalculator` (19% tax, 10% long-stay discount at ≥7 nights) are disconnected from the rest of the system and will be easy to miss or duplicate when the booking REST layer is added.

**Can produce:** maintainability problems, technical debt, future duplication

**Recommended fix:** Add a `@Service` annotation to `BookingValidator` and `InvoiceCalculator` to signal that they are intended Spring beans even without a persistence layer, and add a `package-info.java` comment documenting their pending status. Alternatively, move them to a separate Maven module if they are truly independent domain objects.

---

### 2.2 Security Configuration

**File:** `config/SecurityConfig.java`

#### CRITICAL | Security | CORS is hardcoded to `http://localhost:5173`

```java
// SecurityConfig.java:49
configuration.setAllowedOrigins(List.of("http://localhost:5173"));
```

**Issue:** The allowed CORS origin is a compile-time constant. Any deployment where the frontend does not run on exactly `http://localhost:5173` — a staging server, a CI preview URL, a professor's machine, a cloud deployment — will cause the browser to reject all API calls with a preflight failure. The backend returns `403 Forbidden` on OPTIONS requests from any other origin.

**Impact:**
- The application is non-functional in any environment except the original developer's local machine.
- This is the single highest-risk item for a live academic demonstration: if the professor runs the frontend on port 3000 or opens a deployed URL, nothing works.

**Can produce:** bugs (total application failure in non-local environments), security vulnerability if carelessly expanded to `*`

**Recommended fix:**

```java
List<String> origins = List.of(
    System.getenv().getOrDefault("ALLOWED_ORIGIN", "http://localhost:5173")
);
configuration.setAllowedOrigins(origins);
```

---

#### MEDIUM | Security | SQL parameter binding is logged at TRACE level in all environments

**File:** `src/main/resources/application.properties:9-13`

```properties
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true
logging.level.org.hibernate.SQL=DEBUG
logging.level.org.hibernate.type.descriptor.sql.BasicBinder=TRACE
```

**Issue:** `BasicBinder` at `TRACE` emits the literal value of every JDBC bind parameter to standard output. For login and user creation, this includes user email addresses. There is no profile separation — these settings are active in the Docker container and in every environment.

**Concrete log output example (from a `POST /api/auth/login`):**
```
binding parameter [1] as [VARCHAR] - [usuario@correo.com]
```

**Impact:** User email addresses are written to application logs. Any log aggregation system (Splunk, ELK, CloudWatch) stores this data, constituting a personal information leak.

**Can produce:** security vulnerability (data exposure in logs)

**Recommended fix:** Remove all four lines from `application.properties`. If SQL debugging is needed in development, move them to `src/main/resources/application-dev.properties` and activate with `--spring.profiles.active=dev`.

---

#### LOW | Security | No Spring profiles defined

**File:** `src/main/resources/application.properties`

**Issue:** There is a single `application.properties` file covering all environments. Debug logging, `ddl-auto=update`, `show-sql=true`, and the database URL are all hardcoded in the same file that is used in production via Docker Compose.

**Impact:** Properties intended only for development (SQL logging, DDL management) are active in the Docker container.

**Can produce:** security vulnerability (log exposure), maintainability problems

**Recommended fix:** Create `application-dev.properties` and `application-prod.properties`. Move debug logging and `ddl-auto=update` to the dev profile. Activate profiles via environment variable in `docker-compose.yml`: `SPRING_PROFILES_ACTIVE=prod`.

---

### 2.3 JWT Implementation

**File:** `auth/jwt/JwtService.java`

#### ✅ Correct JWT library and signing approach

JJWT 0.12.6 (the current stable version) is used. Keys are derived with `Keys.hmacShaKeyFor()` from Base64-decoded bytes, which enforces minimum key length. The filter correctly extracts and validates tokens on each request. Token expiration is checked. The secret is externalized to an environment variable.

---

#### LOW | Security | `extractClaim` is declared `public`

```java
// JwtService.java:50
public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
```

**Issue:** `extractClaim` is an internal parsing helper. Declaring it `public` exposes JWT parsing internals to the entire application. Any class in the project can call `jwtService.extractClaim(token, ...)`, bypassing intended encapsulation.

**Can produce:** maintainability problems, unexpected coupling

**Recommended fix:** Change visibility to `private`. `extractUsername` and `isTokenValid` (the intended public API) already cover all caller needs.

---

#### LOW | Security | `isTokenValid` calls `extractUsername` but the filter already extracted it

```java
// JwtAuthenticationFilter.java:44-48
final String username = jwtService.extractUsername(token);   // parses JWT once
if (username != null && ...) {
    UserDetails userDetails = ...;
    if (jwtService.isTokenValid(token, userDetails)) {        // parses JWT again internally
```

```java
// JwtService.java:37-39
public boolean isTokenValid(String token, UserDetails userDetails) {
    final String username = extractUsername(token);   // third parse of same token
    return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
}
```

**Issue:** The JWT is parsed three times per authenticated request: once for `extractUsername`, once inside `isTokenValid` for the username comparison, and once inside `isTokenExpired` for the expiration check. Each parse involves Base64 decoding and HMAC verification.

**Impact:** Minor CPU overhead on every protected API call.

**Can produce:** performance problems (negligible at low scale, relevant at high traffic)

**Recommended fix:** Pass the already-extracted `username` into an overloaded `isTokenValid(String token, String username, UserDetails userDetails)` to skip re-parsing.

---

#### LOW | Security | No JWT issuer, audience, or `nbf` claims validated

**File:** `auth/jwt/JwtService.java`

**Issue:** Tokens are signed with HMAC-SHA but contain only `subject`, `issuedAt`, and `expiration`. Standard JWT validation should also verify `iss` (issuer) and `aud` (audience) to reject tokens generated by other services that happen to share the same secret.

**Can produce:** security vulnerability (token confusion in multi-service environments)

**Recommended fix:** Add `.issuer("novafacts")` in `generateToken` and `.requireIssuer("novafacts")` in the parser.

---

### 2.4 Authentication & Authorization

#### MEDIUM | Authorization | No role-based access control exists

**Files:** `config/SecurityConfig.java`, `auth/controller/UserController.java`

**Issue:** `SecurityConfig` enforces `anyRequest().authenticated()`, meaning all authenticated users have identical access to every endpoint. Currently this includes:

- `GET /api/users` — list all registered accounts
- `POST /api/users` — create new users (any authenticated user can register additional accounts)
- `DELETE /api/users/{id}` — delete any user, including other users' accounts

Any guest user can call `POST /api/users` to create additional accounts, and `DELETE /api/users/{id}` to remove any account including their own or an administrator's. This is a direct privilege escalation path once financial data is added to the system.

**Can produce:** security vulnerability (privilege escalation), bugs (unintended data modification)

**Recommended fix:** Implement `@PreAuthorize("hasRole('ADMIN')")` on `UserController` methods. The `rolId` field already exists in the `User` entity and the `usuario` schema — wire it into the `UserDetails` authorities in `UserDetailsServiceImpl`.

---

#### MEDIUM | Authorization | `GET /api/users` exposes all registered email addresses to any authenticated user

**File:** `auth/controller/UserController.java:31-33`

```java
@GetMapping
public List<UserResponse> getUsers() {
    return userService.getUsers();
}
```

**Issue:** `UserResponse` contains `{ id, email }`. Any valid JWT holder can enumerate every account in the system with a single GET request. In a financial management platform, this is an information disclosure vulnerability.

**Can produce:** security vulnerability (account enumeration)

**Recommended fix:** Restrict this endpoint to admin role via `@PreAuthorize`. Add a comment documenting the restriction intent in the interim.

---

### 2.5 REST API Design

#### LOW | API Design | No API versioning

**Files:** `AuthController.java`, `UserController.java`, `GuestController.java`

**Issue:** All endpoints are exposed under `/api/...` with no version prefix (`/api/v1/...`). Any future breaking change to a contract requires modifying all clients simultaneously. There is no deprecation path.

**Can produce:** maintainability problems, technical debt

**Recommended fix:** Add `/v1/` to the base paths. Since the project is early-stage, the cost of this change is low now and grows as the client surface expands.

---

#### LOW | API Design | `LoginResponse` includes a redundant `message` field

**File:** `auth/dto/LoginResponse.java`

```java
public LoginResponse(String token, String message) {  // message = "Login exitoso"
```

**Issue:** The `message` field is populated with the static string `"Login exitoso"`. The frontend `authService.ts` reads only `response.data.token` and never accesses `message`. The field adds noise to the response contract and creates a false impression of a user-facing message system.

**Can produce:** unnecessary complexity, technical debt

**Recommended fix:** Remove `message` from `LoginResponse`. If status context is needed, rely on HTTP status codes.

---

#### LOW | API Design | `AuthController.login` returns `LoginResponse` without `ResponseEntity`

**File:** `auth/controller/AuthController.java:19-24`

```java
@PostMapping("/login")
public LoginResponse login(@Valid @RequestBody LoginRequest request) {
    return userService.login(request);
}
```

**Issue:** The response is implicitly `200 OK`. A successful login should return `200` (it does), but the pattern is inconsistent with `UserController.createUser()` which explicitly wraps in `ResponseEntity.status(HttpStatus.CREATED)`. The inconsistency makes the pattern ambiguous for future contributors.

**Can produce:** maintainability problems, inconsistent behavior

**Recommended fix:** Standardize — either always return raw objects (simpler) or always use `ResponseEntity` (more explicit). Picking one and applying it consistently is the goal.

---

### 2.6 Controllers

#### ✅ Controllers are thin — all business logic is in services

Every controller method is a direct delegation to the corresponding service. No conditional logic, no data transformation, no direct repository access appears in any controller. This is correct application of the single-responsibility principle.

---

#### MEDIUM | Correctness | `UserController.deleteUser()` returns `204 No Content` for non-existent IDs

**File:** `auth/service/UserService.java:36-38`, `auth/controller/UserController.java:36-38`

```java
// UserService.java
public void deleteUser(Long id) {
    userRepository.deleteById(id);
}
```

**Issue:** In Spring Data JPA 3.x (used by Spring Boot 3.5), `CrudRepository.deleteById()` is a **no-op** when the entity does not exist — it no longer throws `EmptyResultDataAccessException`. Consequently, `DELETE /api/users/99999` where no user 99999 exists returns `204 No Content`, the same response as a successful delete. The operation is indistinguishable from success.

**Contrast with `GuestService.delete()`:**
```java
public void delete(Long id) {
    guestRepository.delete(getOrThrow(id));  // throws 404 if not found
}
```

**Impact:** REST clients and test suites cannot detect that a delete targeted a non-existent resource. The inconsistency between `UserService` and `GuestService` introduces a confusing asymmetry in the API.

**Can produce:** bugs (silent failure of delete operations), inconsistent behavior

**Recommended fix:**
```java
public void deleteUser(Long id) {
    User user = userRepository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado"));
    userRepository.deleteById(id);
}
```

---

### 2.7 Services

#### ✅ Business logic is correctly contained in service classes

All validation, conflict detection, and entity mapping lives in service classes. Controllers call services; services call repositories. The layered boundary is maintained throughout.

---

#### MEDIUM | Data Quality | `UserService.createUser()` stores the email address in the `nombre` column

**File:** `auth/service/UserService.java:44`

```java
user.setNombre(request.getEmail());   // "usuario@correo.com" becomes the user's name
```

**Issue:** `CreateUserRequest` has no `nombre` field. Rather than leaving it null or using a placeholder, the code silently copies the email to the name field. Every user in the system has their email address as their display name. Any UI element that displays the user's name will show the email instead.

**Can produce:** bugs (incorrect data displayed in UI), technical debt

**Recommended fix:** Remove `setNombre()` for now (allow null), or add a `name` field to `CreateUserRequest`. Using the email as a name substitute is a hidden semantic contract that will break when real names are needed.

---

#### MEDIUM | Correctness | Service `create()` and `update()` methods are not `@Transactional`

**Files:** `guest/service/GuestService.java`, `auth/service/UserService.java`

**Issue:** `GuestService.create()` performs an `existsByDocumentNumber()` check followed by `save()`. These are two separate database operations outside any explicit transaction boundary. Under concurrent requests:

1. Request A checks: `existsByDocumentNumber("123")` → false
2. Request B checks: `existsByDocumentNumber("123")` → false
3. Request A saves → succeeds
4. Request B saves → `DataIntegrityViolationException` from the DB unique constraint

The `GlobalExceptionHandler.handleDataIntegrity()` catches this and returns `409 "Conflicto de datos"` — a different message than the service-level `"Ya existe un huésped con ese número de documento"`. The client receives inconsistent error messages depending on timing.

**Can produce:** inconsistent behavior, race conditions under load

**Recommended fix:** Annotate `create()` and `update()` with `@Transactional`. This ensures the read-then-write is atomic under an appropriate isolation level, making the service-level check the canonical conflict detection path.

---

#### LOW | Code Smell | `User` entity has a public setter for the `creadoEn` timestamp

**File:** `auth/entity/User.java:66`

```java
public void setCreadoEn(LocalDateTime creadoEn) { this.creadoEn = creadoEn; }
```

**Issue:** `creadoEn` is managed by `@PrePersist` and has `updatable = false` in the `@Column` definition. The setter exists but provides a path to set a timestamp that JPA will ignore on updates anyway. Its presence misleads callers into thinking they can change the creation timestamp.

**Contrast with `Guest.java`:** no setter for `createdAt` — the correct pattern.

**Can produce:** maintainability problems, confusion

**Recommended fix:** Remove `setCreadoEn()` from `User.java`. The `@PrePersist` callback is the only legitimate way to set this field.

---

### 2.8 Repositories

#### ✅ Repository methods follow Spring Data naming conventions

Both `UserRepository` and `GuestRepository` use derived query method names that are idiomatic Spring Data:

```java
// UserRepository.java
Optional<User> findByUsername(String username);

// GuestRepository.java
boolean existsByDocumentNumber(String documentNumber);
boolean existsByDocumentNumberAndIdNot(String documentNumber, Long id);
```

No custom JPQL or native SQL is used unnecessarily. The `existsByDocumentNumberAndIdNot` method correctly handles the self-exclusion case during update (a guest can be saved with its own document number without triggering a false 409). This is a non-obvious but correct implementation detail.

---

### 2.9 Entities

#### LOW | Naming | `User.creadoEn` uses a Spanish Java field name; `Guest.createdAt` uses English

**Files:** `auth/entity/User.java:32`, `guest/entity/Guest.java:33`

```java
// User.java — Spanish Java field name
private LocalDateTime creadoEn;

// Guest.java — English Java field name (per CLAUDE.md convention)
private LocalDateTime createdAt;
```

**Issue:** The project documentation (`CLAUDE.md`) states: *"English is used for backend Java identifiers."* `Guest.java` correctly follows this convention. `User.java` predates the convention and uses `creadoEn`, creating inconsistency between the two entities.

**Can produce:** maintainability problems, inconsistency

**Recommended fix:** Rename `User.creadoEn` → `User.createdAt` in a future refactor. The `@Column(name = "creado_en")` mapping ensures the DB column name does not change.

---

#### LOW | Design | No optimistic locking on any entity

**Files:** `User.java`, `Guest.java`

**Issue:** Neither entity has a `@Version` field. Under concurrent edits of the same guest or user record, the last write wins silently. No concurrency error is raised.

**Can produce:** bugs (silent data loss under concurrent writes)

**Recommended fix:** Add `@Version private Long version;` to entities that will be subject to concurrent modification, particularly once booking and financial records are added.

---

### 2.10 DTOs & Validation

#### MEDIUM | Code Duplication | `CreateGuestRequest` and `UpdateGuestRequest` are line-for-line identical

**Files:** `guest/dto/CreateGuestRequest.java`, `guest/dto/UpdateGuestRequest.java`

Both files contain identical fields, identical annotations, and identical getters/setters. Every maintenance action (adding a field, changing a validation rule) must be applied to both files independently.

```java
// Both files — identical content
@NotBlank(message = "El nombre es obligatorio")
@Size(max = 100, message = "El nombre no puede superar 100 caracteres")
private String firstName;
// ... 5 more identical fields
```

**Can produce:** technical debt, maintenance problems (divergence risk)

**Recommended fix:** Extract a common base class:
```java
public abstract class GuestRequestBase { /* shared fields and annotations */ }
public class CreateGuestRequest extends GuestRequestBase {}
public class UpdateGuestRequest extends GuestRequestBase {}
```

Keeping them as separate classes is valid design for independent evolution; the problem is that they currently offer zero differentiation.

---

#### MEDIUM | Validation | `LoginRequest.email` has no `@Email` format constraint

**File:** `auth/dto/LoginRequest.java:7-8`

```java
@NotBlank
private String email;
```

**Issue:** The login DTO only validates that `email` is not blank. Any non-blank string passes validation: `"x"`, `"@"`, `"notanemail"`. The backend then calls `userRepository.findByUsername("x")`, which returns an empty Optional, and the service throws `401 Unauthorized` with `"Credenciales inválidas"`. Functionally correct, but the validation layer does not enforce the semantic contract.

**Contrast with `CreateUserRequest`** which has `@Email @NotBlank`.

**Can produce:** inconsistent behavior, technical debt

**Recommended fix:** Add `@Email(message = "El correo no tiene un formato válido")` to `LoginRequest.email`.

---

#### LOW | Validation | `CreateUserRequest` and `LoginRequest` have no localized validation messages

**Files:** `auth/dto/CreateUserRequest.java`, `auth/dto/LoginRequest.java`

**Issue:** The guest DTOs were updated with Spanish validation messages (e.g., `"El nombre es obligatorio"`). The auth DTOs retain the default English Bean Validation messages (e.g., `"must not be blank"`, `"must be a well-formed email address"`). When the `GlobalExceptionHandler` returns validation errors, auth errors appear in English while guest errors appear in Spanish.

**Can produce:** inconsistent behavior (bilingual error messages), technical debt

**Recommended fix:** Add Spanish `message` attributes to all auth DTO constraints, matching the pattern established in the guest DTOs.

---

#### LOW | Validation | `CreateUserRequest` has no maximum length on `email` or `password`

**File:** `auth/dto/CreateUserRequest.java`

```java
@NotBlank
@Email
private String email;

@NotBlank
@Size(min = 6)
private String password;
```

**Issue:** An attacker can send arbitrarily long strings. A 10 MB email string passes `@NotBlank @Email` and reaches the database layer, where PostgreSQL will reject it at the column length limit (`varchar(150)` for email, `varchar(255)` for password hash). This produces an unhandled `DataIntegrityViolationException` with the generic message `"Conflicto de datos"` rather than a proper validation 400.

**Can produce:** bugs (misleading 409 for oversized input), security vulnerability (denial of service via large payloads)

**Recommended fix:**
```java
@NotBlank @Email @Size(max = 150)
private String email;

@NotBlank @Size(min = 6, max = 128)
private String password;
```

---

### 2.11 Exception Handling

#### ✅ `GlobalExceptionHandler` covers all major exception types

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)        // → HTTP status from exception
    @ExceptionHandler(DataIntegrityViolationException.class) // → 409 Conflict
    @ExceptionHandler(IllegalArgumentException.class)       // → 400 Bad Request
    @ExceptionHandler(MethodArgumentNotValidException.class) // → 400 with field name
    @ExceptionHandler(Exception.class)                      // → 500 Internal Server Error
}
```

The handler is centralized, registered with `@RestControllerAdvice`, and returns a consistent `Map<String, String>` with an `"error"` key. All service exceptions use `ResponseStatusException` with appropriate HTTP status codes. The architecture prevents 500 responses for expected error conditions (not found, conflict, validation failure).

---

#### LOW | Observability | No exception logging in `GlobalExceptionHandler`

**File:** `common/GlobalExceptionHandler.java`

**Issue:** The generic handler catches all `Exception` instances and returns `500 Internal Server Error`, but never logs the exception. Unexpected errors — null pointer exceptions, database connection failures, configuration errors — are silently returned to the client with no server-side trace.

**Can produce:** maintainability problems (invisible errors in production)

**Recommended fix:**
```java
@ExceptionHandler(Exception.class)
public ResponseEntity<Map<String, String>> handleGeneric(Exception ex) {
    log.error("Unhandled exception", ex);  // add this
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of("error", "Error interno del servidor"));
}
```

---

### 2.12 Database Configuration

#### MEDIUM | Risk | `spring.jpa.hibernate.ddl-auto=update` is active inside Docker

**File:** `src/main/resources/application.properties:8`, `docker-compose.yml:33`

```properties
spring.jpa.hibernate.ddl-auto=update
```

```yaml
# docker-compose.yml:33
- SPRING_JPA_HIBERNATE_DDL_AUTO=update
```

**Issue:** `ddl-auto=update` allows Hibernate to modify the schema on application startup — adding columns, creating tables. It will **never drop columns or rename them**. A field removed from an entity leaves the column in the database permanently. A renamed field creates a new column and leaves the old one empty. Schema drift is silent and cumulative.

For a class project with `ddl-auto=update`, this is an acceptable trade-off. For any production deployment, it is a significant operational risk.

**Can produce:** bugs (schema drift, orphaned columns), security vulnerability (data exposure from orphaned columns containing historical data)

**Recommended fix:** Introduce Flyway or Liquibase for schema migrations. Set `ddl-auto=validate` to let Hibernate verify the schema matches without modifying it.

---

#### LOW | Design | Local development database URL is hardcoded in `application.properties`

**File:** `src/main/resources/application.properties:3`

```properties
spring.datasource.url=jdbc:postgresql://localhost:5434/novafacts_db
```

**Issue:** Port `5434` is the host-side mapped port from `docker-compose.yml`. When the Docker-compose stack runs, the Spring app container connects via the internal Docker network (`postgres_db:5432`), overriding this with the `SPRING_DATASOURCE_URL` environment variable. However, a developer who runs the Spring app directly without Docker must have PostgreSQL on port `5434` specifically. The non-standard port is not documented.

**Can produce:** maintainability problems (new developer onboarding confusion)

**Recommended fix:** Add a comment in `application.properties` explaining that port 5434 is the local Docker-mapped port, and note that Docker Compose overrides this URL via the environment variable.

---

### 2.13 Test Coverage

#### ✅ Domain logic unit tests are complete and well-structured

The `booking/` package has 22 unit tests across three test classes:

- `BookingTest` — 9 tests covering constructor validation
- `BookingValidatorTest` — 9 tests covering business rules and boundary conditions
- `InvoiceCalculatorTest` — 11 tests covering financial calculations with specific COP amounts and boundary values at 7 nights

Tests use JUnit 5 `@DisplayName` annotations, `assertAll`, and `assertThrows`. Boundary cases (4 guests, 5 guests, 6 nights, 7 nights) are explicitly tested. This test quality is above average for a class project.

---

#### HIGH | Testing | Zero tests for the auth or guest modules

**Files:** `BackendApplicationTests.java` (1 test, skipped)

```java
@Test
void contextLoads() {
    // Skipped: requires a running PostgreSQL instance.
}
```

**Issue:** The only non-booking test is a Spring context load test that is explicitly skipped. There are no tests for:

- `UserService.login()` — the core authentication logic
- `UserService.createUser()` — user registration including duplicate email handling
- `GuestService.create()` / `update()` — conflict detection, null conversion
- `GuestService.delete()` — 404 handling
- `GuestController` — HTTP layer correctness, response status codes
- `JwtService` — token generation, expiration, validation
- `GlobalExceptionHandler` — correct HTTP status per exception type

**Can produce:** bugs (regressions go undetected), technical debt (refactoring becomes risky)

**Recommended fix:** Add `@WebMvcTest` slices for controllers and `@ExtendWith(MockitoExtension.class)` unit tests for services. A `Testcontainers`-based integration test for the repository layer would allow the `contextLoads` test to actually run.

---

## 3. Frontend Audit

### 3.1 Architecture & Component Structure

The frontend follows a clean views-and-services separation:

```
src/
├── main.js              (plain JS — mounts app, installs router)
├── App.vue              (shell — only <router-view />)
├── router/index.js      (plain JS — route definitions)
├── services/            (api.ts, authService.ts, guestService.ts)
├── types/               (auth.ts, guest.ts)
└── views/               (LoginView.vue, DashboardView.vue, GuestsView.vue)
```

There are no separate `components/` subdirectories — all logic is in views. For the current feature count this is acceptable, but `GuestsView.vue` at 600 lines indicates the pattern will not scale without decomposition.

---

#### MEDIUM | Architecture | `GuestsView.vue` is a 600-line monolithic component

**File:** `frontend/src/views/GuestsView.vue`

**Issue:** A single component manages: the page header, the data table, the create/edit modal form, the delete confirmation modal, all associated state (8 reactive refs), all API calls, form validation, date formatting, and error state. Any change to one area risks affecting others.

Specific concerns:
- The create/edit modal and delete modal share a single `<template>` with no encapsulation
- All 8 `ref()` declarations live in the same scope, making state tracing difficult
- `handleSubmit()` handles both create and edit paths via a `modalMode` flag, making the function responsible for two distinct operations

**Can produce:** maintainability problems, bugs (shared state mutations), technical debt

**Recommended fix:** Extract at minimum two sub-components:
- `GuestFormModal.vue` — create/edit form, receives `initialData` prop, emits `saved` / `cancelled`
- `DeleteConfirmModal.vue` — receives `guest` prop, emits `confirmed` / `cancelled`

This reduces `GuestsView.vue` to orchestration only and makes each piece independently testable.

---

### 3.2 Routing & Navigation Guards

#### HIGH | Security | No navigation guards on protected routes

**File:** `frontend/src/router/index.js`

```javascript
const routes = [
  { path: '/',         name: 'login',     component: LoginView },
  { path: '/dashboard', name: 'dashboard', component: DashboardView },
  { path: '/guests',   name: 'guests',    component: GuestsView }
];
// No router.beforeEach guard exists.
```

**Issue:** Any user who types `/dashboard` or `/guests` directly into the browser address bar reaches those views without possessing a valid JWT. `/dashboard` is entirely static (it makes no API calls) so it renders completely without any authentication. `/guests` makes API calls that return 401, but shows the full Huéspedes page structure with an error message suggesting a connectivity problem, not an authentication failure.

**Can produce:** security vulnerability (unauthenticated access to authenticated views), bugs (confusing error messages)

**Recommended fix:**
```javascript
import { TOKEN_KEY } from '../services/api';

router.beforeEach((to) => {
    if (to.name !== 'login' && !localStorage.getItem(TOKEN_KEY)) {
        return { name: 'login' };
    }
});
```

---

#### HIGH | UX / Security | No 401 response interceptor; expired tokens produce misleading error messages

**File:** `frontend/src/services/api.ts`

```typescript
api.interceptors.request.use((config) => { /* attaches token */ });
// No response interceptor.
```

**Issue:** When a JWT expires (default 24-hour TTL), all API calls return `401 Unauthorized`. Because no response interceptor exists, the 401 is caught by the individual view catch blocks. `GuestsView.vue` displays: *"No se pudo cargar la lista de huéspedes. Verifica tu conexión."* — a message suggesting a network problem. The user has no path to recovery except manually navigating to `/`.

**Can produce:** bugs (user stuck on error screen with no recovery path), security vulnerability (stale token remains in localStorage)

**Recommended fix:**
```typescript
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

#### LOW | UX | Active nav link class is hardcoded per view, not dynamic

**Files:** `DashboardView.vue:7`, `GuestsView.vue:7-8`

```html
<!-- DashboardView.vue — active class is hardcoded on Dashboard link -->
<router-link to="/dashboard" class="nav-link nav-link--active">Dashboard</router-link>
<router-link to="/guests"    class="nav-link">Huéspedes</router-link>

<!-- GuestsView.vue — active class hardcoded on Guests link -->
<router-link to="/dashboard" class="nav-link">Dashboard</router-link>
<router-link to="/guests"    class="nav-link nav-link--active">Huéspedes</router-link>
```

**Issue:** Every new route added to the nav requires manually updating the active class in every existing view. Vue Router provides `router-link-exact-active` and the `active-class` prop to handle this automatically.

**Can produce:** maintainability problems, bugs (future nav link added to one view but not others)

**Recommended fix:**
```html
<router-link to="/dashboard" class="nav-link" active-class="nav-link--active" exact>Dashboard</router-link>
<router-link to="/guests"    class="nav-link" active-class="nav-link--active">Huéspedes</router-link>
```

This removes all hardcoded active-state management from the templates.

---

### 3.3 Authentication Flow

#### MEDIUM | Bug | "Remember me" feature stores data but never reads it — completely non-functional

**Files:** `frontend/src/services/authService.ts:20-22`, `frontend/src/views/LoginView.vue`

```typescript
// authService.ts:20-22
if (shouldRememberUser) {
    localStorage.setItem('user_remembered', email);
}
```

**Issue:** The key `'user_remembered'` is written on successful login when the checkbox is ticked. It is removed on logout. However, `LoginView.vue` has no `onMounted` hook that reads this value to pre-fill `userEmail.value`. The `shouldRememberSession` ref is always initialized to `false`.

Furthermore, the JWT token is stored in `localStorage` unconditionally, meaning sessions already persist across browser restarts regardless of the checkbox. The checkbox has zero functional effect.

**Can produce:** bugs (feature visible in UI that does nothing), maintainability problems

**Recommended fix:** Add to `LoginView.vue`:
```typescript
onMounted(() => {
    const remembered = localStorage.getItem('user_remembered');
    if (remembered) {
        userEmail.value = remembered;
        shouldRememberSession.value = true;
    }
});
```

---

#### LOW | Security | Email validation in `authService.ts` uses a string-inclusion check

**File:** `frontend/src/services/authService.ts:6`

```typescript
if (!email.includes('@')) {
    return { status: 'invalid_credentials', message: 'El formato del correo es inválido.' };
}
```

**Issue:** This check accepts `"@"`, `"not@"`, `"@domain"`, `"a@b"` as valid emails. The HTML `<input type="email">` browser validation is more strict but can be bypassed via DevTools or programmatic submission. The backend `LoginRequest` has no `@Email` constraint. An attacker sending `"x"` as the email will pass the frontend check and receive a 401 from the backend — functionally correct, but the validation intent is broken.

**Can produce:** bugs (invalid email format reaching the server)

**Recommended fix:** Use a simple but correct regex at the service layer, and add `@Email` to `LoginRequest` on the backend.

---

#### LOW | Robustness | `handleFormSubmit` in `LoginView.vue` resets `isSubmitting` inline, not in `finally`

**File:** `frontend/src/views/LoginView.vue:116-117`

```typescript
const result = await authenticateUser(credentials);
isSubmitting.value = false;   // ← not in finally
```

**Issue:** If `authenticateUser` throws an uncaught exception (currently impossible because it catches all errors, but fragile), `isSubmitting` would remain `true` permanently, leaving the submit button disabled for the session.

**Can produce:** bugs (permanently disabled button on unexpected throw)

**Recommended fix:** Use `try/finally`:
```typescript
try {
    const result = await authenticateUser(credentials);
    // handle result
} finally {
    isSubmitting.value = false;
}
```

---

### 3.4 API Services & Axios

#### ✅ Shared Axios instance with automatic JWT injection

`api.ts` creates a single `axios` instance with the `VITE_API_URL` base URL and a request interceptor that reads the token from `localStorage` and attaches it as a Bearer token. All service modules (`authService.ts`, `guestService.ts`) import this shared instance. There is no token duplication, no hardcoded base URL in individual services, and `axios.isAxiosError()` is used consistently for error detection.

---

#### LOW | Dead Code | `getGuest(id)` in `guestService.ts` is exported but never imported

**File:** `frontend/src/services/guestService.ts:9-12`

```typescript
export async function getGuest(id: number): Promise<Guest> {
    const response = await api.get<Guest>(`/api/guests/${id}`);
    return response.data;
}
```

**Issue:** No view imports `getGuest`. The edit modal pre-fills from data already in the local `guests` array without making an additional API call. The function is exported dead code.

**Can produce:** unnecessary complexity (callers don't know whether to use it)

**Recommended fix:** Remove `getGuest` unless a detail view is planned. If it is planned, add a comment noting the intended consumer.

---

### 3.5 TypeScript Usage

#### LOW | Consistency | Entry point files (`main.js`, `router/index.js`) are plain JavaScript

**Files:** `frontend/src/main.js`, `frontend/src/router/index.js`

**Issue:** All other files in the project use TypeScript (`.ts`, `.vue` with `<script setup lang="ts">`). The two entry point files are plain `.js` with no type annotations. The Vite TypeScript configuration does not enforce type checking on these files.

**Can produce:** maintainability problems, technical debt (inconsistency attracts more JS additions)

**Recommended fix:** Rename both to `.ts` and add appropriate type annotations (the router file in particular benefits from typed route definitions).

---

#### LOW | Typing | `emptyForm()` is typed as `CreateGuestRequest` but used for `UpdateGuestRequest`

**File:** `frontend/src/views/GuestsView.vue:162-169, 225`

```typescript
const emptyForm = (): CreateGuestRequest => ({ ... });
const form = ref<CreateGuestRequest>(emptyForm());
// ...
const updated = await updateGuest(selectedGuest.value.id, form.value as UpdateGuestRequest);
```

**Issue:** The `as UpdateGuestRequest` cast suppresses a type error that should not exist. Since `CreateGuestRequest` and `UpdateGuestRequest` are structurally identical types, this works at runtime, but it signals that the type design has not been resolved. The cast communicates "I know this is wrong but TypeScript won't let me do this otherwise."

**Can produce:** maintainability problems (future type divergence would break the cast silently)

**Recommended fix:** If the types remain identical, use a single `GuestFormData` type for the form and use it for both create and update.

---

### 3.6 Component Analysis

#### MEDIUM | Visual Bug | `App.vue` global `text-align: center` propagates into table data cells

**File:** `frontend/src/App.vue:17`

```css
/* Not scoped — applies to every element in the application */
#app {
  text-align: center;
  color: #2c3e50;
}
```

**Issue:** `text-align` is an inherited CSS property. All descendant elements of `#app` inherit `center` alignment unless they explicitly override it. In `GuestsView.vue`, the table headers explicitly set `text-align: left`, but the table data cells do not:

```css
/* GuestsView.vue — scoped */
.data-table th { text-align: left; }   /* ✓ overrides #app */
.data-table td {
    padding: 14px 16px;
    vertical-align: middle;
    /* text-align not set — inherits center from #app */
}
```

**Result:** Column headers are left-aligned while data cells are center-aligned. This is visually inconsistent and apparent as soon as any guest data exists.

**Can produce:** bugs (visual defect in primary feature's data table)

**Recommended fix:** Add `text-align: left` to `.data-table td` in `GuestsView.vue`. Additionally, audit `#app { text-align: center }` — centering the entire application container is usually not the intended default and can affect other future components.

---

#### MEDIUM | Layout | `DashboardView.vue` has `flex: 1` on both `.header-title` and `.header-nav`

**File:** `frontend/src/views/DashboardView.vue:84-90, 52-57`

```css
.header-title { flex: 1; }   /* original */
.header-nav   { flex: 1; }   /* added later */
```

**Issue:** Both siblings in the flex header container claim `flex: 1`. They split the available space equally, causing the "NovaFacts" title to occupy half the header width rather than sitting compactly next to the logo.

**Compare with `GuestsView.vue`** where `.header-title` has no flex, allowing `.header-nav { flex: 1 }` to absorb all remaining space correctly.

The two pages have visually different headers despite using the same design language.

**Can produce:** bugs (layout inconsistency between pages)

**Recommended fix:** Remove `flex: 1` from `.header-title` in `DashboardView.vue`.

---

#### LOW | Dead Link | Forgot password link is `href="#"` with no handler

**File:** `frontend/src/views/LoginView.vue:68`

```html
<a href="#" class="forgot-link">¿Olvidaste tu contraseña?</a>
```

**Issue:** Clicking this link changes the URL hash and scrolls the page top — visually identical to "nothing happened" but technically a navigation. There is no `@click.prevent`, no router navigation, and no modal or toast triggered. Users who click it receive no feedback.

**Can produce:** bugs (false affordance; user thinks they triggered a recovery flow when nothing happened)

**Recommended fix:** Add `@click.prevent` to suppress the default anchor behavior until the feature is implemented. Or remove the element entirely.

---

### 3.7 State Management

#### ✅ No state management library is needed at this scale

The project has three views with isolated state. Using Pinia or Vuex would add complexity without benefit at this scope. The per-view `ref()` pattern is appropriate. If a fourth or fifth view needs to share guest or user state, Pinia should be introduced at that point.

---

### 3.8 Form Validation

#### LOW | UX | Guest form client-side validation does not check email format

**File:** `frontend/src/views/GuestsView.vue:212-215`

```typescript
if (!form.value.firstName || !form.value.lastName ||
    !form.value.documentType || !form.value.documentNumber) {
    modalError.value = 'Los campos marcados con * son obligatorios.';
    return;
}
```

**Issue:** The client-side check only verifies that required fields are non-empty. If a user enters `"notanemail"` in the email field, the form submits, the backend returns a 400 with the `@Email` validation message, and the error appears in `modalError`. Functionally correct, but the client-side check should mirror the server-side constraints for a better UX (fail fast before the network round-trip).

**Can produce:** poor user experience

**Recommended fix:** Add a simple email format check before submitting if email is provided.

---

### 3.9 CSS Organization

#### MEDIUM | CSS | Identical header, nav, and button styles are duplicated across `DashboardView.vue` and `GuestsView.vue`

**Files:** `DashboardView.vue:43-104`, `GuestsView.vue:293-355`

The following CSS blocks are near-identical across both components (different class names, same values):

| Property group | DashboardView | GuestsView |
|---|---|---|
| Header container | `.dashboard-header` | `.page-header` |
| Logo | `.header-logo` | `.header-logo` |
| Nav links | `.nav-link`, `.nav-link--active` | `.nav-link`, `.nav-link--active` |
| Logout button | `.logout-button` | `.logout-button` |

Because Vue's `scoped` attribute prevents cross-component style leakage, some duplication is structural. However, the identical button system (`btn`, `btn--primary`, `btn--ghost`, `btn--danger`, `btn--sm`) defined in `GuestsView.vue` will need to be reproduced in every new feature view.

**Can produce:** technical debt, maintainability problems (style changes must be applied in N files)

**Recommended fix:** Extract shared styles to a non-scoped `src/assets/global.css` imported in `main.js`, covering button classes, nav link classes, and the header shell. Component-specific layout rules remain scoped.

---

#### LOW | Dead Code | `src/style.css` is a Vite scaffold template file that is never imported

**File:** `frontend/src/style.css` (297 lines)

**Issue:** The file contains CSS variables, `.hero`, `.counter`, `.ticks`, `#next-steps`, `#spacer`, and `#docs` selectors — verbatim content from the Vite project template. `main.js` does not import it. `App.vue` does not import it. It exists in the `src/` directory and is never loaded by the browser.

**Verification:**
```bash
grep -r "style.css" src/   # returns zero results
```

**Can produce:** unnecessary complexity (dead code in the source tree)

**Recommended fix:** Delete `src/style.css`.

---

### 3.10 Accessibility

#### LOW | Accessibility | Modals lack required ARIA attributes and focus management

**File:** `frontend/src/views/GuestsView.vue:65-135`

```html
<div v-if="showModal" class="modal-backdrop" @click.self="closeModal">
  <div class="modal">
    <h3 class="modal-title">...</h3>
    <!-- No role, no aria-modal, no aria-labelledby -->
  </div>
</div>
```

Missing:
- `role="dialog"` on the modal `<div>`
- `aria-modal="true"` to tell screen readers the rest of the page is inert
- `aria-labelledby` pointing to the modal title element's `id`
- Focus management: when the modal opens, focus stays on the trigger button behind the backdrop
- `role="alert"` or `aria-live="polite"` on `.form-error` paragraphs so screen readers announce validation errors

**Can produce:** accessibility issues (screen reader users cannot interact with modals)

**Recommended fix:**
```html
<div role="dialog" aria-modal="true" aria-labelledby="modal-title-id" class="modal">
  <h3 id="modal-title-id" class="modal-title">...</h3>
```
Add a composable or `onMounted` call to move focus to the first input when the modal opens.

---

## 4. Infrastructure Audit

### 4.1 Docker Configuration

#### MEDIUM | Infrastructure | `Dockerfile` declares `EXPOSE 8081` but the application runs on port 8082

**File:** `project-backend/Dockerfile:4`

```dockerfile
FROM eclipse-temurin:21-jdk-alpine
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8081          # ← incorrect
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**Issue:** `application.properties:17` sets `server.port=8082`. `docker-compose.yml:28` maps `"8082:8082"`. The `EXPOSE` instruction documents the wrong port. Running `docker run -P` (automatic port mapping from EXPOSE) maps the host to 8081 while the service listens on 8082 — the application is silently unreachable.

**Can produce:** bugs (application unreachable when deployed standalone)

**Recommended fix:**
```dockerfile
EXPOSE 8082
```

---

#### LOW | Infrastructure | `docker-compose.yml` health check uses a hardcoded database user

**File:** `project-backend/docker-compose.yml:17`

```yaml
healthcheck:
  test: ["CMD-SHELL", "pg_isready -U postgres"]
```

**Issue:** Line 10 uses `${POSTGRES_USER:-postgres}` for the actual PostgreSQL connection. If `POSTGRES_USER` is overridden to something other than `postgres`, `pg_isready -U postgres` will fail — the `postgres` superuser may not exist — causing the `spring_app` service (which depends on `postgres_db` being healthy) to never start.

**Can produce:** bugs (application fails to start when database user is customized)

**Recommended fix:**
```yaml
test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER:-postgres}"]
```

---

#### LOW | Infrastructure | No resource limits in `docker-compose.yml`

**File:** `project-backend/docker-compose.yml`

**Issue:** Neither `postgres_db` nor `spring_app` has `mem_limit`, `cpus`, or any resource constraints. A runaway application or memory leak would consume all host memory.

**Can produce:** performance problems (resource starvation on shared machines)

**Recommended fix:**
```yaml
spring_app:
  deploy:
    resources:
      limits:
        memory: 512m
```

---

### 4.2 Environment Configuration

#### ✅ Frontend environment variable handling is correct

The frontend uses `VITE_API_URL` via `import.meta.env.VITE_API_URL`, provided through `.env` and documented in `.env.example`. The token key is a named constant (`TOKEN_KEY`) in `api.ts`, not scattered as a magic string.

---

#### LOW | Documentation | No `.env.example` file exists for the backend

**Files:** `project-backend/` directory

**Issue:** The frontend ships `frontend/.env.example` documenting the required `VITE_API_URL` variable. The backend requires `POSTGRES_PASSWORD`, `JWT_SECRET`, and optionally `JWT_EXPIRATION`, `POSTGRES_USER` — but there is no corresponding `.env.example` or environment variable documentation in `project-backend/`. A new developer has no way to know which variables are required without reading `application.properties` and `docker-compose.yml` in combination.

**Can produce:** maintainability problems (onboarding friction)

**Recommended fix:** Create `project-backend/.env.example`:
```env
POSTGRES_USER=postgres
POSTGRES_PASSWORD=your_password_here
JWT_SECRET=your_base64_encoded_256bit_key_here
JWT_EXPIRATION=86400000
ALLOWED_ORIGIN=http://localhost:5173
```

---

### 4.3 Build Configuration

#### LOW | Dead Dependency | Lombok is declared as a dependency and annotation processor but never used

**File:** `project-backend/pom.xml`

```xml
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <optional>true</optional>
</dependency>
```

```xml
<annotationProcessorPaths>
    <path>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
    </path>
</annotationProcessorPaths>
```

**Issue:** No Java file in the project uses any Lombok annotation (`@Getter`, `@Setter`, `@Data`, `@Builder`, etc.). All entities and DTOs use hand-written getters and setters. Lombok adds a compile-time annotation processing step and is excluded from the final jar (marked `<optional>`), but it is listed in two places in `pom.xml` for no effect.

**Can produce:** unnecessary complexity (dependency implies Lombok is used; new developers may write Lombok annotations based on this false signal)

**Recommended fix:** Remove the Lombok dependency and its annotation processor configuration from `pom.xml`.

---

#### LOW | Documentation | `pom.xml` has empty template placeholder fields

**File:** `project-backend/pom.xml:13-27`

```xml
<name/>
<description/>
<url/>
<licenses><license/></licenses>
<developers><developer/></developers>
<scm>
    <connection/><developerConnection/><tag/><url/>
</scm>
```

**Issue:** Spring Initializr leaves these as empty elements. They have no functional impact on the build but produce empty XML nodes that clutter the file and may generate warnings in some Maven plugins.

**Can produce:** unnecessary complexity

**Recommended fix:** Remove empty elements or fill them with actual project metadata.

---

## 5. Dead Code & Unused Assets

The following items exist in the codebase but serve no current purpose:

| Asset | Location | Status | Reason |
|---|---|---|---|
| `style.css` | `frontend/src/style.css` | Dead | Not imported by any file; Vite template leftover |
| `getGuest(id)` function | `frontend/src/services/guestService.ts:9` | Unused export | No view imports it |
| `LoginResponse.message` field | `auth/dto/LoginResponse.java` | Unused | Frontend reads only `token`; `"Login exitoso"` is never consumed |
| Lombok dependency | `pom.xml` | Unused dependency | No Lombok annotations in any source file |
| `BackendApplicationTests.contextLoads()` | Test directory | Effectively dead | Body is empty (skipped with a comment) |
| `user_remembered` localStorage key | `authService.ts:21` | Written, never read | `LoginView.vue` has no `onMounted` to read it |
| `pom.xml` empty XML elements | `pom.xml:13-27` | Placeholder clutter | `<name/>`, `<description/>`, `<url/>`, etc. |

---

## 6. What Is Already Well Implemented

The following aspects represent genuine strengths and are explicitly acknowledged:

### Backend

| Strength | Evidence |
|---|---|
| Feature-package structure | `auth/`, `booking/`, `guest/`, `common/`, `config/` — each feature is self-contained |
| Thin controllers | Every controller method is a single delegation call; zero business logic in controllers |
| Constructor injection throughout | No `@Autowired` field injection in any class |
| GlobalExceptionHandler | Handles 5 exception types, returns consistent `Map<String, String>` with `"error"` key |
| Correct HTTP status codes in guest module | 201 for POST, 204 for DELETE, 404 for not found, 409 for conflict, 400 for validation failure |
| DTO isolation | No entity class is ever returned directly from a controller |
| `existsByDocumentNumberAndIdNot` | Correctly handles self-exclusion during update — non-obvious but correct |
| Service-level conflict detection | Duplicate document check before INSERT gives a meaningful Spanish error message; DB constraint is defense-in-depth |
| JJWT 0.12.6 | Current library version, proper key derivation via `Keys.hmacShaKeyFor()` |
| BCrypt for password hashing | `PasswordEncoder` bean in `SecurityConfig`, injected into `UserService` |
| JWT secret from environment variable | `@Value("${jwt.secret}")` — never hardcoded |
| Unit test quality for booking domain | 22 tests with boundary cases, `@DisplayName`, and correct assertions |
| `@PrePersist` timestamp management | Both entities auto-set creation timestamp; no timestamp setter on `Guest` |

### Frontend

| Strength | Evidence |
|---|---|
| Vue 3 Composition API used correctly | `ref()`, `onMounted`, `async/await`, `<script setup lang="ts">` throughout |
| Single shared Axios instance | All services import from `api.ts`; no hardcoded base URLs in service files |
| `axios.isAxiosError()` used consistently | Both `handleSubmit` and `handleDelete` use proper Axios error detection |
| Null conversion for optional fields | `form.value.email || null` before API call; prevents `""` stored in DB |
| Three UI states implemented | Loading (spinner), error (with retry button), and empty-state placeholder in `GuestsView` |
| Optimistic UI updates | After create/update/delete, the local array is updated without a redundant GET |
| Delete confirmation with guest name | Modal shows `{{ guestToDelete?.firstName }} {{ guestToDelete?.lastName }}` before irreversible delete |
| Delete error surfaced to user | `deleteError` ref is displayed in the confirmation modal |
| Discriminated union for `AuthResult` | `AuthResult = 'success' | 'invalid_credentials' | 'server_error'` — type-safe result handling |
| `TOKEN_KEY` as named constant | Exported from `api.ts`, used consistently — no magic string `'auth_token'` scattered around |
| Responsive login page | `LoginView.vue` includes `@media (max-width: 768px)` with proper grid collapse |
| Responsive guest form | `@media (max-width: 480px)` switches form to single-column |
| `router-link` globally registered | Correctly used without importing per-component |
| `@Pattern` on `documentType` | `CC|CE|PA|NIT|TI` with Bean Validation's `matches()` (full-string semantics) — correct regex without manual anchoring |
| Spanish validation messages | All guest DTOs use localized messages for every constraint |

---

## 7. Overall Project Assessment

### Scoring

| Category | Score | Rationale |
|---|---|---|
| **Architecture** | 7 / 10 | Feature-package structure is intentional and scalable. Service-controller-repository separation is maintained. Deducted for orphaned `booking` package without Spring wiring, inconsistent patterns between auth and guest modules (UserService.delete vs GuestService.delete), and a 600-line monolithic view component. |
| **Security** | 4 / 10 | JWT implementation is technically correct (signing, expiration, filter chain). BCrypt is used. Auth endpoints are the only public route. However: CORS blocks all non-local deployments (critical), no RBAC exists (all authenticated users are equivalent), no route guards protect the frontend, SQL TRACE logging exposes email addresses in logs, and any authenticated user can enumerate all accounts via GET /api/users. |
| **Code Quality** | 6 / 10 | Large sections of code are clean, consistent, and professionally structured. Deducted for: a 100% duplicated DTO pair, multiple broken features (remember me, forgot password link), the `nombre = email` data bug, unused dependency (Lombok), and service methods lacking `@Transactional`. |
| **Maintainability** | 5 / 10 | The feature-package architecture makes finding code easy. However: duplicated CSS across views, mixed JavaScript/TypeScript in entry files, inconsistent Java field naming conventions (creadoEn vs createdAt), no Spring profiles for dev/prod separation, and zero tests for the two shipped features create significant future maintenance friction. |
| **Scalability** | 4 / 10 | `GET /api/users` and `GET /api/guests` both return complete, unbounded collections with no pagination. `ddl-auto=update` is unsuitable for production schema evolution. No caching layer. No connection pooling configuration beyond Spring Boot defaults. `GuestsView.vue` will become unmanageable as features grow. |
| **Readability** | 6 / 10 | Variable and method names are clear and descriptive. The feature-package layout makes the code navigable. Deducted for `LoginView.vue`'s excessive inline comments that explain *what* code does rather than *why* (e.g., `// Evitar peticiones duplicadas (Retorno anticipado rápido)`), mixed Spanish/English identifiers in the Java layer, and `style.css` noise. |
| **Consistency** | 5 / 10 | Guest module is internally consistent. Auth module has several inconsistencies with the guest module: no Spanish validation messages, no existence check before delete, different patterns for the `nombre` field. Frontend mixes `.js` and `.ts` entry files. Active nav link class is statically hardcoded per-view instead of dynamically computed. |
| **Documentation** | 3 / 10 | `CLAUDE.md` provides a clear project overview and architectural context. Beyond that: no API documentation (no Swagger/OpenAPI), no backend `.env.example`, empty `pom.xml` metadata fields, no README for either repository, and no inline documentation explaining non-obvious architectural decisions. |
| **Production Readiness** | 3 / 10 | The application works correctly for its implemented scope in local development. It is not deployable as-is to any other environment: CORS blocks all non-local frontends, `ddl-auto=update` is unsafe for production schema management, SQL TRACE logs user data, Dockerfile declares the wrong port, zero frontend tests exist, and zero tests cover the two shipped backend features. |

### Overall Score: **5.0 / 10**

The project demonstrates solid foundational decisions — the architecture, the security model (JWT + BCrypt), the exception handling pattern, and the Vue 3 Composition API usage — that most class projects do not reach. The implementation is above average for its academic context. The overall score reflects the gap between a working local prototype and a deployable, maintainable software product.

---

## 8. Recommended Next Priorities

The following improvements are ordered by their combination of impact, risk, and implementation effort. Each addresses a real problem identified in this audit.

---

### Priority 1 — Fix CORS (Critical, ~10 minutes)

**Why first:** This is the only change that can silently prevent the application from working during evaluation. If the professor opens the frontend on a different port or machine, nothing works. Ten lines of change, zero risk.

```java
// SecurityConfig.java
List<String> origins = List.of(
    System.getenv().getOrDefault("ALLOWED_ORIGIN", "http://localhost:5173")
);
configuration.setAllowedOrigins(origins);
```

---

### Priority 2 — Add router navigation guards + 401 response interceptor (High, ~30 minutes)

**Why second:** These two changes together close the authentication bypass at the frontend. Without guards, unauthenticated users reach the dashboard. Without the 401 interceptor, expired sessions produce confusing connectivity errors with no recovery path. Both are additive changes to existing files.

---

### Priority 3 — Fix `text-align: center` table visual bug + `flex: 1` header inconsistency (Medium, ~15 minutes)

**Why third:** These are visible defects in the primary delivered feature. Any evaluator who creates a guest and views the table will see center-aligned data cells. The header layout difference between Dashboard and Guests is also immediately apparent.

---

### Priority 4 — Add Spanish validation messages to auth DTOs + `@Email` to `LoginRequest` + max lengths to `CreateUserRequest` (Medium, ~20 minutes)

**Why fourth:** The guest module returns Spanish validation errors. The auth module returns English ones. This inconsistency is visible in any API client. The missing `@Email` on `LoginRequest` and missing max-length constraints are low-effort fixes with real validation impact.

---

### Priority 5 — Fix "Remember me" feature (Medium, ~10 minutes)

**Why fifth:** A non-functional UI control is a demonstration risk. The fix is a five-line `onMounted` addition. The feature already stores the data correctly — only the read-back is missing.

---

### Priority 6 — Add router navigation guard + fix `UserService.deleteUser()` 204/404 inconsistency (Medium, ~15 minutes)

**Why sixth:** `DELETE /api/users/9999` returns `204 No Content` when the user does not exist. The guest module returns 404 in this case. The fix mirrors what was already done for guests.

---

### Priority 7 — Remove SQL debug logging + create Spring profiles (Medium, ~20 minutes)

**Why seventh:** SQL TRACE logging is a data exposure risk in any environment with log storage. Creating dev/prod profiles also unblocks the removal of `ddl-auto=update` from the production configuration.

---

### Priority 8 — Fix `Dockerfile EXPOSE` port + `docker-compose.yml` health check user (Medium, ~5 minutes)

**Why eighth:** Two one-line fixes that make the Docker configuration accurate and portable.

---

### Priority 9 — Extract `GuestFormModal.vue` and `DeleteConfirmModal.vue` from `GuestsView.vue` (Medium, ~1 hour)

**Why ninth:** At 600 lines, `GuestsView.vue` is already the largest file in the project. Every new feature added to the guest module makes it harder to read. Extracting the modal components is the most impactful refactor for maintainability.

---

### Priority 10 — Add `@Transactional` to `GuestService.create()` and `GuestService.update()` (Low, ~5 minutes)

**Why tenth:** Eliminates the race condition between the duplicate-document check and the INSERT, ensuring the service-level Spanish error message is always the one returned rather than the fallback `"Conflicto de datos"` from the `DataIntegrityViolationException` handler.

---

### Priority 11 — Eliminate Lombok from `pom.xml`, fix empty `pom.xml` fields, add backend `.env.example` (Low, ~20 minutes)

**Why eleventh:** These are housekeeping items. Removing unused Lombok cleans up the build and removes a misleading signal. Adding `.env.example` eliminates onboarding friction for any new contributor.

---

### Priority 12 — Add `@Transactional(readOnly = true)` + `@Version` fields + API versioning (Low, future sprint)

**Why last:** These are best-practice improvements with low urgency at the current scale. `@Transactional(readOnly = true)` is an optimization. `@Version` for optimistic locking matters when concurrent writes become likely. API versioning (`/api/v1/`) costs nothing to add now but becomes expensive to retrofit once a stable client base exists.

---

### Priority 13 — Write tests for auth and guest modules (High value, future sprint)

**Why separately listed:** Testing is not a single task but a commitment. The booking domain tests are a good model. The same `@DisplayName`-based JUnit 5 style should be applied to:
- `UserServiceTest` — login success, login failure, duplicate email
- `GuestServiceTest` — CRUD operations, conflict detection, 404 handling
- `GuestControllerTest` — HTTP layer via `@WebMvcTest`

This is the single change with the highest long-term impact on project reliability.

---

*End of Audit Report — NovaFacts v0.0.1-SNAPSHOT — 2026-06-27*
