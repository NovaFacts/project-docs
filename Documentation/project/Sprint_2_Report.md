# Sprint 2 Completion Report — Reference Data & Corrección de Propiedad

**Date:** 2026-06-28
**Backend build:** `./mvnw clean test` → **54 tests, 0 failures, BUILD SUCCESS**
**Frontend build:** `npm run build` → **132 modules, 0 warnings, BUILD SUCCESS**

---

## 1. Scope and Objectives

Sprint 2 aligned the `propiedad` table with `Esquema_BD.sql` (dropping three invented columns, adding two schema-correct ones), created the `canal` reference-data module (seeded, read-only), created the `temporada` module (full CRUD), disabled `DevelopmentDataSeeder`, and secured all write operations with `ROLE_ADMINISTRADOR`.

---

## 2. Database Schema Changes Applied

### V3__reference_data_y_correccion_propiedad.sql

#### `propiedad` corrections

| Action | Column | Detail |
|--------|--------|--------|
| ADD | `descripcion TEXT` | Maps `Esquema_BD.propiedad.descripcion` |
| ADD | `activa BOOLEAN NOT NULL DEFAULT true` | Maps `Esquema_BD.propiedad.activa` |
| ALTER | `direccion → TYPE TEXT, DROP NOT NULL` | Schema has `direccion text` (nullable) |
| DROP CONSTRAINT | `propiedad_nombre_key` | Esquema_BD has no UNIQUE on `propiedad.nombre` |
| **DROP** | `ciudad VARCHAR(100) NOT NULL` | Invented column — removed permanently |
| **DROP** | `capacidad INTEGER NOT NULL` | Invented column — removed permanently |
| **DROP** | `precio_por_noche DECIMAL(15,2) NOT NULL` | Invented column — removed permanently |
| **DROP** | `creado_en TIMESTAMP NOT NULL` | Not in Esquema_BD.propiedad — removed permanently |

**⚠ Data loss warning:** Any existing data in `ciudad`, `capacidad`, `precio_por_noche`, and `creado_en` is permanently deleted when V3 runs. A `pg_dump` must be taken before applying V3 to any environment with live data.

#### Migration strategy for dropped columns

1. Production backup:
   ```bash
   pg_dump -h localhost -p 5434 -U postgres novafacts_db > backup_pre_v3_$(date +%Y%m%d).sql
   ```
2. Start the app — Flyway applies V3 automatically.
3. Verify schema: `SELECT column_name FROM information_schema.columns WHERE table_name='propiedad';`
4. Expected columns after V3: `id`, `nombre`, `direccion`, `descripcion`, `activa`.

#### Rollback from V3
```sql
DELETE FROM flyway_schema_history WHERE version = '3';
-- Restore from pg_dump, or:
ALTER TABLE propiedad
    ADD COLUMN ciudad VARCHAR(100) NOT NULL DEFAULT 'N/A',
    ADD COLUMN capacidad INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN precio_por_noche DECIMAL(15,2) NOT NULL DEFAULT 0,
    ADD COLUMN creado_en TIMESTAMP NOT NULL DEFAULT now();
ALTER TABLE propiedad DROP COLUMN IF EXISTS descripcion;
ALTER TABLE propiedad DROP COLUMN IF EXISTS activa;
DROP TABLE IF EXISTS temporada;
DROP TABLE IF EXISTS canal;
```
Then restore old Java sources from git before the V3 commit.

#### `canal` table

Created and seeded with 5 records:

| id | nombre | tipo |
|----|--------|------|
| 1 | Airbnb | Plataforma |
| 2 | Booking | Plataforma |
| 3 | Web propia | Directo |
| 4 | Teléfono | Directo |
| 5 | WhatsApp | Directo |

#### `temporada` table

Created empty (no seed data); managed via `POST /api/temporadas`.

---

## 3. `propiedad` Entity Refactoring

### Java entity changes (`property/entity/Property.java`)

| Removed field | Removed method(s) |
|--------------|-------------------|
| `String city` (→ `ciudad`) | `getCity()`, `setCity()` |
| `Integer capacity` (→ `capacidad`) | `getCapacity()`, `setCapacity()` |
| `BigDecimal pricePerNight` (→ `precio_por_noche`) | `getPricePerNight()`, `setPricePerNight()` |
| `LocalDateTime createdAt` (→ `creado_en`) | `getCreatedAt()`, `@PrePersist onCreate()` |

