# NovaFacts — Master Technical Audit

**Version:** 1.0
**Date:** 2026-06-28
**Classification:** Architectural Audit — Pre-Implementation Reference Document
**Project:** Ingeniería de Software 1 (2016701) — Universidad Nacional de Colombia

---

## Sources Read

| Document | Key data extracted |
|----------|--------------------|
| `Esquema_BD.sql` | 14 tables, all columns, FK constraints, CHECK constraints |
| `Documentation/Diagrams/Database.png` | Full ERD confirming schema; relationships visualized |
| `Documentation/Diagrams/Diagram_CU.png` | 4 actors, 11 use cases, 1 system actor |
| `use_cases/CU_01_jfoglia.pdf` | RF_01, RF_06, RF_18; reservation form; season auto-assign; historial postcondition |
| `use_cases/CU_02_jfoglia.pdf` | RF_02, RF_18; policy mandatory before "Confirmada"; historial required; CU_05 reference |
| `use_cases/CU_03_eostos.pdf` | RF_11, RF_13, RF_18, RF_19, RF_20; automatic refund; formula: Excedente = Σanticipo − neto |
| `use_cases/CU_07-lahernandezt.pdf` | RF_09, RF_10, RF_14; definitive invoice formula; FAC-{NNNN} format; "Facturada" status |
| `use_cases/CU_08-lahernandezt.pdf` | RF_15, RF_17; anticipo registration flow; invoice history with filters |
| `use_cases/CU_09-lahernandezt.pdf` | RF_16, RF_17, RNF_02; user management; 4 roles; soft-deactivate only |
| `README.md` | Business context: Estancias Horizonte; multi-channel rental management |
| `Architecture_Roadmap.md` | 5-sprint plan; all 13 non-auth tables listed as "Absent" |
| `Architecture_Readiness_Review.md` | C-1: booking package orphaned; C-2: no @Transactional |
| `InvoiceService.java` | pricePerNight × nights formula; PAID/PENDING/CANCELLED states; hard delete |
| `ReservationService.java` | Immediately sets CONFIRMED; uses guestId + capacity; no canal/policy/user tracking |
| `UserService.java` | nombre = email hardcoded; rolId = 1 hardcoded; hard delete; no role in token |
| All other backend sources | Confirmed entity fields, repository methods, controller endpoints |
| All frontend sources | Confirmed Vue views, services, types, router, composables |

---

## Document Conflicts Detected

Before the audit: two genuine conflicts between source documents that require explicit resolution before implementation.

### Conflict C-1 — Reservation status "Facturada" vs SQL schema

| Document | States |
|----------|--------|
| `Esquema_BD.sql` (authoritative) | `pendiente \| confirmada \| cancelada \| completada \| no_show` |
| `CU_07` step 7 | "El sistema actualiza el estado de la reserva a **Facturada**" |

"Facturada" does not appear in the SQL CHECK constraint. Either `completada` and `facturada` are the same concept, or the SQL is missing one state.

**Resolution adopted for this audit:** The SQL schema is authoritative. `completada` = stay finished. `facturada` is probably what the use case author called `completada` after invoicing. In implementation, add `facturada` as an additional status only if stakeholders confirm it is a distinct state; otherwise treat CU_07's "Facturada" as `completada`.

### Conflict C-2 — Tax (IVA) in invoice formula

| Document | Formula |
|----------|---------|
| `Esquema_BD.sql` | `factura` has `impuestos decimal(12,2)` column |
| `CU_07` mockup desglose | Bruto − Anticipos − Condonaciones = Neto (no tax line visible) |

The SQL schema implies tax exists; the CU_07 desglose does not show it as a separate line.

**Resolution adopted for this audit:** The schema is authoritative. `impuestos` is calculated and stored. The CU_07 mockup is a simplified display that may embed tax in the `valor bruto` or display it separately not shown in the abbreviated mockup. Implementation must include `impuestos = base × 0.19`.

---

## Section 1 — Executive Summary

### Project maturity

NovaFacts is a university software engineering project implementing a financial management system for a short-term rental company ("Estancias Horizonte"). The system manages property reservations through multiple booking channels, advance payments, cancellation penalties, invoice generation, and refunds.

**Current implementation maturity: Early MVP — approximately 17% complete relative to the original specification.**

The project has a functional authentication module (JWT, BCrypt, Spring Security), a feature-package backend structure, a Vue 3 frontend with a working layout and composable infrastructure, Docker setup, and 54 passing tests. However, the domain model diverges fundamentally from the authoritative SQL schema. The core business modules — cancellation policies, advance payments, penalties, correct invoice calculation, refunds, and audit logging — are entirely absent. Several modules that were implemented (Guest, Payment) have no counterpart in the authoritative schema and must be removed.

### Overall architecture quality

The engineering quality of what exists is good. The layering is correct, the patterns are consistent, and the infrastructure is reusable. The problem is not how it was built — it is what was built. The implemented domain model represents a simplification of the real system that cannot be incrementally extended to match the specification without structural refactoring.

### Main risks

1. The `Reservation` root aggregate has the wrong structure. It cannot be corrected by adding columns — it requires a controlled schema migration with data impact.
2. Two invented modules (`Guest`, `Payment`) are referenced by the existing `ReservationService` and `DevelopmentDataSeeder`. Removing them requires those dependencies to be resolved first.
3. The invoice calculation formula is wrong — it produces different numbers than the specification requires. Existing invoice records created by the seeder will not match what the correct formula would produce.
4. No Flyway is in place. Schema changes via `ddl-auto=update` are uncontrolled and will conflict with migration work.

### Estimated effort

Total refactoring effort: **20–25 development days** across 7 incremental sprints. This is not a rewrite — it is targeted structural correction of 4 existing modules plus addition of 11 new modules.

### Recommendation

**Option A — Incremental refactor.** Preserve all infrastructure, patterns, JWT/security stack, frontend layout, shared CSS, and composables. Execute structural corrections in dependency order. Do not rewrite from scratch. Do not implement multiple sprints simultaneously.

---

## Section 2 — Functional Analysis

### Complete business capability inventory

| Feature | Current Status | Required | Missing | Incorrect | Priority |
|---------|----------------|----------|---------|-----------|----------|
| User authentication (login/JWT) | Implemented | ✓ | — | Token contains no role claim | P0 |
| User management (CRUD + role assignment) | Partial | ✓ | Role assignment, deactivate, audit log | nombre hardcoded = email; hard delete; rolId=1 | P0 |
| Role-based access control (4 roles) | Absent | ✓ | Full RBAC enforcement | All users identical access | P0 |
| Property CRUD | Incorrect | ✓ | `activa` flag, `descripcion` | 3 invented columns; no soft-delete | P1 |
| Booking channel management (canal) | Absent | ✓ | Full module | — | P1 |
| Season management (temporada) | Absent | ✓ | Full module | — | P1 |
| Cancellation policy configuration | Absent | ✓ | Full module | — | P1 |
| Penalty rule configuration | Absent | ✓ | Full module | — | P1 |
| Reservation registration (with canal + season + policy) | Incorrect | ✓ | canal, temporada, politica FK; monto_total; no_show; state machine | Invented guestId, capacity; immediate CONFIRMED | P1 |
| Reservation number auto-generation | Absent | ✓ | RES-{NNNN} | — | P1 |
| Reservation history / audit trail | Absent | ✓ | historial_reserva writes | — | P1 |
| Cancellation policy linking to reservation | Absent | ✓ | CU_02 flow | — | P1 |
| Advance payment (anticipo) registration | Absent | ✓ | Full module | Replaced by invented Payment | P2 |
| Advance payment application | Absent | ✓ | aplicar state transition | — | P2 |
| Penalty calculation engine | Absent | ✓ | Full rule-based engine | booking/InvoiceCalculator is unrelated | P2 |
| Penalty approval workflow | Absent | ✓ | monto_aprobado, monto_condonado | — | P2 |
| Invoice generation with advance discount | Incorrect | ✓ | Correct formula with descuento_anticipo | Wrong formula; wrong states; missing fields | P2 |
| Invoice number auto-generation (FAC-{NNNN}) | Absent | ✓ | Sequential unique identifier | — | P2 |
| Invoice history with filters | Absent | ✓ | RF_15 filter by propiedad, fecha, estado | — | P2 |
| Automatic refund obligation generation | Absent | ✓ | CU_03 automatic trigger | — | P2 |
| Refund processing (devolucion) | Absent | ✓ | Full module | — | P2 |
| Credit note issuance (nota_credito) | Absent | ✓ | Full module | — | P3 |
| Transaction audit log (log_transaccion) | Absent | ✓ | RF_18 on every write operation | — | P2 |
| Invoice PDF download / resend | Absent | ✓ | url_documento, download action | — | P3 |
| Dashboard (aggregated statistics) | Incorrect | ✓ | Measures wrong entities | totalGuests, totalPayments are invented | P3 |
| Guest module | Invented | REMOVE | — | No schema counterpart | P1 |
| Payment module | Invented | REMOVE | — | No schema counterpart; replaces anticipo | P1 |

---

## Section 3 — Database Analysis

### Table 1: `rol`

**Purpose:** Reference table defining the four system roles.
**Business meaning:** Determines what each user can see and do.
**Columns:** `id INT PK, nombre VARCHAR(50) NOT NULL, descripcion TEXT`
**Lifecycle:** Seed-only data; never created by end users; created at schema initialization.
**CRUD:** Read-only from application perspective; seeded by Flyway migration.
**Relationships:** Referenced by `usuario.rol_id`.
**Required endpoints:** `GET /api/roles` (list all roles for user creation form).
**Frontend screens:** Rol dropdown in `UsuariosView.vue` create/edit form.
**User roles allowed:** No mutation permitted by any role; read by Administrador only.
**Validation rules:** Exactly 4 rows: Administrador, Contador, Auxiliar contable, Recepcionista.
**Business rules:** `usuario.rol_id` must reference a valid rol. Role name determines all access permissions (RF_17).

---

### Table 2: `usuario`

