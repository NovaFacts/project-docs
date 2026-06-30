# NovaFacts — Architecture Roadmap
### Current State → MVP

**Project:** Ingeniería de Software 1 (2016701) — Universidad Nacional de Colombia  
**Team:** Julián Andrés Foglia · Santiago Cubides · Laura Valentina Hernández · Eduard Joel Ostos Castro  
**Date:** 2026-06-27  
**Stack:** Spring Boot 3.5 · Java 21 · Vue 3 · PostgreSQL · JWT (jjwt 0.12.6)

---

## 1. Current State

### 1.1 What exists and works

| Area | Status |
|------|--------|
| Auth module | Complete — `JwtService`, `JwtAuthenticationFilter`, `UserService`, `AuthController`, `UserController` |
| Spring Security | Stateless JWT, BCrypt, `GlobalExceptionHandler`, `@Valid` on all DTOs |
| JPA entity | `User` → table `usuario` (email, password_hash, nombre, rol_id, activo, creado_en) |
| Frontend | Login → JWT stored → `router.push('/dashboard')` → placeholder `DashboardView` |
| HTTP layer | Single Axios instance (`api.ts`), Bearer interceptor, `TOKEN_KEY` single source of truth |
| Infrastructure | Docker Compose (PostgreSQL 5434, Spring Boot 8082), `.env.local` pattern, env-var config |
| Domain POJOs | `Booking`, `BookingValidator`, `InvoiceCalculator` — unit-tested, not wired to JPA or API |

### 1.2 The gap — C-04 (CRITICAL)

All 13 non-`usuario` tables in `Esquema_BD.sql` have no JPA entity, repository, service, or controller. Zero domain endpoints exist. The `booking/` package's business logic is unreachable through the API.

### 1.3 Open infrastructure debt (must resolve before new domain code)

| ID | Issue |
|----|-------|
| N-01 | `createUser()` hardcodes `rolId = 1` — FK violation on fresh DB |
| N-04 | `User.username` field maps to `email` column — semantic mismatch |
| N-05 | `nombre` set to the user's email address |
| H-08 | No service-level duplicate email check before DB constraint fires |
| M-01 | `double` used for financial calculations |
| M-02 | Verbose SQL logging unconditional in `application.properties` |
| M-03 | `ddl-auto=update`, no migration tool, `Esquema_BD.sql` orphaned |
| M-04 | `Dockerfile` declares `EXPOSE 8081`; app listens on 8082 |
| M-06 | No router-level navigation guards on frontend |
| L-09 | No `tsconfig.json` in `project-frontend/frontend/` |
| L-11 | `BackendApplicationTests` is an empty no-op |

---

## 2. Gap Analysis by Domain Module

| Module | DB tables | Backend | Frontend |
|--------|-----------|---------|----------|
| Auth / Usuarios | `rol`, `usuario` | Complete | Login + placeholder dashboard |
| Propiedades | `propiedad`, `canal`, `temporada` | Absent | Absent |
| Políticas de cancelación | `politica_cancelacion`, `regla_penalidad` | Absent | Absent |
| Reservas | `reserva`, `historial_reserva` | Absent | Absent |
| Anticipos | `anticipo` | Absent | Absent |
| Penalidades | `penalidad` | Absent (InvoiceCalculator POJO only) | Absent |
| Facturación | `factura`, `nota_credito` | Absent | Absent |
| Devoluciones | `devolucion` | Absent | Absent |
| Trazabilidad | `log_transaccion` | Absent | Absent |

---

## 3. Roadmap

Six phases. **Phase 0** is a pre-sprint that must complete before any new entity is introduced. **Phases 1–5** map directly to the team's sprint schedule (T-01 through T-17).

---

### Phase 0 — Infrastructure Hardening
**Duration:** ~1 day  
**Resolves:** M-01, M-02, M-03, M-04, M-06, N-01, N-04, N-05, H-08, L-09, L-11

#### 0a. Flyway (resolves M-03)

- Add `flyway-core` to `pom.xml`
- Set `spring.flyway.enabled=true`, `spring.flyway.baseline-on-migrate=true`
- Remove `spring.jpa.hibernate.ddl-auto=update` → replace with `validate`
- Create `src/main/resources/db/migration/V1__initial_schema.sql` — copy `Esquema_BD.sql` verbatim (all 14 tables, all FKs, all constraints)
- Create `V2__seed_data.sql` — INSERT 4 roles (`Administrador`, `Contador`, `Auxiliar contable`, `Recepcionista`), INSERT 3 canales (`Airbnb`, `Booking`, `Web propia`)

