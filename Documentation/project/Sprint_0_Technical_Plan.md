# Sprint 0 — Technical Plan
## Schema Foundation: Flyway Adoption

**Date:** 2026-06-28
**Status:** PENDING APPROVAL
**Estimated duration:** 0.5 days backend / 0 frontend

---

## 1. Objective

Replace uncontrolled schema evolution (`spring.jpa.hibernate.ddl-auto=update`) with Flyway versioned migrations.

**After this sprint:**
- Every schema change has a version number, a SQL file, and a checksum stored in `flyway_schema_history`.
- No Hibernate auto-DDL. The database is the authority; the code must match it.
- Tests remain isolated (H2 + `create-drop`) — Flyway is disabled in the test profile.
- A fresh Docker environment can reproduce the exact current schema by applying V1.
- All subsequent sprints add migrations as `V2__*`, `V3__*`, etc.

**This sprint does NOT:**
- Correct the schema to match `Esquema_BD.sql` (that is Sprint 3 and beyond).
- Create new entities, modify services, or touch the frontend.
- Break any of the 54 currently passing tests.

---

## 2. Current State Analysis

### What Hibernate `ddl-auto=update` has produced in PostgreSQL

From reading each entity file, the current database schema is:

**Table `usuario`** (entity: `User.java`, `@Table(name="usuario")`)
| Column | Type | Constraints |
|--------|------|-------------|
| `id` | BIGSERIAL | PK |
| `email` | VARCHAR(150) | NOT NULL, UNIQUE |
| `password_hash` | VARCHAR(255) | NOT NULL |
| `nombre` | VARCHAR(100) | NOT NULL |
| `rol_id` | INTEGER | NOT NULL |
| `activo` | BOOLEAN | NOT NULL |
| `creado_en` | TIMESTAMP | NOT NULL |

**Table `huesped`** (entity: `Guest.java`, @Table(name="huesped") — invented, no official counterpart)
| Column | Type | Constraints |
|--------|------|-------------|
| `id` | BIGSERIAL | PK |
| `primer_nombre` | VARCHAR(100) | NOT NULL |
| `apellido` | VARCHAR(100) | NOT NULL |
| `tipo_documento` | VARCHAR(50) | NOT NULL |
| `numero_documento` | VARCHAR(50) | NOT NULL, UNIQUE |
| `email` | VARCHAR(150) | |
| `telefono` | VARCHAR(30) | |
| `creado_en` | TIMESTAMP | NOT NULL |

**Table `propiedad`** (entity: `Property.java`, @Table(name="propiedad"))
| Column | Type | Constraints |
|--------|------|-------------|
| `id` | BIGSERIAL | PK |
| `nombre` | VARCHAR(150) | NOT NULL, UNIQUE |
| `direccion` | VARCHAR(250) | NOT NULL |
| `ciudad` | VARCHAR(100) | NOT NULL ← invented |
| `capacidad` | INTEGER | NOT NULL ← invented |
| `precio_por_noche` | DECIMAL(15,2) | NOT NULL ← invented |
| `creado_en` | TIMESTAMP | NOT NULL |

**Table `reserva`** (entity: `Reservation.java`, @Table(name="reserva"))
| Column | Type | Constraints |
|--------|------|-------------|
| `id` | BIGSERIAL | PK |
| `huesped_id` | BIGINT | NOT NULL ← invented |
| `propiedad_id` | BIGINT | NOT NULL |
| `fecha_inicio` | DATE | NOT NULL |
| `fecha_fin` | DATE | NOT NULL |
| `cantidad_huespedes` | INTEGER | NOT NULL ← invented |
| `estado` | VARCHAR(20) | NOT NULL |
| `creado_en` | TIMESTAMP | NOT NULL |

Status values stored: `CONFIRMED`, `CANCELLED`, `COMPLETED` (English — wrong)

**Table `factura`** (entity: `Invoice.java`, @Table(name="factura"))
| Column | Type | Constraints |
|--------|------|-------------|
| `id` | BIGSERIAL | PK |
| `reserva_id` | BIGINT | NOT NULL, UNIQUE |
| `subtotal` | DECIMAL(15,2) | NOT NULL |
| `iva` | DECIMAL(15,2) | NOT NULL ← wrong name (official = `impuestos`) |
| `total` | DECIMAL(15,2) | NOT NULL |
| `estado` | VARCHAR(20) | NOT NULL |
| `version` | BIGINT | (optimistic lock) |
| `creado_en` | TIMESTAMP | NOT NULL |

Status values stored: `PENDING`, `PAID`, `CANCELLED` (English — wrong)