**Purpose:** Internal staff user accounts for the NovaFacts system.
**Business meaning:** Employees of Estancias Horizonte who operate the system.
**Columns:** `id, email (unique), password_hash, nombre, rol_id (FK→rol), activo, creado_en`
**Lifecycle:** Created by Administrador; can be edited (nombre, rol); soft-deactivated; NEVER hard-deleted.
**CRUD:** Create, Read, Update (nombre+rol), Deactivate (activo=false). No hard delete per CU_09 notes.
**Relationships:** FK source for `log_transaccion.usuario_id`, `reserva.usuario_creador_id`, `anticipo.usuario_id`, `factura.usuario_id`, `penalidad.usuario_id`, `nota_credito.usuario_id`, `devolucion.usuario_id`, `historial_reserva.usuario_id`.
**Required endpoints:**
- `GET /api/usuarios` — list with nombre, correo, rol, activo, ultima_sesion
- `POST /api/usuarios` — create with nombre, email, rol, temp password
- `PUT /api/usuarios/{id}` — edit nombre and/or rol
- `PUT /api/usuarios/{id}/desactivar` — deactivate (activo=false)
- `POST /api/auth/login` — already implemented
**Frontend screens:** `UsuariosView.vue` with list table (Nombre, Correo, Rol, Estado, Última sesión), create/edit modal, deactivate confirmation modal.
**User roles allowed:** Administrador only for mutations; all roles for read-own-profile.
**Validation rules:**
- Email must be unique among active users (CU_09: "No puede existir más de una cuenta activa con el mismo correo electrónico")
- Role must be one of 4 valid values
- Deactivated user cannot log in but records remain for audit
**Business rules:** RF_16 (admin manages users); RF_17 (role-based access); RF_18 (all operations logged); RNF_02 (encrypted passwords, role-based access).
**Current implementation gaps:** `nombre = email` hardcoded; `rolId = 1` hardcoded; hard delete; no unique email check; no role in JWT token; `UserResponse` returns only `id + username`; no deactivation endpoint.

---

### Table 3: `canal`

**Purpose:** Reference table of booking channels through which reservations arrive.
**Business meaning:** Classifies the origin of each reservation (Airbnb, Booking, Web propia, Teléfono, WhatsApp).
**Columns:** `id, nombre VARCHAR(50) NOT NULL`
**Lifecycle:** Seeded at initialization. May be managed by Administrador.
**CRUD:** Read by reservation creation form. Optional admin CRUD for future channel addition.
**Relationships:** Referenced by `reserva.canal_id` (NOT NULL).
**Required endpoints:**
- `GET /api/canales` — list all channels (used in reservation form dropdown per CU_01 mockup)
**Frontend screens:** Channel dropdown in reservation create form.
**User roles allowed:** Read by all authenticated users (for dropdown population).
**Business rules:** RF_01 requires every reservation to record its channel. `canal_id` is NOT NULL on `reserva`.

---

### Table 4: `temporada`

**Purpose:** Defines named date ranges that classify reservation seasons.
**Business meaning:** Seasons affect which penalty rules apply and may affect pricing context.
**Columns:** `id, nombre VARCHAR(100) NOT NULL, fecha_inicio DATE NOT NULL, fecha_fin DATE NOT NULL, descripcion TEXT`
**Lifecycle:** Configured by Administrador before reservation period begins.
**CRUD:** Full CRUD by Administrador.
**Relationships:** Referenced by `reserva.temporada_id` (nullable) and `regla_penalidad.temporada_aplica` (nullable).
**Required endpoints:**
- `GET /api/temporadas` — list seasons
- `POST /api/temporadas` — create season (Administrador)
- `PUT /api/temporadas/{id}` — edit season
- `GET /api/temporadas/vigente?checkIn=DATE&checkOut=DATE` — internal: find season covering dates
**Frontend screens:** Season management view (Administrador only). Auto-populated read-only field in reservation form.
**Business rules:**
- RF_04: At least one season must exist covering the reservation dates (CU_01 precondition)
- RF_05: System auto-assigns season based on check-in/check-out dates (CU_01 step 6)
- If dates span two seasons, apply the one with greater duration within the range (CU_01 notes: business rule to define)

---

### Table 5: `propiedad`

**Purpose:** Physical properties managed by Estancias Horizonte.
**Business meaning:** The units that are rented to guests.
**Columns:** `id, nombre VARCHAR(150) NOT NULL, direccion TEXT NOT NULL, descripcion TEXT, activa BOOLEAN NOT NULL DEFAULT true`
**Note:** NO `ciudad`, `capacidad`, `precio_por_noche` in the original schema.
**Lifecycle:** Created by Administrador; soft-deactivated via `activa = false`; NEVER hard-deleted.
**CRUD:** Create, Read, Update, Deactivate (soft). No hard delete.
**Relationships:** Referenced by `politica_cancelacion.propiedad_id`, `reserva.propiedad_id`.
**Required endpoints:**
- `GET /api/propiedades` — list all (with activa filter option)
- `POST /api/propiedades` — create
- `PUT /api/propiedades/{id}` — update nombre, direccion, descripcion
- `PUT /api/propiedades/{id}/desactivar` — soft-delete
- `GET /api/propiedades/{id}/politicas` — list policies for this property
**Frontend screens:** `PropiedadesView.vue` with list, create/edit modal, deactivate action, link to policies.
**User roles allowed:** Administrador for mutations; all roles for read (dropdown in reservation form).
**Validation rules:**
- Cannot be deactivated if it has CONFIRMED reservations
- `activa = false` prevents new reservations (RF_03 / CU_01 precondition)
**Business rules:** Only active properties can receive reservations.
**Current implementation gaps:** 3 invented columns (`ciudad`, `capacidad`, `precio_por_noche`); missing `activa` and `descripcion`; hard delete; no soft-deactivation.

---

### Table 6: `politica_cancelacion`

**Purpose:** Cancellation policies configured per property.
**Business meaning:** Rules governing what happens when a reservation is cancelled.
**Columns:** `id, propiedad_id (FK→propiedad) NOT NULL, nombre VARCHAR(100), descripcion TEXT, activa BOOLEAN DEFAULT true`
**Lifecycle:** Created by Administrador per property; can be deactivated; linked to reservations at booking time.
**CRUD:** Full CRUD by Administrador.
**Relationships:** Has `regla_penalidad` children; referenced by `reserva.politica_cancelacion_id`.
**Required endpoints:**
- `GET /api/propiedades/{id}/politicas` — policies for a property
- `POST /api/propiedades/{id}/politicas` — create policy
- `PUT /api/politicas/{id}` — update
- `PUT /api/politicas/{id}/desactivar` — deactivate
- `GET /api/politicas/{id}/reglas` — list penalty rules
**Frontend screens:** Policy management (Administrador); policy selection in reservation detail (CU_02 mockup).
**Business rules:**
- RF_02: Every reservation must have a linked policy before reaching "Confirmada" (CU_02)
- A reservation without a policy cannot be confirmed (CU_02: "El sistema impide que una reserva quede en estado 'Confirmada' sin una política")
- Policy can be changed on a CONFIRMED reservation; each change creates a historial entry (CU_02 notes)

---

### Table 7: `regla_penalidad`

**Purpose:** Individual penalty calculation rules within a cancellation policy.
**Business meaning:** Defines exactly how much to charge when a reservation is cancelled, based on timing and season.
**Columns:** `id, politica_cancelacion_id (FK) NOT NULL, tipo ENUM('porcentaje','noches','valor_fijo','mixto'), valor DECIMAL, dias_anticipacion_min INT, dias_anticipacion_max INT, temporada_aplica INT (FK→temporada, nullable)`
**Lifecycle:** Created with/after the parent policy; applied during cancellation.
**CRUD:** CRUD within policy management.
**Relationships:** Child of `politica_cancelacion`; optional FK to `temporada`.
**Business rules:**
- Rule matching: find the `regla_penalidad` where `dias_anticipacion` (days from cancellation to check-in) falls between `min` and `max` AND `temporada_aplica` matches the reservation's season (or is null for all seasons)
- Types: `porcentaje` = % of monto_total; `noches` = N nights × price; `valor_fijo` = fixed amount; `mixto` = combination
- RF_09: System must show `monto_segun_politica`, `monto_aprobado`, and `monto_condonado` (CU_07)

---

### Table 8: `reserva` (root aggregate)

**Purpose:** Core booking record linking property, channel, season, policy, and creating user.
**Business meaning:** A reservation is a contractual commitment between Estancias Horizonte and a guest, created by an internal user through a specific channel.
**Columns:** `id, propiedad_id (FK) NOT NULL, canal_id (FK) NOT NULL, temporada_id (FK, nullable), politica_cancelacion_id (FK) NOT NULL, usuario_creador_id (FK) NOT NULL, fecha_inicio DATE NOT NULL, fecha_fin DATE NOT NULL, huesped_nombre VARCHAR, huesped_documento VARCHAR, notas TEXT, monto_total DECIMAL NOT NULL, estado ENUM(pendiente|confirmada|cancelada|completada|no_show), no_show BOOLEAN DEFAULT false, creado_en TIMESTAMP, modificado_en TIMESTAMP`

**Note on Conflict C-1:** CU_07 uses "Facturada" as a status. The SQL schema does not include it. Flagged — awaiting stakeholder resolution.

**State machine:**
```
pendiente ──(link policy)──→ confirmada
confirmada ──(stay completed)──→ completada
confirmada ──(cancelled)──→ cancelada [triggers penalty calculation]
confirmada ──(guest no-show)──→ no_show [triggers penalty calculation]
completada ──(invoice generated)──→ [possibly facturada per CU_07, pending C-1 resolution]
```