#### 0b. Verbose SQL → dev profile (resolves M-02)

- Create `src/main/resources/application-dev.properties` with `show-sql=true`, `SQL=DEBUG`, `BasicBinder=TRACE`
- Remove those three lines from `application.properties`
- Activate locally via `SPRING_PROFILES_ACTIVE=dev` in `.env.local`

#### 0c. Rename `User.username` → `User.email` (resolves N-04)

- Rename the Java field; `@Column(name = "email")` stays unchanged (zero schema impact)
- Update `UserRepository`: rename `findByUsername` → `findByEmail`
- `UserDetailsServiceImpl.loadUserByUsername()` calls `findByEmail()` internally (keep Spring Security interface signature)
- Update all callers in `UserService`

#### 0d. Fix `createUser()` (resolves N-01, N-05, H-08)

- Add `rolId` (`@NotNull`) and `nombre` (`@NotBlank`) to `CreateUserRequest`
- In `UserService.createUser()`:
  1. Check `rolRepository.existsById(request.getRolId())` → throw `ResponseStatusException(NOT_FOUND, "Rol no encontrado")` if false
  2. Check `userRepository.existsByEmail(email)` → throw `ResponseStatusException(CONFLICT, "El correo ya está registrado")` if true
  3. `user.setNombre(request.getNombre())`
- Add `RolRepository extends JpaRepository<Rol, Integer>` (the only new entity in Phase 0)

#### 0e. BigDecimal migration (resolves M-01)

- Replace all `double` with `BigDecimal` in `Booking.java`, `BookingValidator.java`, `InvoiceCalculator.java`
- Use `RoundingMode.HALF_UP` at every calculation step
- Update corresponding test classes

#### 0f. Dockerfile port fix (resolves M-04)

- `Dockerfile:4` — change `EXPOSE 8081` → `EXPOSE 8082`

#### 0g. Frontend pre-sprint (resolves M-06, L-09, L-11)

- Add `router.beforeEach()` in `router/index.js`: check `localStorage.getItem(TOKEN_KEY)` for routes with `meta: { requiresAuth: true }`, redirect to `/` if absent
- Apply `meta: { requiresAuth: true }` to `/dashboard`
- Add `tsconfig.json` to `project-frontend/frontend/`
- Replace empty `BackendApplicationTests.contextLoads()` with smoke test: POST `/api/auth/login` with wrong credentials asserts HTTP 401

---

### Sprint 1 — Data Model, Reference Data, Roles (T-01, T-02, T-03)
**MoSCoW:** All MUST  
**Owners:** T-01 Santiago · T-02 Eduard · T-03 Eduard (auth complete — T-03 becomes RBAC wiring)

#### Backend — New feature packages

```
com.novafacts.backend
├── rol/
│   ├── entity/Rol.java                      @Entity @Table("rol")
│   ├── repository/RolRepository.java         JpaRepository<Rol, Integer>
│   ├── service/RolService.java               findAll()
│   ├── controller/RolController.java         GET /api/roles
│   └── dto/RolResponse.java
├── propiedad/
│   ├── entity/Propiedad.java                 @Entity @Table("propiedad")
│   ├── repository/PropiedadRepository.java   findByActivaTrue(Pageable)
│   ├── service/PropiedadService.java         CRUD; soft-delete sets activa=false
│   ├── controller/PropiedadController.java   GET/POST /api/propiedades, /{id} GET/PUT/DELETE
│   └── dto/ CreatePropiedadRequest, PropiedadResponse
├── canal/
│   ├── entity/Canal.java                     @Entity @Table("canal")
│   └── controller/CanalController.java       GET /api/canales (read-only)
├── temporada/
│   ├── entity/Temporada.java                 @Entity @Table("temporada")
│   └── controller/TemporadaController.java   GET/POST /api/temporadas
└── politica/
    ├── entity/ PoliticaCancelacion.java, ReglaPenalidad.java
    ├── service/ PoliticaCancelacionService.java
    ├── controller/ PoliticaCancelacionController.java
    │              GET/POST /api/politicas
    │              POST /api/politicas/{id}/reglas
    └── dto/ CreatePoliticaRequest, PoliticaResponse, CreateReglaRequest, ReglaResponse
```