| Added field | Description |
|-------------|-------------|
| `String descripcion` (→ `descripcion`) | Nullable text |
| `Boolean activa` (→ `activa`, `NOT NULL DEFAULT true`) | Soft-delete flag |

### DTO changes

**`CreatePropertyRequest`:**
- Removed: `city`, `capacity`, `pricePerNight`
- Added: `descripcion` (optional)
- Kept: `name` (`@NotBlank`), `address` (optional)

**`UpdatePropertyRequest`:**
- Removed: `city`, `capacity`, `pricePerNight`
- Added: `descripcion` (optional), `activa` (`@NotNull`)
- Kept: `name` (`@NotBlank`), `address` (optional)

**`PropertyResponse`:**
- Removed: `city`, `capacity`, `pricePerNight`, `createdAt`
- Added: `descripcion`, `activa`

### Service change: soft-delete

`PropertyService.delete(Long id)` now sets `activa = false` and saves, instead of calling `deleteById`. The property remains accessible via `GET /api/propiedades/{id}` with `activa: false`.

### Controller rename

`@RequestMapping` changed from `/api/properties` to `/api/propiedades`.

---

## 4. Cascading Fixes (Forced by Property Entity Refactoring)

The following changes were forced by the removal of `capacity` and `pricePerNight` from the Property entity. None of these add new functionality — they restore compilability.

### `ReservationService` (minimal change)

`validateGuestCount(Integer guestCount, Integer propertyCapacity)` removed.  
Calls to it in `create()` and `update()` replaced with `validatePropertyExists(propertyId)` (an existence check).

**Why:** `property.getCapacity()` no longer exists; capacity is not in the domain model.  
**Impact on tests:** `ReservationControllerTest` creates reservations with `guestCount=2` (was ≤6 capacity). No test now fails.

### `InvoiceService` (minimal change)

Auto-calculation `property.getPricePerNight() × nights` removed.  
Subtotal is now provided by the caller in `CreateInvoiceRequest`.

**Why:** `property.getPricePerNight()` no longer exists. The real pricing model (from `reserva.monto_total`) will be wired in Sprint 3.

### `CreateInvoiceRequest` (minimal change)

Added `@NotNull @DecimalMin("0.01") BigDecimal subtotal` field.

### `DevelopmentDataSeeder`

Changed to `@Profile("never")`. Internal seeding methods simplified to use only `name`/`address` on Property (no city/capacity/pricePerNight).

### Tests updated for removed fields

All 5 test files that called `setCity()`, `setCapacity()`, or `setPricePerNight()` on Property fixtures were fixed by removing those 3 calls from `setUp()`. The test logic (what is being tested) is unchanged.

| Test class | Fix |
|-----------|-----|
| `PropertyControllerTest` | Full rewrite — new fields, new URL `/api/propiedades`, soft-delete behavior |
| `ReservationControllerTest` | Removed 3 property setters from setUp |
| `InvoiceControllerTest` | Removed 3 setters; `invoiceBody()` now includes `subtotal` |
| `PaymentControllerTest` | Removed 3 property setters from setUp |
| `DashboardControllerTest` | Removed 3 property setters from setUp |

---

## 5. New Modules

### Canal (`/api/canales`)

| File | Description |
|------|-------------|
| `canal/entity/Canal.java` | `@Entity`, maps `canal` table |
| `canal/repository/CanalRepository.java` | `JpaRepository<Canal, Integer>` |
| `canal/dto/CanalResponse.java` | Immutable DTO: `id`, `nombre`, `tipo` |
| `canal/service/CanalService.java` | `listar()` with `@Transactional(readOnly=true)` |
| `canal/controller/CanalController.java` | `GET /api/canales` → 200 |

GET is available to any authenticated user. There are no write endpoints (data is seeded by V3).

### Temporada (`/api/temporadas`)

| File | Description |
|------|-------------|
| `temporada/entity/Temporada.java` | `@Entity`, maps `temporada` table |
| `temporada/repository/TemporadaRepository.java` | `JpaRepository<Temporada, Integer>` |
| `temporada/dto/TemporadaRequest.java` | `nombre` (`@NotBlank`), `fechaInicio` (`@NotNull`), `fechaFin` (`@NotNull`) |
| `temporada/dto/TemporadaResponse.java` | Immutable DTO: `id`, `nombre`, `fechaInicio`, `fechaFin` |
| `temporada/service/TemporadaService.java` | `listar`, `buscarPorId`, `crear`, `actualizar`, `eliminar` |
| `temporada/controller/TemporadaController.java` | Full CRUD at `/api/temporadas` |