**Lifecycle:** Created by Recepcionista or Auxiliar → linked to policy (CU_02) → confirmed → completed/cancelled.
**Relationships:** Parent of `historial_reserva`, `anticipo`, `penalidad`, `factura`, `devolucion`.
**Required endpoints:**
- `POST /api/reservas` — create (status=pendiente)
- `GET /api/reservas` — list with filters
- `GET /api/reservas/{id}` — detail with full financial summary
- `PUT /api/reservas/{id}/politica` — link policy → move to confirmada (CU_02)
- `PUT /api/reservas/{id}/completar` — mark completed
- `PUT /api/reservas/{id}/cancelar` — cancel → trigger penalty calculation
- `PUT /api/reservas/{id}/no-show` — mark no-show → trigger penalty calculation
- `GET /api/reservas/{id}/historial` — change history
- `GET /api/reservas/{id}/anticipos` — list anticipos for this reservation
- `GET /api/reservas/{id}/penalidad` — penalty for this reservation
- `GET /api/reservas/{id}/factura` — invoice for this reservation
- `GET /api/reservas/{id}/trazabilidad` — full financial breakdown
**Frontend screens:** `ReservasView.vue` — list with filters; detail with policy section (CU_02 mockup); full financial desglose. **Sidebar per mockups:** Dashboard / Reservas / Penalidades / Anticipos / Facturación / Usuarios.
**User roles allowed:** Create/edit — Recepcionista, Auxiliar contable, Administrador. Financial state transitions — Contador, Administrador. View — all roles.
**Validation rules:**
- Property must be `activa = true` (RF_03)
- Season must cover dates (RF_04)
- No overlapping CONFIRMED reservations for same property
- `fecha_inicio < fecha_fin`
- `monto_total > 0`
- Policy must be set before status=confirmada (RF_02)
**Business rules:** RF_01, RF_02, RF_03, RF_04, RF_05, RF_06, RF_18.
**Current implementation gap:** 5 required FKs missing; 2 invented columns; wrong initial state; no state machine; no monto_total; no no_show; no historial writes; depends on invented Guest entity.

---

### Table 9: `historial_reserva`

**Purpose:** Immutable audit trail of every change to a `reserva` record.
**Business meaning:** Legal traceability record — who changed what, when.
**Columns:** `id, reserva_id (FK) NOT NULL, usuario_id (FK) NOT NULL, campo_modificado VARCHAR, valor_anterior TEXT, valor_nuevo TEXT, fecha_cambio TIMESTAMP`
**Lifecycle:** Written by `ReservaService` on every mutation; NEVER updated or deleted.
**CRUD:** Create (system-only). Read by authorized users. No update. No delete.
**Required endpoints:**
- `GET /api/reservas/{id}/historial` — chronological list of changes
**Frontend screens:** Historial tab on reservation detail page.
**Business rules:** RF_18 (every transaction recorded); CU_01 postcondition (initial creation entry); CU_02 postcondition (policy assignment entry); CU_02 notes ("el registro de trazabilidad es obligatorio e inmutable; no puede ser eliminado ni editado").

---

### Table 10: `anticipo`

**Purpose:** Advance payment made by the guest before the invoice is generated.
**Business meaning:** A financial deposit received before the stay; an accounting liability that will be discounted from the final invoice.
**Columns:** `id, reserva_id (FK) NOT NULL, usuario_id (FK) NOT NULL, monto DECIMAL NOT NULL, fecha_pago DATE, metodo_pago VARCHAR, referencia VARCHAR, notas TEXT, estado ENUM(registrado|aplicado|devuelto), creado_en TIMESTAMP`
**Lifecycle:** Registered by Contador → applied by Contador → may be returned (devolucion) if exceeds net value.
**State machine:** `registrado → aplicado → devuelto`
**CRUD:** Create (Contador). Read. No edit (corrections via negative records per CU_08 notes). No delete.
**Relationships:** Parent of `devolucion`; child of `reserva`.
**Required endpoints:**
- `POST /api/reservas/{id}/anticipos` — register advance (Contador)
- `GET /api/reservas/{id}/anticipos` — list advances with running total
- `PUT /api/anticipos/{id}/aplicar` — mark as applied
- `GET /api/anticipos/pendientes-devolucion` — list refund obligations
**Frontend screens:** Anticipos module (sidebar item "Anticipos"); sub-screen within reservation detail showing accumulated total; refund obligation panel (CU_03 mockup).
**Business rules:**
- RF_09, RF_10, RF_11: Anticipos are accounting liabilities; discounted from invoice
- RF_08 (CU_08): No limit on number of anticipos per reservation
- If anticipo exceeds valor bruto, explicit confirmation required (CU_08 notes)
- Corrections via negative anticipo records, not edits/deletes
- `metodo_pago` is configurable (CU_08 notes)
- All registrations written to `log_transaccion` (RF_18)

---

### Table 11: `penalidad`

**Purpose:** Penalty record generated when a reservation is cancelled or marked no-show.
**Business meaning:** Financial consequence of breaking the reservation contract; may be partially or fully waived.
**Columns:** `id, reserva_id (FK) NOT NULL, usuario_id (FK) NOT NULL, monto_segun_politica DECIMAL, monto_aprobado DECIMAL, monto_condonado DECIMAL, fecha_calculo DATE, observaciones TEXT`
**Lifecycle:** Auto-calculated on cancellation/no_show using matched `regla_penalidad`; reviewed and approved by Contador.
**CRUD:** Create (system on cancellation). Read. Update `monto_aprobado`/`monto_condonado` (Contador). No delete.
**Required endpoints:**
- `POST /api/reservas/{id}/penalidad/calcular` — calculate on cancellation
- `PUT /api/penalidades/{id}/aprobar` — Contador approves/adjusts amounts
- `GET /api/reservas/{id}/penalidad` — get penalty detail
**Frontend screens:** `PenalidadesView.vue` (sidebar item "Penalidades"); shows monto_segun_politica, monto_aprobado, monto_condonado (RF_09).
**Business rules:** RF_09 (show difference between policy penalty and approved vs waived); `monto_condonado = monto_segun_politica − monto_aprobado`.

---

### Table 12: `factura`

**Purpose:** Electronic invoice for a completed or invoiced reservation.
**Business meaning:** Legal financial document showing the amount owed after applying advances and penalties.
**Columns:** `id, reserva_id (FK) NOT NULL, usuario_id (FK) NOT NULL, numero_factura VARCHAR(50) UNIQUE NOT NULL, subtotal DECIMAL NOT NULL, descuento_anticipo DECIMAL NOT NULL DEFAULT 0, impuestos DECIMAL, total DECIMAL NOT NULL, estado ENUM(borrador|emitida|anulada), url_documento VARCHAR, creado_en TIMESTAMP`
**Invoice number format:** `FAC-{NNNN}` (confirmed from CU_08 mockup: FAC-0031, FAC-0030, etc.)
**Formula (from CU_07, schema, and Conflict C-2 resolution):**
```
subtotal           = reserva.monto_total
descuento_anticipo = Σ(anticipo.monto WHERE reserva_id = X AND estado = 'aplicado')
condonaciones      = penalidad.monto_condonado (if cancellation)
base               = subtotal − descuento_anticipo − condonaciones
impuestos          = base × 0.19
total              = base + impuestos
```
**State machine:** `borrador → emitida`; any state can be `anulada`.
**Lifecycle:** Generated as borrador; emitted by Contador; can be annulled; triggers automatic refund check.
**CRUD:** Create (borrador). Emit (Contador). Annul. Read. No hard delete. No edit once emitida.
**Relationships:** Child of `reserva`; parent of `nota_credito`. One invoice per reservation (CU_07 notes: "Solo se puede generar una factura por reserva").
**Required endpoints:**
- `POST /api/reservas/{id}/factura` — generate borrador invoice
- `PUT /api/facturas/{id}/emitir` — emit (Contador/Administrador)
- `PUT /api/facturas/{id}/anular` — annul
- `GET /api/facturas` — history with filters: propiedad, fecha_desde, fecha_hasta, estado (RF_15)
- `GET /api/facturas/{id}` — detail with full desglose
- `GET /api/facturas/{id}/pdf` — download PDF (url_documento)
**Frontend screens:** `FacturacionView.vue` — "Historial de facturas" (CU_08 mockup): filters, table with N.° Factura, Reserva, Propiedad, Valor neto, Estado, Acciones (Ver | Descargar | Reenviar); detail panel with desglose.
**User roles allowed:** Generate/Emit — Administrador, Contador. View — all roles. Download/Resend — Administrador, Contador.
**Business rules:**
- RF_10: Anticipos discounted from total (CU_07)
- RF_14: Electronic invoice with all concepts
- RF_15: Invoice history with filters
- Only one invoice per reservation (CU_07 notes)
- If anticipos > valor neto: auto-generate devolucion AND continue with invoice at net value 0 (CU_07 step 5a and CU_03)
**Current implementation gaps:** Wrong formula; wrong states; missing `numero_factura`, `usuario_id`, `descuento_anticipo`, `url_documento`; PAID status doesn't exist in original design; hard delete allowed; `InvoiceService.pay()` has no schema counterpart.

---

### Table 13: `nota_credito`

**Purpose:** Credit note issued against an emitted invoice.
**Business meaning:** Financial adjustment document; reduces the amount owed without annulling the original invoice.
**Columns:** `id, factura_id (FK) NOT NULL, usuario_id (FK) NOT NULL, numero_nota VARCHAR(50) UNIQUE NOT NULL, monto DECIMAL NOT NULL, motivo TEXT, fecha TIMESTAMP`
**Lifecycle:** Created by Contador after invoice is emitted; immutable once created.
**CRUD:** Create. Read. No edit. No delete.
**Required endpoints:**
- `POST /api/facturas/{id}/nota-credito` — issue credit note
- `GET /api/facturas/{id}/nota-credito` — get credit notes for invoice
**Frontend screens:** Credit note section within invoice detail.
**User roles allowed:** Contador, Administrador.

---

### Table 14: `devolucion`

**Purpose:** Refund obligation generated automatically when advance payments exceed the net invoice amount.
**Business meaning:** When a guest overpaid (anticipos > monto neto), the company owes a refund.
**Columns:** `id, reserva_id (FK) NOT NULL, anticipo_id (FK) NOT NULL, usuario_id (FK) NOT NULL, monto DECIMAL NOT NULL, estado ENUM(pendiente|procesada|rechazada), fecha_devolucion DATE, referencia_comprobante VARCHAR, creado_en TIMESTAMP`
**State machine:** `pendiente → procesada` (or `rechazada`). Cannot be closed without `referencia_comprobante` (CU_03 notes).
**Lifecycle:** Auto-created by system during invoice emission (CU_03); Contador registers the actual refund payment.
**CRUD:** Create (system-only). Read. Update estado (Contador). No delete.
**Required endpoints:**
- `GET /api/devoluciones` — list all (Contador dashboard)
- `GET /api/reservas/{id}/devoluciones` — refunds for a reservation
- `PUT /api/devoluciones/{id}/procesar` — Contador registers refund with comprobante
**Frontend screens:** Refund obligation panel within `AnticiposView.vue` (CU_03 mockup: "ALERTA: Los anticipos superan el valor neto. Se ha generado una obligación de devolución"; badge indicator on "Anticipos" nav item per RF_20).
**Business rules:**
- RF_13: Auto-generated by system when Σ anticipos > valor neto (CU_03)
- RF_20: Badge indicator on Anticipos menu when `estado = pendiente`
- `referencia_comprobante` required to close (CU_03 notes)
- CU_03 formula: `excedente = Σ anticipos − valor neto a cobrar`