**Entity conventions (applies to every new entity):**
- Column names must match `Esquema_BD.sql` exactly: `@Column(name = "nombre")`, `@Column(name = "rol_id")`, etc.
- All monetary fields: `BigDecimal` — never `double`
- Dates: `LocalDate`; timestamps: `LocalDateTime` with `@CreationTimestamp` / `@UpdateTimestamp`
- Constructor injection everywhere; no `@Autowired` on fields

#### RBAC wiring (T-02 / T-03)

- `UserDetailsServiceImpl.loadUserByUsername()`: join-fetch `rol` from `usuario`, return as `GrantedAuthority("ROLE_" + rol.nombre.toUpperCase().replace(" ", "_"))`
- Add `"rol"` claim to JWT in `JwtService.generateToken(email, rolNombre)`; extract in `JwtAuthenticationFilter` to populate authorities without a DB lookup per request
- `SecurityConfig` rule chain:

```java
.requestMatchers(HttpMethod.POST, "/api/propiedades/**").hasRole("ADMINISTRADOR")
.requestMatchers(HttpMethod.DELETE, "/api/propiedades/**").hasRole("ADMINISTRADOR")
.requestMatchers(HttpMethod.POST, "/api/politicas/**").hasRole("ADMINISTRADOR")
.requestMatchers(HttpMethod.GET, "/api/**").authenticated()
```

#### REST API — Sprint 1

| Method | Path | Role | HTTP |
|--------|------|------|------|
| GET | `/api/roles` | AUTH | 200 |
| GET | `/api/canales` | AUTH | 200 |
| GET | `/api/temporadas` | AUTH | 200 |
| POST | `/api/temporadas` | ADMIN | 201 |
| GET | `/api/propiedades` | AUTH | 200 |
| POST | `/api/propiedades` | ADMIN | 201 |
| GET/PUT/DELETE | `/api/propiedades/{id}` | AUTH / ADMIN | 200 / 200 / 204 |
| GET | `/api/politicas` | AUTH | 200 |
| POST | `/api/politicas` | ADMIN | 201 |
| POST | `/api/politicas/{id}/reglas` | ADMIN | 201 |

#### Frontend — Sprint 1

- `views/PropiedadesView.vue` — table + "Nueva propiedad" form
- `views/PoliticasView.vue` — policy list with expandable rule details
- `components/AppSidebar.vue` — persistent sidebar with role-aware nav (reads `rol` claim from JWT; hides admin links from non-admin roles)
- Add `/propiedades`, `/politicas` routes with `requiresAuth: true`
- `services/propiedadService.ts`, `services/politicaService.ts`

---

### Sprint 2 — Reservations API and History (T-04, T-05, T-06)
**MoSCoW:** All MUST  
**Owners:** T-04 Santiago · T-05 Laura · T-06 Laura

#### Backend — reserva package

```
reserva/
├── entity/
│   ├── Reserva.java            @Entity @Table("reserva")
│   │                           monto_total: BigDecimal
│   │                           estado: String (pendiente|confirmada|cancelada|completada|no_show)
│   └── HistorialReserva.java   @Entity @Table("historial_reserva")
├── repository/
│   ├── ReservaRepository.java           findByEstado(String, Pageable)
│   │                                    findByPropiedadId(int, Pageable)
│   └── HistorialReservaRepository.java  findByReservaIdOrderByFechaCambioDesc(int)
├── service/
│   └── ReservaService.java     crear, obtener, listar(Pageable + filtros), cambiarEstado, marcarNoShow
│                               on every state change: write HistorialReserva in same @Transactional
└── controller/ReservaController.java
    dto/ CreateReservaRequest, ReservaResponse, CambiarEstadoRequest, HistorialReservaResponse
```

**`CreateReservaRequest` validation:**
```java
@NotNull Integer propiedadId;
@NotNull Integer canalId;
@NotNull Integer politicaCancelacionId;
@NotNull @Future LocalDate fechaInicio;
@NotNull LocalDate fechaFin;        // service validates fechaFin.isAfter(fechaInicio)
@NotNull @Positive BigDecimal montoTotal;
```

**State machine (enforced in `ReservaService.cambiarEstado()`):**
```
pendiente   → confirmada
pendiente   → cancelada
confirmada  → completada
confirmada  → cancelada   (triggers PenalidadService.calcular — same @Transactional)
confirmada  → no_show     (triggers PenalidadService.calcular — same @Transactional)
```
Any invalid transition → `ResponseStatusException(422, "Transición de estado inválida")`

All list endpoints accept `?page=0&size=20` — use `findAll(Pageable)` everywhere (resolves M-07).

#### REST API — Sprint 2