**Table `pago`** (entity: `Payment.java`, @Table(name="pago") — invented, no official counterpart)
| Column | Type | Constraints |
|--------|------|-------------|
| `id` | BIGSERIAL | PK |
| `factura_id` | BIGINT | NOT NULL, UNIQUE |
| `monto` | DECIMAL(15,2) | NOT NULL |
| `metodo_pago` | VARCHAR(20) | NOT NULL |
| `referencia` | VARCHAR(100) | |
| `pagado_en` | TIMESTAMP | NOT NULL |
| `version` | BIGINT | |
| `creado_en` | TIMESTAMP | NOT NULL |

**Index:** `idx_reserva_propiedad_id` on `reserva(propiedad_id)` (from `@Index` annotation)

### What the test environment has

`application-test.properties` uses H2 in-memory with `ddl-auto=create-drop`. The same entities produce equivalent tables in H2 on each test run and they are dropped after. Flyway must not interfere here because V1 SQL uses PostgreSQL-specific syntax (`BIGSERIAL`, `TIMESTAMP WITHOUT TIME ZONE`, etc.) that H2 cannot parse.

---

## 3. Dependencies

None. Sprint 0 has no predecessors.

---

## 4. Risks

### R-01 — Flyway history table already exists in the running database
**Probability:** Very low (project has always used `ddl-auto=update` with no prior Flyway setup).
**Impact:** Application fails to start with `Found non-empty schema(s) "public" without schema history table!`.
**Detection:** Run `SELECT * FROM information_schema.tables WHERE table_name = 'flyway_schema_history'` in PostgreSQL before applying.
**Mitigation:** `spring.flyway.baseline-on-migrate=true` together with `spring.flyway.baseline-version=1` instructs Flyway to create the history table and mark V1 as already applied, without running V1.sql. This is the standard adoption strategy for existing databases.

### R-02 — H2 cannot parse PostgreSQL SQL in V1
**Probability:** Certain if Flyway runs in the test profile.
**Impact:** All 54 tests fail at context load.
**Mitigation:** Add `spring.flyway.enabled=false` to `application-test.properties`. Tests continue using `ddl-auto=create-drop` as before.

### R-03 — `ddl-auto=validate` fails due to schema/entity mismatch
**Probability:** Low. Hibernate created the DB from the entities, so they match.
**Impact:** Application fails to start.
**Mitigation:** V1 is derived from the exact column names in the entity files. If `validate` fails, it reveals a real mismatch that must be diagnosed before proceeding. Rollback is trivial: revert `ddl-auto` to `update`.

### R-04 — Missing migration directory
**Probability:** Certain until created.
**Impact:** `NoSuchFileException` at startup.
**Mitigation:** Create `src/main/resources/db/migration/` before adding the dependency.

---

## 5. Affected Files

### Backend — modified

| File | Change |
|------|--------|
| `pom.xml` | Add `flyway-core` dependency (managed version from Spring Boot BOM) |
| `src/main/resources/application.properties` | Change `ddl-auto=update` → `ddl-auto=validate`; add 3 Flyway properties |
| `src/test/resources/application-test.properties` | Add `spring.flyway.enabled=false` |

### Backend — created

| File | Content |
|------|---------|
| `src/main/resources/db/migration/V1__baseline.sql` | Exact DDL of the current 6 tables, derived from entity files above |

### Frontend — none

### Database — none at runtime
The V1 SQL runs on **fresh** environments only. On the existing running database, Flyway baselines (marks V1 as applied without executing it) because `baseline-on-migrate=true`.

---

## 6. New / Removed Entities

None. No Java entity files are touched in this sprint.

---

## 7. Affected Endpoints

None. No controller, service, or repository files are touched.

---

## 8. Affected Screens

None.

---

## 9. Rollback Strategy

If anything goes wrong after applying:

1. Revert `application.properties`: restore `spring.jpa.hibernate.ddl-auto=update`, remove Flyway properties.
2. Remove `flyway-core` from `pom.xml`.
3. Delete `src/main/resources/db/migration/V1__baseline.sql`.
4. If Flyway already created `flyway_schema_history` in the DB: `DROP TABLE flyway_schema_history CASCADE;`
5. Application returns to previous state: `ddl-auto=update`, no migration tracking.

All rollback steps are reversible within 5 minutes.

---

## 10. Exact Implementation Order

1. Add `db/migration/` directory under `src/main/resources/`.
2. Write `V1__baseline.sql` with the DDL of the current 6 tables.
3. Add `flyway-core` to `pom.xml`.
4. Update `application.properties`:
   - `spring.jpa.hibernate.ddl-auto=validate`
   - `spring.flyway.locations=classpath:db/migration`
   - `spring.flyway.baseline-on-migrate=true`
   - `spring.flyway.baseline-version=1`
5. Update `application-test.properties`:
   - `spring.flyway.enabled=false`
6. Run `./mvnw clean compile` — must succeed.
7. Run `./mvnw test` — all 54 tests must pass.
8. Start the application against the running PostgreSQL container — must start without errors.
9. Verify `flyway_schema_history` contains one row with `version=1, success=true`.