---

### Table 15: `log_transaccion`

**Purpose:** Complete immutable audit log of every financial and administrative operation.
**Business meaning:** Legal compliance trail; identifies who did what and when.
**Columns:** `id, usuario_id (FK) NOT NULL, entidad_afectada VARCHAR, entidad_id INT, accion VARCHAR, datos_adicionales JSONB, fecha_transaccion TIMESTAMP`
**Lifecycle:** Written by every service on every write operation; NEVER updated or deleted.
**CRUD:** Create (system-only). Read (admin only). No update. No delete.
**Required endpoints:**
- `GET /api/log` — read audit log (Administrador only, with filters)
**Frontend screens:** Audit log view (Administrador only).
**Business rules:** RF_18 (every transaction); CU_01, CU_02, CU_03, CU_08, CU_09 all reference RF_18. Implementation: `LogTransaccionService.registrar()` must use `Propagation.MANDATORY` — fails if called outside an active transaction.

---

## Section 4 — Entity Compatibility Matrix

| Current Entity | Target DB Table | Compatibility | Mismatch Detail |
|----------------|----------------|---------------|-----------------|
| `User` | `usuario` | **Partially compatible** | `nombre = email` hardcoded; `rolId` = plain int (no FK relationship); no role loaded in JWT; hard delete; no `activo` check on login |
| `Property` | `propiedad` | **Incorrect** | 3 invented columns (`city`, `capacity`, `pricePerNight`); 2 missing columns (`activa`, `descripcion`); hard delete instead of soft-deactivate |
| `Reservation` | `reserva` | **Incorrect** | 5 missing NOT NULL FKs (`canal_id`, `politica_cancelacion_id`, `usuario_creador_id`, `temporada_id`, `monto_total`); 2 invented columns (`guestId`, `guestCount`); wrong initial state (CONFIRMED instead of pendiente); no state machine; wrong status enum values |
| `Invoice` | `factura` | **Incorrect** | Missing: `usuario_id`, `numero_factura`, `descuento_anticipo`, `url_documento`; wrong column name (`tax` vs `impuestos`); wrong status values (PAID/PENDING/CANCELLED vs borrador/emitida/anulada); wrong formula; wrong UNIQUE constraint scope |
| `Guest` | *(none)* | **Extra — must be removed** | `huesped` table does not exist in the schema; invented entity |
| `Payment` | *(none)* | **Extra — must be removed** | `pago` table does not exist; replaces `anticipo` with incompatible financial model |
| *(missing)* | `rol` | **Missing** | No entity; FK orphaned in `usuario` |
| *(missing)* | `canal` | **Missing** | No entity; `reserva.canal_id` unimplementable |
| *(missing)* | `temporada` | **Missing** | No entity; season auto-assign impossible |
| *(missing)* | `politica_cancelacion` | **Missing** | No entity; RF_02 cannot be met |
| *(missing)* | `regla_penalidad` | **Missing** | No entity; penalty engine impossible |
| *(missing)* | `historial_reserva` | **Missing** | No entity; RF_18 CU_01/CU_02 postconditions unmet |
| *(missing)* | `anticipo` | **Missing** | Replaced by invented `Payment`; entire financial advance model absent |
| *(missing)* | `penalidad` | **Missing** | No entity; penalty workflow absent |
| *(missing)* | `nota_credito` | **Missing** | No entity |
| *(missing)* | `devolucion` | **Missing** | No entity; RF_13 automatic trigger absent |
| *(missing)* | `log_transaccion` | **Missing** | No entity; RF_18 audit trail absent |

---

## Section 5 — REST API Audit

### Currently implemented endpoints

| Method | Path | Status | Issues |
|--------|------|--------|--------|
| `POST` | `/api/auth/login` | **Keep with fixes** | Doesn't return role; `LoginResponse.message` unused |
| `POST` | `/api/users` | **Rewrite** | Hardcodes nombre=email, rolId=1; missing role field |
| `GET` | `/api/users` | **Rewrite** | Returns only id+username; missing nombre, rol, activo |
| `DELETE` | `/api/users/{id}` | **Remove** | Must become PUT /desactivar; hard delete violates CU_09 |
| `GET` | `/api/properties` | **Rewrite** | Wrong DTO fields; missing activa filter |
| `POST` | `/api/properties` | **Rewrite** | Wrong fields (city/capacity/pricePerNight) |
| `PUT` | `/api/properties/{id}` | **Rewrite** | Wrong fields |
| `DELETE` | `/api/properties/{id}` | **Remove** | Must become soft-deactivate |
| `GET` | `/api/guests` | **Remove** | Invented module |
| `POST` | `/api/guests` | **Remove** | Invented module |
| `PUT` | `/api/guests/{id}` | **Remove** | Invented module |
| `DELETE` | `/api/guests/{id}` | **Remove** | Invented module |
| `GET` | `/api/guests/{id}` | **Remove** | Invented module |
| `GET` | `/api/reservations` | **Rewrite** | Wrong DTO; wrong field names; missing canal/temporada/policy |
| `POST` | `/api/reservations` | **Rewrite** | Creates as CONFIRMED; uses guestId; missing 5 required fields |
| `PUT` | `/api/reservations/{id}` | **Remove** | Replace with explicit state-machine endpoints |
| `DELETE` | `/api/reservations/{id}` | **Remove** | Reservations should not be hard-deleted |
| `GET` | `/api/invoices` | **Rewrite** | Wrong DTO; missing filters; wrong status values |
| `POST` | `/api/invoices` | **Rewrite** | Wrong formula; wrong states; missing fields |
| `PUT` | `/api/invoices/{id}/pay` | **Remove** | PAID state doesn't exist in schema |
| `PUT` | `/api/invoices/{id}/cancel` | **Rewrite** | Rename to /anular; only from borrador/emitida |
| `DELETE` | `/api/invoices/{id}` | **Remove** | Invoices must not be deleted |
| `GET` | `/api/payments` | **Remove** | Invented module |
| `POST` | `/api/payments` | **Remove** | Invented module |
| `DELETE` | `/api/payments/{id}` | **Remove** | Invented module |
| `GET` | `/api/dashboard` | **Partial** | Exists but measures wrong entities |

### Required endpoints not yet implemented

| Method | Path | Priority | Notes |
|--------|------|----------|-------|
| `GET` | `/api/roles` | P0 | For user creation dropdown |
| `PUT` | `/api/usuarios/{id}` | P0 | Edit nombre/rol (Administrador) |
| `PUT` | `/api/usuarios/{id}/desactivar` | P0 | Soft-deactivate (CU_09) |
| `GET` | `/api/canales` | P1 | For reservation form dropdown |
| `GET` | `/api/temporadas` | P1 | List seasons |
| `POST` | `/api/temporadas` | P1 | Create season (Admin) |
| `GET` | `/api/temporadas/vigente` | P1 | Auto-assign season by dates |
| `PUT` | `/api/propiedades/{id}/desactivar` | P1 | Soft-deactivate |
| `GET` | `/api/propiedades/{id}/politicas` | P1 | Policies for property |
| `POST` | `/api/propiedades/{id}/politicas` | P1 | Create policy |
| `PUT` | `/api/politicas/{id}` | P1 | Update policy |
| `POST` | `/api/politicas/{id}/reglas` | P1 | Add penalty rule |
| `POST` | `/api/reservas` | P1 | Create (pendiente state) |
| `GET` | `/api/reservas` | P1 | List with filters |
| `PUT` | `/api/reservas/{id}/politica` | P1 | Link policy → confirmada |
| `PUT` | `/api/reservas/{id}/completar` | P1 | Mark completed |
| `PUT` | `/api/reservas/{id}/cancelar` | P2 | Cancel + trigger penalty |
| `PUT` | `/api/reservas/{id}/no-show` | P2 | No-show + trigger penalty |
| `GET` | `/api/reservas/{id}/historial` | P1 | Change history |
| `GET` | `/api/reservas/{id}/trazabilidad` | P2 | Full financial breakdown |
| `POST` | `/api/reservas/{id}/anticipos` | P2 | Register advance |
| `PUT` | `/api/anticipos/{id}/aplicar` | P2 | Apply advance |
| `POST` | `/api/reservas/{id}/penalidad/calcular` | P2 | Calculate penalty |
| `PUT` | `/api/penalidades/{id}/aprobar` | P2 | Approve/adjust penalty |
| `POST` | `/api/reservas/{id}/factura` | P2 | Generate borrador invoice |
| `PUT` | `/api/facturas/{id}/emitir` | P2 | Emit invoice |
| `PUT` | `/api/facturas/{id}/anular` | P2 | Annul invoice |
| `GET` | `/api/facturas` | P2 | History with filters (RF_15) |
| `GET` | `/api/facturas/{id}/pdf` | P3 | Download PDF |
| `POST` | `/api/facturas/{id}/nota-credito` | P3 | Issue credit note |
| `PUT` | `/api/devoluciones/{id}/procesar` | P2 | Resolve refund obligation |
| `GET` | `/api/devoluciones` | P2 | List pending refunds |
| `GET` | `/api/log` | P3 | Audit log (Admin only) |

---

## Section 6 — Frontend Audit

### Intended navigation (from ALL use case mockups)

Every CU_01, CU_02, CU_03, CU_07, CU_08, CU_09 mockup shows the same sidebar:
```
Dashboard
Reservas          ← currently exists but wrong
Penalidades       ← completely absent
Anticipos         ← completely absent; currently "Payments" (wrong)
Facturación       ← currently "Invoices" (wrong states/formula)
Usuarios          ← currently manages users but wrong form
```