| Method | Path | Role | HTTP |
|--------|------|------|------|
| POST | `/api/reservas` | AUTH | 201 |
| GET | `/api/reservas?page&size&estado&propiedadId` | AUTH | 200 |
| GET | `/api/reservas/{id}` | AUTH | 200 |
| PUT | `/api/reservas/{id}/estado` | AUTH | 200 |
| GET | `/api/reservas/{id}/historial` | AUTH | 200 |
| GET | `/api/reservas/{id}/trazabilidad` | AUTH | 200 (full wiring Sprint 5) |

#### Frontend — Sprint 2

- `views/ReservasView.vue` — filterable/paginated table, status badges, "Nueva reserva" modal
- `views/ReservaDetailView.vue` — full detail, FSM action buttons (role-gated), historial tab
- `views/TrazabilidadView.vue` (T-06) — per-reservation financial timeline (nodes populate as domain data arrives in later sprints)
- `services/reservaService.ts`
- Routes: `/reservas`, `/reservas/:id`

---

### Sprint 3 — Advances, Refunds, Penalty Engine (T-07, T-08, T-09, T-10)
**MoSCoW:** All MUST  
**Owners:** T-07 / T-08 / T-09 Julian · T-10 Santiago

#### Backend — anticipo, penalidad, devolucion packages

```
anticipo/
├── entity/Anticipo.java               @Entity @Table("anticipo")  monto: BigDecimal
│                                      estado: registrado | aplicado | devuelto
├── repository/AnticipoRepository.java findByReservaId, findByReservaIdAndEstado
│                                      sumMontoByReservaIdAndEstado (native or @Query)
├── service/AnticipoService.java        registrar, listar, aplicar
│                                       generarDevolucionAutomaticaSiCorresponde(reservaId, facturaTotal)
└── controller/AnticipoController.java

penalidad/
├── entity/Penalidad.java              @Entity @Table("penalidad")  all amounts: BigDecimal(12,2)
├── repository/PenalidadRepository.java findByReservaId
├── service/PenalidadService.java       calcular(reservaId, fechaCancelacion), aprobar(id, montoAprobado)
└── controller/PenalidadController.java

devolucion/
├── entity/Devolucion.java             @Entity @Table("devolucion")  monto: BigDecimal(12,2)
│                                      estado: pendiente | procesada | rechazada
├── repository/DevolucionRepository.java findByReservaId, findByEstado(Pageable)
├── service/DevolucionService.java       crear, procesar, rechazar
└── controller/DevolucionController.java
```

**`PenalidadService.calcular()` — penalty engine (T-10):**

1. Load `reserva` and its `politica_cancelacion_id`
2. Load all `regla_penalidad` rows for that policy
3. Compute `diasAnticipacion = ChronoUnit.DAYS.between(fechaCancelacion, reserva.fechaInicio)`
4. Find matching rule: `diasAnticipacion >= regla.diasAnticipacionMin AND (regla.diasAnticipacionMax IS NULL OR diasAnticipacion <= regla.diasAnticipacionMax)` and `temporadaAplica matches OR null`
5. Compute `montoSegunPolitica` by rule type:
   - `porcentaje`: `reserva.montoTotal.multiply(regla.valor).divide(100, 2, HALF_UP)`
   - `valor_fijo`: `regla.valor`
   - `noches`: `regla.valor.multiply(pricePerNight)`
   - `mixto`: combination per rule definition
6. Save `Penalidad` with `montoAprobado = montoSegunPolitica`, `montoCondonado = ZERO`
7. Write `log_transaccion` in same `@Transactional`

**`AnticipoService.generarDevolucionAutomaticaSiCorresponde()` — auto-refund (T-09):**
- Called by `FacturaService.emitir()` in the same transaction
- If `Σ anticipos aplicados > factura.total`: create `Devolucion` for the difference with `estado = pendiente`

#### REST API — Sprint 3

| Method | Path | Role | HTTP |
|--------|------|------|------|
| POST | `/api/reservas/{id}/anticipos` | AUTH | 201 |
| GET | `/api/reservas/{id}/anticipos` | AUTH | 200 |
| PUT | `/api/anticipos/{id}/aplicar` | CONTADOR | 200 |
| POST | `/api/reservas/{id}/penalidad` | AUTH | 201 |
| GET | `/api/penalidades?reservaId` | AUTH | 200 |
| PUT | `/api/penalidades/{id}/aprobar` | CONTADOR | 200 |
| GET | `/api/devoluciones?page&size&estado` | AUTH | 200 |
| PUT | `/api/devoluciones/{id}/procesar` | CONTADOR | 200 |