---

## 11. V1 Baseline SQL — Preview

The following is the DDL that V1 will contain. It is derived exclusively from the current entity files — not from `Esquema_BD.sql`. It documents where the project is **now**, not where it must go.

```sql
-- V1__baseline.sql
-- Snapshot of the database schema as created by Hibernate ddl-auto=update
-- from the current entity files (Sprint 0: 2026-06-28).
-- This migration runs only on fresh installations.
-- On existing databases, Flyway baselines this version without executing it.

CREATE TABLE IF NOT EXISTS usuario (
    id            BIGSERIAL    PRIMARY KEY,
    email         VARCHAR(150) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    nombre        VARCHAR(100) NOT NULL,
    rol_id        INTEGER      NOT NULL,
    activo        BOOLEAN      NOT NULL DEFAULT true,
    creado_en     TIMESTAMP    NOT NULL
);

CREATE TABLE IF NOT EXISTS huesped (
    id               BIGSERIAL   PRIMARY KEY,
    primer_nombre    VARCHAR(100) NOT NULL,
    apellido         VARCHAR(100) NOT NULL,
    tipo_documento   VARCHAR(50)  NOT NULL,
    numero_documento VARCHAR(50)  NOT NULL UNIQUE,
    email            VARCHAR(150),
    telefono         VARCHAR(30),
    creado_en        TIMESTAMP    NOT NULL
);

CREATE TABLE IF NOT EXISTS propiedad (
    id              BIGSERIAL     PRIMARY KEY,
    nombre          VARCHAR(150)  NOT NULL UNIQUE,
    direccion       VARCHAR(250)  NOT NULL,
    ciudad          VARCHAR(100)  NOT NULL,
    capacidad       INTEGER       NOT NULL,
    precio_por_noche DECIMAL(15,2) NOT NULL,
    creado_en       TIMESTAMP     NOT NULL
);

CREATE TABLE IF NOT EXISTS reserva (
    id                  BIGSERIAL   PRIMARY KEY,
    huesped_id          BIGINT      NOT NULL,
    propiedad_id        BIGINT      NOT NULL,
    fecha_inicio        DATE        NOT NULL,
    fecha_fin           DATE        NOT NULL,
    cantidad_huespedes  INTEGER     NOT NULL,
    estado              VARCHAR(20) NOT NULL,
    creado_en           TIMESTAMP   NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_reserva_propiedad_id ON reserva(propiedad_id);

CREATE TABLE IF NOT EXISTS factura (
    id          BIGSERIAL     PRIMARY KEY,
    reserva_id  BIGINT        NOT NULL UNIQUE,
    subtotal    DECIMAL(15,2) NOT NULL,
    iva         DECIMAL(15,2) NOT NULL,
    total       DECIMAL(15,2) NOT NULL,
    estado      VARCHAR(20)   NOT NULL,
    version     BIGINT,
    creado_en   TIMESTAMP     NOT NULL
);

CREATE TABLE IF NOT EXISTS pago (
    id           BIGSERIAL     PRIMARY KEY,
    factura_id   BIGINT        NOT NULL UNIQUE,
    monto        DECIMAL(15,2) NOT NULL,
    metodo_pago  VARCHAR(20)   NOT NULL,
    referencia   VARCHAR(100),
    pagado_en    TIMESTAMP     NOT NULL,
    version      BIGINT,
    creado_en    TIMESTAMP     NOT NULL
);
```

---

## 12. Acceptance Criteria

- [ ] `./mvnw clean compile` exits with `BUILD SUCCESS`
- [ ] `./mvnw test` exits with `BUILD SUCCESS`, 54 tests, 0 failures
- [ ] Application starts against Docker PostgreSQL without errors
- [ ] `SELECT * FROM flyway_schema_history` returns exactly 1 row: `version=1, description=baseline, success=true`
- [ ] No entity file was modified
- [ ] No controller, service, repository, or DTO file was modified
- [ ] No frontend file was modified
- [ ] `DevelopmentDataSeeder` still compiles (not yet disabled)

---

## 13. What Sprint 0 Does NOT Solve

This sprint establishes control over schema evolution. It does not correct any schema errors. The following known problems are deferred to their respective sprints:

| Problem | Deferred to |
|---------|-------------|
| `huesped` table has no official counterpart | Sprint 3 |
| `pago` table has no official counterpart | Sprint 4 |
| `propiedad` has 3 invented columns | Sprint 2 |
| `reserva` missing 5 required FKs | Sprint 3 |
| `factura.iva` should be named `impuestos` | Sprint 5 |
| Reservation status values in English | Sprint 3 |
| Invoice status values in English | Sprint 5 |
| `DevelopmentDataSeeder` needs disabling | Sprint 2 |