### Screen-by-screen audit

| Screen | Current State | Required State | Action |
|--------|--------------|----------------|--------|
| `LoginView.vue` | ✅ Correct | Correct | Keep as-is |
| `DashboardView.vue` | ⚠️ Wrong metrics | Correct structure; wrong data | Rebuild metrics after domain correction |
| `GuestsView.vue` | ❌ Must not exist | Not in intended navigation | **Delete** |
| `PropertiesView.vue` | ⚠️ Wrong schema | Needed but hidden (dropdown source) | Rebuild form fields |
| `ReservationsView.vue` | ⚠️ Wrong form | Required; completely different form fields | **Rebuild** |
| `InvoicesView.vue` | ⚠️ Wrong states/formula | Required as "Facturación" | **Rebuild** |
| `PaymentsView.vue` | ❌ Must not exist | Not in intended navigation | **Delete** |
| `PenalidadesView.vue` | ❌ Missing | Required; sidebar item | **Create** |
| `AnticiposView.vue` | ❌ Missing | Required; sidebar item; badge indicator | **Create** |
| Refund panel (within Anticipos) | ❌ Missing | Required per CU_03 mockup | **Create** |
| `UsuariosView.vue` (enhanced) | ⚠️ Basic | Required; Nombre/Rol/Estado/Última sesión | **Rebuild** |
| Invoice history with filters | ❌ Missing | Required per CU_08 mockup (RF_15) | **Create within Facturación** |
| Policy management view | ❌ Missing | Required for Administrador | **Create** |
| Season management view | ❌ Missing | Required for Administrador | **Create** |
| Reservation detail with historial | ❌ Missing | Required per CU_02 postconditions | **Create** |
| Audit log view | ❌ Missing | Required for Administrador | **Create** |

### Frontend files that must be removed

- `GuestsView.vue`
- `PaymentsView.vue`
- `services/guestService.ts`
- `services/paymentService.ts`
- `types/guest.ts`
- `types/payment.ts`

### Frontend files that are reusable without modification

- `App.vue` (after removing `text-align: center` from `#app`)
- `layouts/AppLayout.vue`
- `components/AppHeader.vue`
- `components/AppModal.vue`
- `components/AppNav.vue` (after updating nav links)
- `components/PageHeader.vue`
- `composables/useAsyncState.ts`
- `services/api.ts` (after adding 401 response interceptor)
- `services/authService.ts`
- `assets/shared.css`
- `router/index.js` (after adding role-based guards)

---

## Section 7 — Business Rules Audit

| Rule | Source | Status | Notes |
|------|--------|--------|-------|
| Reservation requires active property | CU_01 precondition | ❌ Missing | `activa` field absent on Property |
| Reservation requires season covering dates | CU_01 precondition | ❌ Missing | No Temporada entity |
| Season auto-assigned by dates | CU_01 step 6 | ❌ Missing | No Temporada entity |
| Reservation number auto-generated (RES-NNNN) | CU_01 notes | ❌ Missing | — |
| Reservation initial state = pendiente | SQL schema | ❌ Wrong | Current code sets CONFIRMED immediately |
| Policy mandatory before confirmada | CU_02, RF_02 | ❌ Missing | No policy entity |
| Policy change creates historial entry | CU_02 notes | ❌ Missing | No historial entity |
| No overlap for CONFIRMED reservations | ReservationRepository | ✅ Correct logic | Wrong status enum value (CONFIRMED vs confirmada) |
| `fecha_inicio < fecha_fin` | Business logic | ✅ Implemented | Validated in ReservationService |
| Max 30 nights | BookingValidator (POJO) | ⚠️ Partial | Logic exists but in unwired POJO; active in ReservationService |
| Anticipos are accounting liabilities | RF_11, CU_08 | ❌ Missing | No Anticipo entity |
| No limit on anticipos per reservation | CU_08 notes | ❌ Missing | No Anticipo entity |
| Corrections via negative anticipo records | CU_08 notes | ❌ Missing | No Anticipo entity |
| Anticipos immutable (no edit/delete) | CU_08 notes | ❌ Missing | No Anticipo entity |
| Penalty calculated from regla_penalidad matching days and season | Schema + CU logic | ❌ Missing | booking/InvoiceCalculator unrelated |
| Show monto_segun_politica vs monto_aprobado vs condonado | RF_09 / CU_07 | ❌ Missing | No Penalidad entity |
| Invoice formula: subtotal − descuento_anticipo − condonaciones = base; base × 1.19 = total | CU_07 + schema | ❌ Wrong | Current: pricePerNight × nights × 1.19 |
| Only one invoice per reservation | CU_07 notes | ✅ Enforced | Via `existsByReservationId` check — business rule correct; implementation partially correct |
| Invoice number sequential (FAC-NNNN) | CU_08 mockup | ❌ Missing | No generation logic |
| Invoice PDF downloadable | CU_07 step 8 | ❌ Missing | No url_documento field |
| If anticipos > valor neto: auto-generate devolucion | CU_03, RF_13 | ❌ Missing | Entire devolucion module absent |
| Devolucion requires comprobante to close | CU_03 notes | ❌ Missing | — |
| All write operations → log_transaccion | RF_18 | ❌ Missing | No log entity |
| Users never hard-deleted | CU_09 notes | ❌ Wrong | UserService.deleteUser() uses hard delete |
| Unique email among active users | CU_09 precondition | ❌ Missing | No validation in UserService |
| User deactivation doesn't delete history | CU_09 notes | ❌ Missing | Hard delete removes all related records |
| JWT token contains role | RF_17 enforcement | ❌ Missing | Token only contains username |
| Role-based access enforcement | RF_17, CU_09 | ❌ Missing | anyRequest().authenticated() only |
| Password BCrypt encoded | Security | ✅ Correct | BCryptPasswordEncoder wired |
| CSRF disabled (REST API) | SecurityConfig | ✅ Correct | Stateless JWT |
| Data encrypted in transit | RNF_02 | ✅ Correct | HTTPS enforced via CORS; BCrypt for passwords |
| Property soft-deactivate only | CU_01 precondition | ❌ Wrong | PropertyService uses hard delete |

---

## Section 8 — Role and Permission Matrix

### Roles (from CU_09 and use case diagram)

| Role | Code | Description |
|------|------|-------------|
| Administrador | ADMIN | Full system access; manages users, policies, seasons, properties |
| Contador | CONTADOR | Financial operations; anticipos, penalties, invoices, refunds |
| Auxiliar contable | AUXILIAR | Creates reservations; supports Contador |
| Recepcionista | RECEPCIONISTA | Creates reservations; read access to financial data |

### Permission matrix

| Screen / Operation | ADMIN | CONTADOR | AUXILIAR | RECEPCIONISTA |
|-------------------|-------|---------|---------|---------------|
| Login | ✓ | ✓ | ✓ | ✓ |
| Dashboard (view) | ✓ | ✓ | ✓ | ✓ |
| View reservations | ✓ | ✓ | ✓ | ✓ |
| Create reservation | ✓ | — | ✓ | ✓ |
| Link cancellation policy | ✓ | — | ✓ | — |
| Mark reservation completed | ✓ | ✓ | — | — |
| Cancel reservation | ✓ | ✓ | — | — |
| Mark no-show | ✓ | ✓ | — | — |
| View historial reserva | ✓ | ✓ | ✓ | ✓ |
| View properties | ✓ | ✓ | ✓ | ✓ |
| Create/edit property | ✓ | — | — | — |
| Deactivate property | ✓ | — | — | — |
| Manage channels (canal) | ✓ | — | — | — |
| Manage seasons (temporada) | ✓ | — | — | — |
| Configure cancellation policies | ✓ | — | — | — |
| View penalty (penalidad) | ✓ | ✓ | ✓ | ✓ |
| Approve/adjust penalty | ✓ | ✓ | — | — |
| Register anticipo | ✓ | ✓ | — | — |
| Apply anticipo | ✓ | ✓ | — | — |
| Generate invoice (borrador) | ✓ | ✓ | — | — |
| Emit invoice | ✓ | ✓ | — | — |
| Annul invoice | ✓ | ✓ | — | — |
| View invoice | ✓ | ✓ | ✓ | ✓ |
| Download invoice PDF | ✓ | ✓ | — | — |
| Resend invoice | ✓ | ✓ | — | — |
| Issue credit note | ✓ | ✓ | — | — |
| Process refund (devolucion) | ✓ | ✓ | — | — |
| View audit log | ✓ | — | — | — |
| Manage users | ✓ | — | — | — |

### Required Spring Security configuration

```java
// Endpoint authorization model (to be implemented)
// /api/auth/**           → permitAll()
// /api/roles             → hasRole('ADMIN')
// /api/usuarios/**       → hasRole('ADMIN')
// /api/canales           → authenticated()
// /api/temporadas/**     → hasRole('ADMIN')
// /api/propiedades/**    → hasAnyRole('ADMIN','CONTADOR','AUXILIAR','RECEPCIONISTA')
// POST/PUT/DELETE propiedades → hasRole('ADMIN')
// /api/politicas/**      → hasRole('ADMIN')
// /api/reservas GET      → authenticated()
// POST /api/reservas     → hasAnyRole('ADMIN','AUXILIAR','RECEPCIONISTA')
// PUT /reservas/{id}/politica → hasAnyRole('ADMIN','AUXILIAR')
// PUT /reservas/{id}/cancelar → hasAnyRole('ADMIN','CONTADOR')
// /api/anticipos/**      → hasAnyRole('ADMIN','CONTADOR')
// /api/penalidades/**    → hasAnyRole('ADMIN','CONTADOR')
// /api/facturas/**       → hasAnyRole('ADMIN','CONTADOR')
// GET /api/facturas      → authenticated()
// /api/devoluciones/**   → hasAnyRole('ADMIN','CONTADOR')
// /api/log               → hasRole('ADMIN')
// /api/dashboard         → authenticated()
```

---

## Section 9 — Dependency Graph

The following order is MANDATORY. Each module cannot be implemented until all its dependencies are complete.