#### Frontend — Sprint 3

- `views/AnticipodView.vue` — list per reservation, register form (metodo_pago, monto, fecha_pago)
- `views/PenalidadesView.vue` — penalty detail with policy breakdown, approval form for Contador
- `views/DevolucionesView.vue` — refund tracking table with status badges
- `services/anticipoService.ts`, `services/penalidadService.ts`, `services/devolucionService.ts`

---

### Sprint 4 — Invoicing, No-Show, Bill History (T-11, T-12, T-13, T-14)
**MoSCoW:** All MUST  
**Owners:** T-11 Santiago · T-12 / T-14 Laura · T-13 Eduard

#### Backend — factura package

```
factura/
├── entity/
│   ├── Factura.java              @Entity @Table("factura")
│   │                             subtotal, descuento_anticipo, impuestos, total: BigDecimal(12,2)
│   │                             estado: borrador | emitida | anulada
│   │                             numero_factura: String (UNIQUE, generated)
│   └── NotaCredito.java          @Entity @Table("nota_credito")  monto: BigDecimal(12,2)
├── repository/
│   ├── FacturaRepository.java    findByReservaId, findByEstado(Pageable), findByNumeroFactura
│   └── NotaCreditoRepository.java findByFacturaId
├── service/
│   └── FacturaService.java       generarBorrador, emitir, anular, generarNotaCredito
└── controller/FacturaController.java
```

**`FacturaService.generarBorrador()` calculation (T-13):**
```
subtotal            = reserva.montoTotal
descuento_anticipo  = Σ anticipo.monto WHERE estado='aplicado' AND reserva_id=X
base_imponible      = subtotal - descuento_anticipo
impuestos           = base_imponible × 0.19  [BigDecimal, scale=2, HALF_UP]
total               = base_imponible + impuestos
numero_factura      = "NF-" + year + "-" + nextval('factura_seq') padded to 5 digits
```
Saved as `estado = borrador`.

**`FacturaService.emitir()` — atomic:**
```java
@Transactional
public FacturaResponse emitir(int facturaId, int usuarioId) {
    factura.setEstado("emitida");
    facturaRepository.save(factura);
    anticipoService.generarDevolucionAutomaticaSiCorresponde(factura.getReservaId(), factura.getTotal());
    logTransaccionService.registrar(usuarioId, "factura", facturaId, "generar_factura", null);
    return toResponse(factura);
}
```

**No-show (T-11):**
- `PUT /api/reservas/{id}/no-show` → `ReservaService.marcarNoShow()`:
  1. Validates `estado = confirmada`
  2. Sets `no_show = true`, `estado = no_show`
  3. Calls `penalidadService.calcular(id, LocalDate.now())` in same `@Transactional`

**Invoice number generation:**
- Add `CREATE SEQUENCE factura_seq START 1` in new migration `V3__sequences.sql`
- `FacturaService` calls `nextval('factura_seq')` via native query

#### REST API — Sprint 4

| Method | Path | Role | HTTP |
|--------|------|------|------|
| PUT | `/api/reservas/{id}/no-show` | AUTH | 200 |
| POST | `/api/reservas/{id}/factura` | CONTADOR | 201 |
| GET | `/api/facturas?page&size&estado` | AUTH | 200 |
| GET | `/api/facturas/{id}` | AUTH | 200 |
| PUT | `/api/facturas/{id}/emitir` | CONTADOR | 200 |
| PUT | `/api/facturas/{id}/anular` | CONTADOR | 200 |
| POST | `/api/facturas/{id}/nota-credito` | CONTADOR | 201 |
| GET | `/api/facturas/{id}/notas-credito` | AUTH | 200 |

#### Frontend — Sprint 4

- `views/FacturacionView.vue` — invoice list with status badges, filter by estado, "Generar factura" button per reservation
- `views/FacturaDetailView.vue` (T-12) — full breakdown: subtotal, advance discount, tax line, total; emit/void buttons; credit notes section
- `views/HistorialFacturasView.vue` (T-14) — paginated, searchable by `numero_factura` or date range

---

### Sprint 5 — Atomic Transactions, Performance, Responsive Design (T-15, T-16, T-17)
**MoSCoW:** T-15 MUST · T-16 SHOULD · T-17 SHOULD  
**Owners:** T-15 Julian · T-16 Eduard · T-17 Laura

#### Atomic transactions (T-15)

All cross-service operations must run in a single `@Transactional`. Audit every method that calls another service:

| Operation | Boundary |
|-----------|----------|
| `FacturaService.emitir()` | emitir + devolución automática + log |
| `ReservaService.cancelar()` | estado change + penalidad.calcular() + log |
| `AnticipoService.aplicar()` | estado change + log |
| `DevolucionService.procesar()` | estado change + log |

`LogTransaccionService` uses `Propagation.MANDATORY` — fails fast if called outside a transaction:

```java
@Transactional(propagation = Propagation.MANDATORY)
public void registrar(Long usuarioId, String entidad, int entidadId, String accion, Object datos) {
    // build LogTransaccion entity and save
}
```

Complete the `/api/reservas/{id}/trazabilidad` endpoint:
```json
{
  "reserva": { ... },
  "anticipos": [ ... ],
  "penalidad": { ... } | null,
  "factura": { ... } | null,
  "notasCredito": [ ... ],
  "devoluciones": [ ... ]
}
```

#### Performance (T-16)

- Add `V4__indexes.sql`: indexes on `reserva(estado)`, `reserva(propiedad_id)`, `anticipo(reserva_id)`, `factura(reserva_id)`, `log_transaccion(entidad_id, entidad_afectada)`
- HikariCP config in `application.properties`: `maximum-pool-size=10`, `minimum-idle=2`
- Use `@EntityGraph` on list queries to avoid N+1 (e.g. `ReservaRepository` fetching `propiedad`)

#### Responsive design (T-17)

- `AppSidebar.vue`: collapses to hamburger menu at `< 768px`
- `ReservaTable.vue`, `FacturacionView.vue`: horizontal scroll on small screens
- Dashboard KPI cards: `grid-template-columns: repeat(auto-fit, minmax(200px, 1fr))`

---

## 4. Database Evolution

### Migration sequence

```
V1__initial_schema.sql    — All 14 tables from Esquema_BD.sql verbatim
V2__seed_data.sql         — 4 roles, 3 canales
V3__sequences.sql         — factura_seq, nota_credito_seq
V4__indexes.sql           — Performance indexes on FK and estado columns
```

After Phase 0: `spring.jpa.hibernate.ddl-auto=validate`. Hibernate validates entities match the migrated schema — never modifies the schema again. Every schema change requires a new `V{n}__*.sql` file.

### JPA entity → table mapping reference

| Java class | Table | Monetary fields |
|---|---|---|
| `Rol` | `rol` | — |
| `Propiedad` | `propiedad` | — |
| `Canal` | `canal` | — |
| `Temporada` | `temporada` | — |
| `PoliticaCancelacion` | `politica_cancelacion` | — |
| `ReglaPenalidad` | `regla_penalidad` | `valor: BigDecimal(12,2)` |
| `Reserva` | `reserva` | `monto_total: BigDecimal` |
| `HistorialReserva` | `historial_reserva` | — |
| `Anticipo` | `anticipo` | `monto: BigDecimal(12,2)` |
| `Penalidad` | `penalidad` | `monto_segun_politica`, `monto_aprobado`, `monto_condonado` — all `BigDecimal(12,2)` |
| `Factura` | `factura` | `subtotal`, `descuento_anticipo`, `impuestos`, `total` — all `BigDecimal(12,2)` |
| `NotaCredito` | `nota_credito` | `monto: BigDecimal(12,2)` |
| `Devolucion` | `devolucion` | `monto: BigDecimal(12,2)` |
| `LogTransaccion` | `log_transaccion` | — (`datos_adicionales`: `JsonNode` via `@Type`) |

---

## 5. Security / RBAC Permission Matrix

| Endpoint group | Administrador | Contador | Auxiliar contable | Recepcionista |
|---|:---:|:---:|:---:|:---:|
| POST/PUT/DELETE `/api/propiedades` | ✓ | | | |
| POST/PUT/DELETE `/api/politicas` | ✓ | | | |
| Gestión de usuarios | ✓ | | | |
| POST/PUT `/api/reservas` | ✓ | | ✓ | ✓ |
| GET `/api/reservas/**` | ✓ | ✓ | ✓ | ✓ |
| POST `/api/reservas/{id}/anticipos` | ✓ | ✓ | ✓ | |
| PUT `/api/anticipos/{id}/aplicar` | ✓ | ✓ | | |
| POST `/api/reservas/{id}/factura` | ✓ | ✓ | | |
| PUT `/api/facturas/{id}/emitir` | ✓ | ✓ | | |
| PUT `/api/penalidades/{id}/aprobar` | ✓ | ✓ | | |
| GET (all listings) | ✓ | ✓ | ✓ | ✓ |