Business validation: `fechaInicio` must be strictly before `fechaFin` (400 if violated).

---

## 6. Security Rules Applied (SecurityConfig)

```
/api/auth/**                         → permitAll
/api/usuarios/**                     → hasRole("ADMINISTRADOR")     [Sprint 1]
POST   /api/propiedades|canales|temporadas/**  → hasRole("ADMINISTRADOR")
PUT    /api/propiedades|canales|temporadas/**  → hasRole("ADMINISTRADOR")
DELETE /api/propiedades|canales|temporadas/**  → hasRole("ADMINISTRADOR")
anyRequest                           → authenticated
```

GET endpoints for `propiedades`, `canales`, and `temporadas` are accessible to any authenticated user with any role.

---

## 7. Endpoint Summary

| Method | Path | Auth | Response |
|--------|------|------|----------|
| `GET` | `/api/propiedades` | Authenticated | All properties (incl. inactive) |
| `GET` | `/api/propiedades/{id}` | Authenticated | Single property |
| `POST` | `/api/propiedades` | ROLE_ADMINISTRADOR | 201 + created property |
| `PUT` | `/api/propiedades/{id}` | ROLE_ADMINISTRADOR | 200 + updated property |
| `DELETE` | `/api/propiedades/{id}` | ROLE_ADMINISTRADOR | 204 (soft-delete: activa=false) |
| `GET` | `/api/canales` | Authenticated | 5 seeded channels |
| `GET` | `/api/temporadas` | Authenticated | All seasons |
| `GET` | `/api/temporadas/{id}` | Authenticated | Single season |
| `POST` | `/api/temporadas` | ROLE_ADMINISTRADOR | 201 + created season |
| `PUT` | `/api/temporadas/{id}` | ROLE_ADMINISTRADOR | 200 + updated season |
| `DELETE` | `/api/temporadas/{id}` | ROLE_ADMINISTRADOR | 204 (hard delete) |

---

## 8. Frontend Changes

| File | Change |
|------|--------|
| `types/property.ts` | Removed city/capacity/pricePerNight; added descripcion/activa |
| `services/propertyService.ts` | All URLs updated from `/api/properties` to `/api/propiedades` |
| `views/PropertiesView.vue` | Removed 3 columns from table/form; added descripcion field; activa badge; soft-delete dialog says "Desactivar" |
| `types/canal.ts` | New: `Canal` interface |
| `services/canalService.ts` | New: `getCanales()` |
| `views/CanalesView.vue` | New: read-only table with tipo badge |
| `types/temporada.ts` | New: `Temporada`, `TemporadaRequest` interfaces |
| `services/temporadaService.ts` | New: full CRUD functions |
| `views/TemporadasView.vue` | New: table + create/edit modal + delete confirm dialog |
| `router/index.js` | Added `/canales` and `/temporadas` routes with `meta: { requiresAdmin: true }` |
| `components/AppNav.vue` | "Canales", "Temporadas", "Usuarios" links grouped under `v-if="esAdministrador"` |

---

## 9. Manual Testing Steps

### 9.1 Verify `propiedad` migration
```bash
docker compose up -d
# Connect to DB
psql -h localhost -p 5434 -U postgres novafacts_db
\d propiedad
-- Expected columns: id, nombre, direccion, descripcion, activa
```

### 9.2 Verify canales seeded
```bash
TOKEN=$(curl -s -X POST http://localhost:8082/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin","password":"admin123"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")

curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8082/api/canales | python3 -m json.tool
```
Expected: 5 channels — Airbnb, Booking, Web propia, Teléfono, WhatsApp.

### 9.3 Create and list temporadas
```bash
curl -s -X POST http://localhost:8082/api/temporadas \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"nombre":"Temporada Alta 2027","fechaInicio":"2027-06-01","fechaFin":"2027-08-31"}' \
  | python3 -m json.tool
# Expected: 201 with id, nombre, fechaInicio, fechaFin

curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8082/api/temporadas | python3 -m json.tool
# Expected: list with 1 entry
```