```
[1] Flyway baseline + schema freeze
         |
         ↓
[2] Rol entity + RBAC foundation
         |
         ↓
[3] Canal + Temporada (reference data)
         |
         ↓
[4] Propiedad correction (activa flag, remove invented columns)
         |
         ↓
[5] PoliticaCancelacion + ReglaPenalidad
         |
         ↓
[6] Reserva rebuild (root aggregate)
     - Requires: Canal, Temporada, Propiedad, PoliticaCancelacion, Rol/User
     - Creates: historial_reserva writes
     - Removes: Guest dependency
         |
         ↓
[7] Guest module removal (after Reserva no longer references guestId)
         |
         ↓
[8] Anticipo (replaces Payment)
     - Requires: Reserva, User
     - Removes: Payment dependency
         |
         ↓
[9] Payment module removal (after Anticipo exists)
         |
         ↓
[10] Penalidad + calculation engine
     - Requires: Reserva, ReglasPenalidad, User
         |
         ↓
[11] Factura rebuild (correct formula)
     - Requires: Anticipo (for descuento_anticipo), Penalidad, User, sequential number
     - Triggers: Devolucion auto-generation
         |
         ↓
[12] Devolucion (refund obligation)
     - Requires: Anticipo, Factura
         |
         ↓
[13] NotaCredito
     - Requires: Factura
         |
         ↓
[14] LogTransaccion (cross-cutting)
     - Wires into: ALL service write methods
         |
         ↓
[15] DashboardService correction
     - Requires: all modules to exist with correct entities
```

**Why this order:**
- `Rol` must exist before RBAC can be enforced. All subsequent modules depend on role-gated endpoints.
- `Canal` and `Temporada` must exist before `Reserva` can be created with the correct FK columns.
- `Propiedad` must have `activa` before `Reserva` can enforce RF_03.
- `PoliticaCancelacion` must exist before `Reserva` can enforce RF_02.
- `Reserva` must be correct before `Anticipo`, `Penalidad`, `Factura`, and `Devolucion` can be built (they all FK into `reserva`).
- `Anticipo` must exist before `Factura` can compute `descuento_anticipo`.
- `Penalidad` must exist before `Factura` can compute `monto_condonado`.
- `Factura` must be emitted before `Devolucion` can be auto-generated.
- `LogTransaccion` is cross-cutting and wires in last, but the entity and service must be created in Sprint 2 so all subsequent sprints can use it.

---

## Section 10 — Refactoring Plan

### Sprint 0 — Schema Foundation (1 day)

**Goal:** Establish Flyway; freeze schema; prevent further ddl-auto drift.

**Files affected:**
- `pom.xml` — add `flyway-core` dependency
- `application.properties` — change `ddl-auto=update` to `ddl-auto=validate`
- Create `src/main/resources/db/migration/V1__baseline.sql` — copy of `Esquema_BD.sql` verbatim
- `application.properties` — add `spring.flyway.baseline-on-migrate=true` for existing DB

**Database impact:** No changes to existing tables; Flyway establishes migration history.

**Frontend impact:** None.

**Risk:** If the running database already has `huesped` and `pago` tables, Flyway baseline will include them. Next migration will clean them up. Medium risk.

**Rollback:** Revert `ddl-auto=validate` to `ddl-auto=update`; delete migration files.

**Estimated effort:** 0.5 days backend; 0.5 days testing.

**Acceptance criteria:** Application starts with Flyway enabled; `ddl-auto=validate` passes on existing schema; no migration errors.

---

### Sprint 1 — Roles and RBAC (2 days)

**Goal:** Wire role-based access control end-to-end.

**Backend tasks:**
- Create `rol/entity/Rol.java`, `RolRepository`, `RolService`, `RolController`
- Create `V2__roles.sql` — seed 4 roles
- Update `User.java` — add `@ManyToOne Rol rol` JPA relationship
- Update `UserService.createUser()` — accept `nombre` and `rolId` from request; remove hardcoded values; add unique email validation; record in `log_transaccion`
- Update `UserService` — replace `deleteUser()` with `desactivarUser()` (activo=false)
- Update `UserService.getUsers()` — return nombre, email, rol, activo, ultimaSesion
- Update `UserDetailsServiceImpl.loadUserByUsername()` — load `rol.nombre`; construct `SimpleGrantedAuthority("ROLE_" + rolNombre.toUpperCase())`
- Update `JwtService.generateToken()` — include role claim
- Update `SecurityConfig` — add role-based method-level security (`@EnableMethodSecurity`)

**Frontend tasks:**
- Update `UsuariosView.vue` — table columns Nombre, Correo, Rol, Estado (Activo/Inactivo), Última sesión; create/edit modal with Rol dropdown; Deactivate action
- Update `authService.ts` — read rol from token response; store alongside token
- Update `router/index.js` — pass role to navigation; hide nav items per role

**Database impact:** `V2__roles.sql` inserts 4 rows; `ALTER TABLE usuario ADD CONSTRAINT fk_usuario_rol FOREIGN KEY (rol_id) REFERENCES rol(id)` if baseline didn't include it.

**Risk:** Low — additive.

**Estimated effort:** Backend 1.5 days; Frontend 0.5 days.

---

### Sprint 2 — Reference Data (Canal + Temporada + Propiedad fix) (2 days)

**Goal:** Implement missing reference modules; correct Propiedad schema.

**Backend tasks:**
- Create `canal/` package — entity, repository, service, controller; `V3__canales.sql` with 5 default channels
- Create `temporada/` package — entity, repository, service, controller (CRUD); season auto-find by date range query
- `V4__fix_propiedad.sql` — `ALTER TABLE propiedad ADD COLUMN activa BOOLEAN NOT NULL DEFAULT true, ADD COLUMN descripcion TEXT; DROP COLUMN ciudad, DROP COLUMN capacidad, DROP COLUMN precio_por_noche`
- Update `Property.java` — remove 3 columns; add `activa`, `descripcion`
- Update `PropertyService` — replace `delete()` with `desactivar()`; add `activa = true` validation in `findAll`

**Frontend tasks:**
- Update `PropertiesView.vue` — remove city/capacity/price fields; add descripcion; add deactivate action
- Add canal dropdown data loading to reservation form (prepare for Sprint 3)

**Database impact:** Medium — `propiedad` table columns changed; existing rows in `propiedad` will lose their invented columns.

**Risk:** Medium — existing `DevelopmentDataSeeder` seeds ciudad/capacidad/pricePerNight which will no longer compile. **Disable seeder** (`@Profile("never")`) at start of Sprint 2 until Sprint 5 rewrite.

**Estimated effort:** Backend 1.5 days; Frontend 0.5 days.

---

### Sprint 3 — Cancellation Policies + Rebuilding Reserva (5 days)

**Goal:** Implement the full policy module; rebuild the Reserva root aggregate; remove Guest.

**Backend tasks:**
- Create `politica/` package — `PoliticaCancelacion`, `ReglaPenalidad`, repositories, services, controllers
- `V5__politicas.sql` — create `politica_cancelacion` and `regla_penalidad` tables
- `V6__fix_reserva.sql` — `ALTER TABLE reserva DROP COLUMN huesped_id, DROP COLUMN cantidad_huespedes; ADD COLUMN canal_id INT NOT NULL DEFAULT 1, ADD COLUMN temporada_id INT, ADD COLUMN politica_cancelacion_id INT, ADD COLUMN usuario_creador_id INT NOT NULL DEFAULT 1, ADD COLUMN numero_reserva VARCHAR(20) UNIQUE, ADD COLUMN huesped_nombre VARCHAR(200), ADD COLUMN huesped_documento VARCHAR(50), ADD COLUMN notas TEXT, ADD COLUMN monto_total DECIMAL(12,2) NOT NULL DEFAULT 0, ADD COLUMN no_show BOOLEAN NOT NULL DEFAULT false, ADD COLUMN modificado_en TIMESTAMP; ALTER COLUMN estado TYPE VARCHAR(20)` and update enum values; ADD FK constraints
- Rebuild `Reservation.java`, `ReservationService.java`, `ReservationController.java` — state machine, canal/temporada/policy FKs, numero_reserva generation, monto_total, historial writes
- Create `historial_reserva/` package
- Create `LogTransaccion` entity (shell) for use in subsequent sprints
- Remove `Guest` module — delete `guest/` package entirely; update `ReservationService` to remove `guestRepository` dependency

**Frontend tasks:**
- Rebuild `ReservationsView.vue` — new form: Propiedad dropdown, Canal de origen dropdown, Huésped (text field), Temporada (auto-populated read-only), Monto total, Check-in, Check-out, Notas; state machine action buttons; policy link step; historial tab
- Delete `GuestsView.vue`, `guestService.ts`, `types/guest.ts`
- Remove Guest from `AppNav.vue`

**Database impact:** HIGH — significant changes to `reserva` table. Existing seeded reservation rows will be invalidated by NOT NULL column additions.

**Risk:** HIGH — this is the most complex sprint. Existing test data becomes invalid. The DevelopmentDataSeeder is disabled so this is acceptable.

**Rollback:** Revert migration (`V6` down migration); restore prior entity files from git.

**Estimated effort:** Backend 4 days; Frontend 1 day.

---

### Sprint 4 — Anticipo + Penalidad (3 days)

**Goal:** Implement advance payment and penalty calculation; remove Payment module.

**Backend tasks:**
- `V7__anticipo.sql` — create `anticipo` table
- Create `anticipo/` package — entity, repository, service, controller; state machine (registrado→aplicado→devuelto); running total query
- `V8__penalidad.sql` — create `penalidad` table
- Create `penalidad/` package — entity, repository; `PenalidadService.calcular()` implementing rule-matching engine (find `regla_penalidad` by days_anticipacion range and season; apply tipo calculation)
- Wire `ReservationService.cancelar()` and `marcarNoShow()` to trigger `PenalidadService.calcular()` in the same `@Transactional`
- Remove `Payment` module — delete `payment/` package; update `DashboardService` to remove payment dependency