**Implementation:** `SecurityConfig` `.requestMatchers(...).hasAnyRole(...)` chains backed by the `"rol"` JWT claim loaded into `GrantedAuthority` by `JwtAuthenticationFilter` — no DB lookup per request.

---

## 6. Financial Traceability Flow

```
Reserva (pendiente)
    ↓ confirmada
Reserva (confirmada)
    ↓ Anticipo registrado (uno o más)
Anticipo[]: registrado → aplicado
    │
    ├─ [cancelación / no-show]
    │       ↓
    │   PenalidadService.calcular(reservaId, fechaCancelacion)
    │     → busca ReglaPenalidad por dias_anticipacion + temporada_aplica
    │     → calcula montoSegunPolitica (BigDecimal, HALF_UP)
    │     → Penalidad (pendiente aprobación Contador)
    │             ↓ Contador aprueba
    │         Penalidad: montoAprobado, montoCondonado set
    │
    ↓ FacturaService.generarBorrador()
Factura (borrador):
    subtotal           = reserva.monto_total
    descuento_anticipo = Σ anticipo.monto WHERE estado='aplicado'
    base_imponible     = subtotal - descuento_anticipo
    impuestos          = base_imponible × 0.19  [BigDecimal, scale=2, HALF_UP]
    total              = base_imponible + impuestos
    numero_factura     = "NF-{AÑO}-{SEQ:05d}"
    ↓ Contador emite (@Transactional)
Factura (emitida)
    → log_transaccion: accion='generar_factura'
    │
    ├─ [Σ anticipos aplicados > factura.total]
    │       ↓ (mismo @Transactional)
    │   Devolucion (pendiente) → Contador procesa → Devolucion (procesada)
    │
    └─ [ajuste posterior]
        → NotaCredito emitida → log_transaccion: accion='generar_nota_credito'
```

Every state transition at every entity writes one `log_transaccion` record in the same `@Transactional`. `GET /api/reservas/{id}/trazabilidad` reconstructs the complete chain from a single `reserva_id`.

---

## 7. Complete REST API Reference

### Auth
| Method | Path | HTTP |
|--------|------|------|
| POST | `/api/auth/login` | 200 |

### Users
| Method | Path | HTTP |
|--------|------|------|
| POST | `/api/usuarios` | 201 |
| GET | `/api/usuarios?page&size` | 200 |
| DELETE | `/api/usuarios/{id}` | 204 |

### Reference data
| Method | Path | HTTP |
|--------|------|------|
| GET | `/api/roles` | 200 |
| GET | `/api/canales` | 200 |
| GET / POST | `/api/temporadas` | 200 / 201 |

### Properties and policies
| Method | Path | HTTP |
|--------|------|------|
| GET / POST | `/api/propiedades` | 200 / 201 |
| GET / PUT / DELETE | `/api/propiedades/{id}` | 200 / 200 / 204 |
| GET / POST | `/api/politicas` | 200 / 201 |
| POST | `/api/politicas/{id}/reglas` | 201 |

### Reservations
| Method | Path | HTTP |
|--------|------|------|
| POST | `/api/reservas` | 201 |
| GET | `/api/reservas?page&size&estado&propiedadId` | 200 |
| GET | `/api/reservas/{id}` | 200 |
| PUT | `/api/reservas/{id}/estado` | 200 |
| PUT | `/api/reservas/{id}/no-show` | 200 |
| GET | `/api/reservas/{id}/historial` | 200 |
| GET | `/api/reservas/{id}/trazabilidad` | 200 |

### Advances
| Method | Path | HTTP |
|--------|------|------|
| POST | `/api/reservas/{id}/anticipos` | 201 |
| GET | `/api/reservas/{id}/anticipos` | 200 |
| PUT | `/api/anticipos/{id}/aplicar` | 200 |

### Penalties
| Method | Path | HTTP |
|--------|------|------|
| POST | `/api/reservas/{id}/penalidad` | 201 |
| GET | `/api/penalidades?reservaId` | 200 |
| PUT | `/api/penalidades/{id}/aprobar` | 200 |

### Invoices
| Method | Path | HTTP |
|--------|------|------|
| POST | `/api/reservas/{id}/factura` | 201 |
| GET | `/api/facturas?page&size&estado` | 200 |
| GET | `/api/facturas/{id}` | 200 |
| PUT | `/api/facturas/{id}/emitir` | 200 |
| PUT | `/api/facturas/{id}/anular` | 200 |
| POST | `/api/facturas/{id}/nota-credito` | 201 |
| GET | `/api/facturas/{id}/notas-credito` | 200 |