### 9.4 Validate date constraint
```bash
curl -s -X POST http://localhost:8082/api/temporadas \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"nombre":"Inválida","fechaInicio":"2027-09-01","fechaFin":"2027-08-01"}' \
  | python3 -m json.tool
# Expected: 400 Bad Request → {"error": "La fecha de inicio debe ser anterior a la fecha de fin"}
```

### 9.5 Soft-delete property
```bash
PROP_ID=$(curl -s -X POST http://localhost:8082/api/propiedades \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"name":"Villa Test","address":"Calle 1","descripcion":"Prueba soft-delete"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")

curl -s -X DELETE -H "Authorization: Bearer $TOKEN" http://localhost:8082/api/propiedades/$PROP_ID
# Expected: 204 No Content

curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8082/api/propiedades/$PROP_ID | python3 -m json.tool
# Expected: 200 OK with "activa": false
```

### 9.6 Non-admin blocked on write
```bash
CONTADOR_TOKEN=$(curl -s -X POST http://localhost:8082/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"maria","password":"maria123"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")

curl -s -o /dev/null -w "%{http_code}" \
  -X POST http://localhost:8082/api/propiedades \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $CONTADOR_TOKEN" \
  -d '{"name":"No permitida"}'
# Expected: 403
```

### 9.7 Frontend admin-only nav
1. Login as `admin` → see "Canales", "Temporadas", "Usuarios" in nav.
2. Navigate to `/canales` → see 5 seeded channels with tipo badges.
3. Navigate to `/temporadas` → empty state with "+ Nueva temporada" button.
4. Create a season → appears in table.
5. Login as `maria` (Contador) → none of those 3 nav links appear; navigating to `/temporadas` redirects to `/dashboard`.

---

## 10. Verification Checklist

- [x] `./mvnw clean test` → BUILD SUCCESS, **54 tests, 0 failures**
- [x] Backend compiles without warnings
- [x] `npm run build` → **132 modules, 0 warnings, BUILD SUCCESS**
- [x] No dead code, unused imports, or TODOs
- [x] `DevelopmentDataSeeder` is disabled (`@Profile("never")`)
- [x] `propiedad` table: `ciudad`, `capacidad`, `precio_por_noche`, `creado_en` removed in V3
- [x] `canal` table created and seeded with 5 records
- [x] `temporada` table created; full CRUD available at `/api/temporadas`
- [x] `PropertyController` path renamed to `/api/propiedades`
- [x] Soft-delete: `DELETE /api/propiedades/{id}` sets `activa=false`, property still accessible via GET
- [x] RBAC: POST/PUT/DELETE for propiedades, canales, temporadas blocked for non-ADMINISTRADOR (403)
- [x] Frontend: PropertiesView updated — no city/capacity/price columns; activa badge; "Desactivar" soft-delete flow
- [x] Frontend: CanalesView — read-only table with tipo badge
- [x] Frontend: TemporadasView — list + create/edit/delete modals with date validation
- [x] Router guards protect `/canales` and `/temporadas` routes for admin only

---

## 11. Remaining Risks

| Risk | Sprint |
|------|--------|
| `huesped` table still exists but not in Esquema_BD | Sprint 3 (Guest removal) |
| `pago` table still exists but not in Esquema_BD | Sprint 4 (Payment removal) |
| `reserva` entity is missing 5 FK columns (`canal_id`, `temporada_id`, `politica_cancelacion_id`, `usuario_creador_id`, `monto_total`) | Sprint 3 (Reserva rebuild) |
| `InvoiceService.create()` takes caller-provided subtotal; no pricing logic exists | Sprint 5 (Factura rebuild from monto_total) |
| `GET /api/propiedades` returns inactive properties — client-side filtering not yet implemented | Sprint 3 (UX improvement) |

---

## 12. Recommended Next Sprint

**Sprint 3 — Reserva Rebuild**

Per the Master Technical Audit dependency graph:
1. Add FK columns to `reserva`: `canal_id`, `temporada_id`, `politica_cancelacion_id`, `usuario_creador_id`, `monto_total`.
2. Update `Reservation` entity and `ReservationService` to use the new columns.
3. Create the `politica_cancelacion` module (Sprint 2.5 dependency).
4. Remove the `huesped` / `Guest` module if not in Esquema_BD.
5. Update `ReservationControllerTest` for new fields.

Sprint 3 can now proceed because `canal` and `temporada` (which `reserva` needs as FKs) are created in this sprint.