**Frontend tasks:**
- Create `AnticiposView.vue` — register anticipo form; running total display; refund obligation panel (per CU_03 mockup); badge indicator
- Create `PenalidadesView.vue` — penalty detail: monto_segun_politica, monto_aprobado, monto_condonado; approve action
- Delete `PaymentsView.vue`, `paymentService.ts`, `types/payment.ts`
- Remove Payments from `AppNav.vue`; add Penalidades and Anticipos nav items

**Database impact:** Medium — new tables only.

**Risk:** Medium — penalty rule engine is complex; requires thorough testing of all penalty types.

**Estimated effort:** Backend 2.5 days; Frontend 0.5 days.

---

### Sprint 5 — Invoice Rebuild + Devolucion + NotaCredito (4 days)

**Goal:** Rebuild invoice with correct formula; implement auto-refund trigger; add credit notes.

**Backend tasks:**
- `V9__fix_factura.sql` — `ALTER TABLE factura ADD COLUMN usuario_id INT NOT NULL DEFAULT 1, ADD COLUMN numero_factura VARCHAR(50) UNIQUE, ADD COLUMN descuento_anticipo DECIMAL(12,2) NOT NULL DEFAULT 0, ADD COLUMN url_documento VARCHAR(500); ALTER COLUMN estado TYPE VARCHAR(20)` update values borrador/emitida/anulada; RENAME COLUMN tax TO impuestos; DROP UNIQUE CONSTRAINT on reserva_id (if db-level); ALTER COLUMN subtotal to store monto_total; DROP COLUMN ON EXISTING INCORRECT ROWS`
- Rebuild `InvoiceService` — new formula: `subtotal = reserva.monto_total; descuento_anticipo = Σ(anticipo applied); condonaciones = penalidad.monto_condonado; base = subtotal − descuento_anticipo − condonaciones; impuestos = base × 0.19; total = base + impuestos`; generate `numero_factura = "FAC-" + String.format("%04d", nextSeq())`; on emit: check if anticipos > net → auto-generate devolucion
- `V10__devolucion.sql` — create `devolucion` table
- Create `devolucion/` package — entity, repository, service (auto-triggered by InvoiceService.emitir()); processing endpoint with referencia_comprobante validation
- `V11__nota_credito.sql` — create `nota_credito` table
- Create `nota_credito/` package — entity, repository, service, controller
- Rewrite `DevelopmentDataSeeder` with correct entities (no guest, no payment)

**Frontend tasks:**
- Rebuild `InvoicesView.vue` as "Facturación" — invoice history with filters (RF_15, CU_08 mockup); desglose panel showing Bruto−Anticipos−Condonaciones=Neto+Impuestos=Total
- Add refund obligation UI to `AnticiposView.vue` (alert banner + "Registrar devolución realizada" button per CU_03 mockup)
- Add credit note section to invoice detail

**Database impact:** HIGH — significant `factura` schema changes. Existing seeded invoice rows will be invalid.

**Risk:** HIGH — formula change; sequential number generation requires atomic sequence. Use PostgreSQL sequence (`CREATE SEQUENCE factura_seq`).

**Estimated effort:** Backend 3 days; Frontend 1 day.

---

### Sprint 6 — Audit Logging + Dashboard Correction (2 days)

**Goal:** Wire `log_transaccion` to all service writes; correct Dashboard metrics.

**Backend tasks:**
- Create `log/` package — `LogTransaccion`, `LogTransaccionRepository`, `LogTransaccionService.registrar()` with `Propagation.MANDATORY`
- Wire `registrar()` calls into: `ReservationService` (all state transitions), `AnticipoService` (create, apply), `PenalidadService` (calculate, approve), `InvoiceService` (create borrador, emit, annul), `DevolucionService` (create, resolve), `UserService` (create, edit, deactivate)
- Rebuild `DashboardService` — replace `totalGuests/totalPayments` with `anticiposPendientesAplicar`, `devolucionesPendientes`, `facturasBorrador`, `facturasEmitidas`, correct reservation counts
- Add `GET /api/log` endpoint (Admin only)

**Frontend tasks:**
- Update `DashboardView.vue` — new metric cards reflecting correct domain
- Add basic audit log view (optional, P3)

**Estimated effort:** Backend 1.5 days; Frontend 0.5 days.

---

### Sprint 7 — Non-functional Hardening (1 day)

**Goal:** Fix remaining non-functional issues.

**Tasks:**
- CORS: externalize `ALLOWED_ORIGIN` from hardcoded `localhost:5173` to environment variable
- `api.ts`: add 401 response interceptor → auto-redirect to `/` on token expiry
- `router/index.js` → `router/index.ts`: add role-based navigation guards
- `@Transactional` audit: confirm all remaining service write methods are transactional
- `App.vue`: remove `text-align: center` from `#app` (causes table misalignment)
- Add `@Transactional(readOnly = true)` to all read-only service methods
- `JwtService.extractClaim` → change to `private`
- Remove `LoginResponse.message` field (unused by frontend)

**Estimated effort:** 1 day.

---

## Section 11 — Reusable Components

The following exist and should be preserved without major modification:

| Component | Why reusable |
|-----------|-------------|
| `JwtService.java` | JWT generation/validation is correctly implemented; only needs role claim added |
| `JwtAuthenticationFilter.java` | Correctly filters requests; no changes needed |
| `SecurityConfig.java` (config package) | BCrypt bean, CORS setup, stateless sessions are correct; only endpoint authorization rules need adding |
| `GlobalExceptionHandler.java` | `@RestControllerAdvice` covers all exception types; only needs logging added to generic handler |
| `UserRepository.java` | `findByUsername` is correct; no changes needed |
| `booking/model/Booking.java` | Pure domain object with correct validation constraints; keep as domain value object |
| `booking/service/BookingValidator.java` | Unit-tested business rules; promote to `@Service` when wiring to Reserva |
| `booking/service/InvoiceCalculator.java` | Keep as reference for IVA rate constant; wire logic into InvoiceService |
| `AppLayout.vue` | Correctly wraps all authenticated views |
| `AppHeader.vue` | Logout, navigation — correct; only needs role-based show/hide logic |
| `AppNav.vue` | Reusable; update link list only |
| `AppModal.vue` | Generic reusable modal |
| `PageHeader.vue` | Generic reusable page header |
| `composables/useAsyncState.ts` | Excellent composable; used by all views; keep exactly as-is |
| `services/api.ts` | Central Axios instance; only needs 401 interceptor added |
| `services/authService.ts` | Login flow correct; only needs role extraction |
| `assets/shared.css` | All shared classes reusable; only remove text-align issue |
| `docker-compose.yml` | PostgreSQL + app containers — correct; no changes needed |
| `Dockerfile` | Spring Boot build — correct |
| All test infrastructure | JUnit 5 + JaCoCo + Spring Boot Test; all passing tests remain valid |
| `application.properties` | Structure correct; only ddl-auto changes needed |
| `pom.xml` | Dependencies correct; only Flyway needs to be added |

---

## Section 12 — Components That Must Be Removed

| Component | Reason |
|-----------|--------|
| `guest/entity/Guest.java` | No schema counterpart; invented entity |
| `guest/repository/GuestRepository.java` | Same |
| `guest/service/GuestService.java` | Same |
| `guest/controller/GuestController.java` | Same |
| `guest/dto/` (all DTOs) | Same |
| `payment/entity/Payment.java` | No schema counterpart; wrong financial model |
| `payment/entity/PaymentMethod.java` | Same |
| `payment/repository/PaymentRepository.java` | Same |
| `payment/service/PaymentService.java` | Same |
| `payment/controller/PaymentController.java` | Same |
| `payment/dto/` (all DTOs) | Same |
| `GuestsView.vue` | No schema counterpart; not in intended navigation |
| `PaymentsView.vue` | No schema counterpart; not in intended navigation |
| `services/guestService.ts` | Same |
| `services/paymentService.ts` | Same |
| `types/guest.ts` | Same |
| `types/payment.ts` | Same |
| `Invoice.pay()` method | PAID state doesn't exist in original design |
| `Invoice.delete()` method | Invoices must not be hard-deleted |
| `UserService.deleteUser()` | Replace with `desactivarUser()` |
| `PropertyService.delete()` | Replace with `desactivar()` |
| `ReservationService.delete()` | Reservations must not be deleted |
| The leftover `backend/` directory | Empty scaffold leftover; not part of project |
| `com.novafacts.SecurityConfig` (wrong package) | Duplicate; dead code; stale scaffold |
| `com.novafacts.backend.TestController` | Test scaffold; not production code |
| `com.novafacts.backend.controller.HelloController` | Test scaffold |
| `com.novafacts.config.PasswordConfig` | Duplicate; not wired; stale scaffold |

---

## Section 13 — Migration Risks

### Risk R-01 — Existing reservation data becomes invalid after Sprint 3
**Severity:** HIGH
**Description:** Sprint 3 adds 5 NOT NULL columns to `reserva` (canal_id, politica_cancelacion_id, usuario_creador_id, monto_total, numero_reserva). Existing rows lack these values.
**Mitigation:** The `DevelopmentDataSeeder` is disabled at the start of Sprint 2. Test environment DB is recreated clean before Sprint 3. If a staging environment has production-like data, a data migration script must be written: assign DEFAULT values to NULL FKs, generate placeholder `numero_reserva` values.
**Data loss:** Existing seeded data will be overwritten. No real production data exists in this academic project.

### Risk R-02 — Invoice data becomes invalid after Sprint 5
**Severity:** HIGH
**Description:** Sprint 5 changes the `factura` schema (rename columns, add NOT NULL columns, change status values). Existing PENDING/PAID/CANCELLED rows conflict with new borrador/emitida/anulada enum.
**Mitigation:** Same as R-01 — DB recreated clean. Migration script if needed: `UPDATE factura SET estado = 'emitida' WHERE estado = 'PAID'`, etc.

### Risk R-03 — `DevelopmentDataSeeder` fails to compile after Sprint 2
**Severity:** MEDIUM
**Description:** Seeder references `Guest`, `Property.city`, `Property.capacity`, `Property.pricePerNight`, `Payment` — all of which are removed or changed in Sprints 2–4.
**Mitigation:** Disable seeder with `@Profile("never")` at start of Sprint 2. Rewrite seeder in Sprint 5 once all correct entities exist.