### Refunds
| Method | Path | HTTP |
|--------|------|------|
| GET | `/api/devoluciones?page&size&estado` | 200 |
| PUT | `/api/devoluciones/{id}/procesar` | 200 |
| PUT | `/api/devoluciones/{id}/rechazar` | 200 |

---

## 8. Frontend Target Architecture

```
src/
├── router/index.js
│   └── beforeEach — JWT guard for requiresAuth routes
├── services/
│   ├── api.ts                — Axios + Bearer interceptor + TOKEN_KEY
│   ├── authService.ts        — authenticateUser, logout
│   ├── reservaService.ts
│   ├── anticipoService.ts
│   ├── penalidadService.ts
│   ├── facturaService.ts
│   ├── devolucionService.ts
│   ├── propiedadService.ts
│   └── politicaService.ts
├── types/
│   ├── auth.ts, reserva.ts, anticipo.ts, factura.ts, ...
├── views/
│   ├── LoginView.vue          ← working
│   ├── DashboardView.vue      ← KPI cards + recent reservations + quick actions
│   ├── ReservasView.vue
│   ├── ReservaDetailView.vue
│   ├── AnticipodView.vue
│   ├── PenalidadesView.vue
│   ├── FacturacionView.vue
│   ├── FacturaDetailView.vue
│   ├── HistorialView.vue
│   ├── TrazabilidadView.vue   ← per-reservation financial timeline
│   ├── PropiedadesView.vue
│   ├── PoliticasView.vue
│   └── UsuariosView.vue       ← admin only
└── components/
    ├── AppSidebar.vue         ← role-aware nav (reads JWT rol claim)
    ├── KpiCard.vue
    ├── ReservaTable.vue
    ├── EstadoBadge.vue
    ├── MontoDisplay.vue       ← COP formatting (Intl.NumberFormat)
    └── TrazabilidadTimeline.vue
```

---

## 9. Task → Sprint Alignment

| Task | Sprint | Owner | Key deliverable |
|------|--------|-------|-----------------|
| T-03 Autenticación | Phase 0 | Eduard | RBAC wiring, rol JWT claim |
| T-01 Modelo de datos | Sprint 1 | Santiago | Flyway V1+V2, Propiedad / Canal / Temporada / Politica entities |
| T-02 Módulo de roles | Sprint 1 | Eduard | Rol entity, `GET /api/roles`, SecurityConfig rules, `AppSidebar` |
| T-04 API registro reservas | Sprint 2 | Santiago | Reserva entity/service/controller, FSM, pagination |
| T-05 Historial de reserva | Sprint 2 | Laura | HistorialReserva entity, audit trail on every state change |
| T-06 Vista trazabilidad | Sprint 2 (shell) / Sprint 5 (complete) | Laura | TrazabilidadView + `/trazabilidad` endpoint |
| T-07 Registro anticipos | Sprint 3 | Julian | Anticipo entity/service/controller |
| T-08 Anticipos vs factura | Sprint 3 + 4 | Julian | `descuento_anticipo` in Factura; `calcularTotalAplicado` |
| T-09 Devolución automática | Sprint 3 | Julian | `generarDevolucionAutomaticaSiCorresponde()` |
| T-10 Motor penalidades | Sprint 3 | Santiago | `PenalidadService.calcular()` — full `regla_penalidad` engine |
| T-11 Estado no-show | Sprint 4 | Santiago | `ReservaService.marcarNoShow()` + penalidad trigger |
| T-12 Desglose penalidad | Sprint 4 | Laura | `PenalidadController` detail + `FacturaDetailView` breakdown |
| T-13 Facturación electrónica | Sprint 4 | Eduard | `FacturaService.generarBorrador/emitir`, `numero_factura` |
| T-14 Historial facturas | Sprint 4 | Laura | `HistorialFacturasView.vue`, paginated `GET /api/facturas` |
| T-15 Transacciones atómicas | Sprint 5 | Julian | `@Transactional` audit + `LogTransaccionService` MANDATORY propagation |
| T-16 Pruebas rendimiento | Sprint 5 | Eduard | V4 indexes, HikariCP config, `@EntityGraph` on N+1 queries |
| T-17 Diseño responsive | Sprint 5 | Laura | `AppSidebar` hamburger, grid layouts, mobile tables |