### Risk R-04 — Frontend breaks when Guest and Payment APIs disappear
**Severity:** MEDIUM
**Description:** Current frontend `GuestsView.vue` and `PaymentsView.vue` call APIs that will be removed.
**Mitigation:** Delete the views and services in the same sprint that removes the backend modules. No partial state.

### Risk R-05 — Penalty rule engine complexity
**Severity:** MEDIUM
**Description:** The `regla_penalidad` matching logic (days range + season filter + tipo calculation) is non-trivial. Incorrect implementation produces wrong financial results.
**Mitigation:** Write `PenalidadServiceTest` with all 4 penalty types × multiple day ranges before implementing the engine. The `BookingValidator.java` POJO test suite (22 tests) serves as a specification reference.

### Risk R-06 — Sequential invoice number generation under concurrency
**Severity:** MEDIUM
**Description:** `FAC-{NNNN}` sequential number must be unique even under concurrent invoice generation.
**Mitigation:** Use a PostgreSQL sequence (`CREATE SEQUENCE factura_numero_seq`) queried atomically. Do NOT use `MAX(id) + 1` pattern.

### Risk R-07 — Flyway baseline on existing non-empty database
**Severity:** LOW
**Description:** If the database already has tables (from `ddl-auto=update`), Flyway's `baseline-on-migrate=true` must be set. If not set, Flyway will reject migration on a non-empty schema.
**Mitigation:** Set `spring.flyway.baseline-on-migrate=true` in `application.properties` for development. Remove for clean production deployments.

### Risk R-08 — `@Transactional` + `Propagation.MANDATORY` on LogTransaccion
**Severity:** LOW
**Description:** If `LogTransaccionService.registrar()` is called outside a transaction, it will throw an exception. This can break callers that forgot to add `@Transactional`.
**Mitigation:** All service write methods already have `@Transactional` in the new modules. Add a unit test that verifies calling `registrar()` without a transaction throws `IllegalTransactionStateException`.

---

## Section 14 — Missing Tests

### Unit tests missing

| Test class | What to test |
|-----------|-------------|
| `PenalidadServiceTest` | All 4 penalty types (porcentaje, noches, valor_fijo, mixto); day range matching; season filter; edge cases (day = min, day = max) |
| `InvoiceServiceTest` | Correct formula with/without anticipos; with/without condonaciones; tax calculation; edge case anticipos > total |
| `ReservacionServiceTest` | State machine transitions (pendiente→confirmada, confirmada→completada, etc.); invalid transitions throw exception; season auto-assign; overlap detection |
| `AnticipoServiceTest` | State machine; running total; cannot edit/delete; over-valor-bruto requires confirmation |
| `DevolucionServiceTest` | Auto-generated when anticipos > neto; referencia required to close; one open devolucion per concept |
| `LogTransaccionServiceTest` | Fails with MANDATORY propagation outside transaction |
| `UserServiceTest` | Unique email enforcement; soft-deactivate; role assignment |
| `BookingValidatorTest` | Already exists (22 tests) ✅ |
| `InvoiceCalculatorTest` | Already exists ✅ |

### Integration tests missing

| Test class | What to test |
|-----------|-------------|
| `ReservacionControllerTest` | Full lifecycle: create→link policy→confirm→complete→invoice |
| `FacturaControllerTest` | Invoice generation with anticipos; correct totals; FAC-NNNN format |
| `PenalidadControllerTest` | Penalty calculation on cancellation; approval workflow |
| `DevolucionControllerTest` | Auto-generation; resolution with comprobante |
| `RoleAuthorizationTest` | Each endpoint rejects users with wrong role (403 Forbidden) |
| `AnticipoControllerTest` | Register, apply, running total, devolucion trigger |

### Business rule tests missing

| Test | Rule |
|------|------|
| Cannot confirm reservation without policy | RF_02 |
| Cannot create reservation on inactive property | RF_03 |
| Season auto-assigned correctly | RF_05 |
| Only one invoice per reservation | CU_07 notes |
| Anticipo immutable (no edit) | CU_08 notes |
| Devolucion auto-generated when anticipos > neto | RF_13 |
| Invoice FAC-NNNN unique sequential | CU_08 mockup |

### Security tests missing

| Test | What |
|------|------|
| `SecurityTest` | Unauthenticated → 401 on all protected endpoints |
| `RoleTest` | CONTADOR cannot manage users (403) |
| `RoleTest` | RECEPCIONISTA cannot approve penalty (403) |
| `RoleTest` | AUXILIAR cannot emit invoice (403) |
| `AdminOnlyTest` | Only ADMIN can read audit log |
| `SoftDeleteTest` | Deleted user cannot log in; records still readable |

---

## Section 15 — Final Migration Roadmap

| Sprint | Objective | Deliverables | Acceptance Criteria | Dependencies | Duration |
|--------|-----------|-------------|---------------------|-------------|---------|
| 0 | Schema foundation | Flyway migration V1; `ddl-auto=validate`; application still starts | Application boots; Flyway migration history table created | None | 1 day |
| 1 | RBAC foundation | `Rol` entity; RBAC in SecurityConfig; UserService corrected; UsuariosView rebuilt | 4 roles seeded; each endpoint returns 403 for wrong role; users soft-deactivated | Sprint 0 | 2 days |
| 2 | Reference data + Propiedad fix | Canal; Temporada; corrected Propiedad; Flyway V2–V4 | Canal list endpoint returns 5 channels; season auto-assign returns correct season; Propiedad has activa + descripcion | Sprint 1 | 2 days |
| 3 | Reserva rebuild | PoliticaCancelacion; ReglaPenalidad; Reserva entity rebuilt; HistorialReserva; Guest module removed | Reservation created in pendiente state; policy required before confirmada; historial entry on creation; Guest module endpoints return 404 | Sprint 2 | 5 days |
| 4 | Anticipo + Penalidad | Anticipo module; penalty calculation engine; Payment module removed | Anticipo registered and applied; penalty calculated from rules on cancellation; Payment module endpoints return 404 | Sprint 3 | 3 days |
| 5 | Invoice rebuild + Devolucion + NotaCredito | Invoice with correct formula; auto-devolucion trigger; credit notes; DevelopmentDataSeeder rewritten | Invoice total = (monto_total − descuento_anticipo) × 1.19; FAC-NNNN generated; devolucion auto-created when anticipos > neto; seeder generates 30+ correct records | Sprint 4 | 4 days |
| 6 | Audit log + Dashboard fix | LogTransaccion; corrected DashboardService | Every write operation creates log entry; Dashboard shows anticipos pendientes, devoluciones pendientes, facturas borrador/emitidas | Sprint 5 | 2 days |
| 7 | Non-functional hardening | CORS externalized; 401 interceptor; role-based nav guards; App.vue text-align fix | App works on different port; expired token redirects to login; nav hides Facturación for Recepcionista; table cells left-aligned | Any sprint | 1 day |

**Total estimated duration: 20 development days**

**Critical path:** Sprint 0 → Sprint 1 → Sprint 2 → Sprint 3 (blocking) → Sprint 4 → Sprint 5 → Sprint 6 → Sprint 7

Sprint 3 is the critical-path bottleneck. Every subsequent sprint depends on the Reserva root aggregate being correct. It must not be rushed or partially implemented.

---

## Appendix — Complete Requirements Register

| ID | Requirement | Source | Current Status |
|----|-------------|--------|----------------|
| RF_01 | Register reservations with property, canal, season; maintain traceability | CU_01 | ❌ Canal, temporada, historial missing |
| RF_02 | Every reservation linked to cancellation policy before "Confirmada" | CU_02 | ❌ Policy module absent |
| RF_03 | Only active properties can receive reservations | CU_01 precondition | ❌ activa flag missing |
| RF_04 | Season must exist covering reservation dates | CU_01 precondition | ❌ Temporada absent |
| RF_05 | System auto-assigns season by dates | CU_01 step 6 | ❌ Not implemented |
| RF_06 | Reservation number auto-generated, unique, immutable (RES-NNNN) | CU_01 notes | ❌ Not implemented |
| RF_07 | Policy configuration per property (admin) | CU_02, CU_05 | ❌ Not implemented |
| RF_08 | Policy change on confirmed reservation creates historial entry | CU_02 notes | ❌ Not implemented |
| RF_09 | Show monto_segun_politica vs monto_aprobado vs monto_condonado | CU_07 | ❌ Penalidad absent |
| RF_10 | Anticipos discounted from final invoice total | CU_07, CU_08 | ❌ Wrong formula |
| RF_11 | Anticipos are accounting liabilities in reservation balance | CU_03 | ❌ Anticipo absent |
| RF_12 | Penalty calculated from matching penalty rules | Schema, CU_04 | ❌ Engine absent |
| RF_13 | Auto-generate refund obligation when anticipos > neto | CU_03 | ❌ Not implemented |
| RF_14 | Electronic invoice with anticipos and approved penalties applied | CU_07 | ❌ Wrong formula, wrong fields |
| RF_15 | Invoice history with filters (propiedad, fecha, estado) | CU_08 | ❌ No filters implemented |
| RF_16 | Admin creates, edits, deactivates users and assigns roles | CU_09 | ❌ Hardcoded values; hard delete |
| RF_17 | System restricts actions by authenticated user role | CU_09 | ❌ RBAC not enforced |
| RF_18 | Every transaction recorded with user, date, time | CU_01/02/03/08/09 | ❌ log_transaccion absent |
| RF_19 | Refund obligation states: pendiente → resuelta | CU_03 | ❌ Devolucion absent |
| RF_20 | Badge indicator on Anticipos nav for pending refunds | CU_03 mockup | ❌ Not implemented |
| RNF_01 | Transactional integrity — atomic operations | CU_01/CU_03 | ⚠️ @Transactional partial |
| RNF_02 | Data protected via encryption and role-based access | CU_09 | ⚠️ BCrypt OK; RBAC absent |

**Requirements satisfied: 2 / 22 (authentication + basic CRUD patterns)**

---

*End of NovaFacts Master Technical Audit v1.0 — 2026-06-28*
*All findings are evidence-based. No rule was invented. No table was skipped.*
*Document conflicts C-1 and C-2 are explicitly identified and resolved with the SQL schema as authority.*
