# Dashboard Implementation Report

**Date:** 2026-06-28  
**Backend test result:** 54 tests, 0 failures — **BUILD SUCCESS**  
**Frontend build result:** 117 modules transformed — **BUILD SUCCESS (0 warnings)**

---

## 1. Files Created

### Backend

| File | Purpose |
|---|---|
| `dashboard/dto/DashboardResponse.java` | Immutable DTO with 10 aggregated fields (9 counts + `BigDecimal totalRevenue`) |
| `dashboard/service/DashboardService.java` | Single `@Transactional(readOnly = true)` method; orchestrates 5 repositories |
| `dashboard/controller/DashboardController.java` | `GET /api/dashboard` → 200 OK with `DashboardResponse` |
| `src/test/.../dashboard/DashboardControllerTest.java` | 2 integration tests (empty DB → zeros; seeded data → correct aggregates) |

### Frontend

| File | Purpose |
|---|---|
| `src/types/dashboard.ts` | `DashboardStats` TypeScript interface mirroring the DTO |
| `src/services/dashboardService.ts` | `getDashboardStats()` — typed call through the central `api` Axios instance |

---

## 2. Files Modified

### Backend — repository additions only (no service or business logic changed)

| File | What changed |
|---|---|
| `reservation/repository/ReservationRepository.java` | Added `long countByStatus(ReservationStatus status)` |
| `invoice/repository/InvoiceRepository.java` | Added `long countByStatus(InvoiceStatus status)` + `InvoiceStatus` import |
| `payment/repository/PaymentRepository.java` | Added `@Query("SELECT SUM(p.amount) FROM Payment p") BigDecimal sumTotalRevenue()` + `@Query` / `BigDecimal` imports |

All three are pure additions — no existing method was altered.

### Frontend — single file replaced

| File | What changed |
|---|---|
| `src/views/DashboardView.vue` | Replaced the two-line placeholder with the full dashboard implementation |

No other frontend files were modified.

---

## 3. API Changes

### New endpoint

```
GET /api/dashboard
Authorization: Bearer <token>
200 OK

{
  "totalGuests": 12,
  "totalProperties": 5,
  "confirmedReservations": 8,
  "cancelledReservations": 3,
  "completedReservations": 4,
  "pendingInvoices": 2,
  "paidInvoices": 6,
  "cancelledInvoices": 1,
  "totalPayments": 6,
  "totalRevenue": 8568000
}
```

No existing endpoints were modified or removed.

---

## 4. Architectural Decisions

### Single dedicated endpoint (`GET /api/dashboard`)

The alternative (fetching from 5 existing list endpoints in the frontend and computing counts client-side) was rejected because:
- It transfers significantly more data (full entity lists vs. 10 numbers)
- It duplicates aggregation logic in the client
- It issues 5 parallel HTTP requests instead of 1
- Future optimisation (database-level `COUNT` or a materialized view) would require no frontend change

### `@Transactional(readOnly = true)` on the service method

All five repository calls happen in a single read-only transaction, guaranteeing a consistent snapshot across all counts without locking overhead.

### `SUM` query instead of `count()` on Payment

`totalRevenue` requires summing `amount` values — not counting rows. A custom JPQL `@Query("SELECT SUM(p.amount) FROM Payment p")` is used. `SUM` of an empty result set returns `null` in JPQL; the service guards this with `rawRevenue != null ? rawRevenue : BigDecimal.ZERO`.

### `countByStatus` as Spring Data derived queries

Both `ReservationRepository.countByStatus` and `InvoiceRepository.countByStatus` use the Spring Data naming convention — no JPQL annotation needed. Spring generates the equivalent `SELECT count(*) WHERE status = ?` automatically.

### Frontend: `useAsyncState` single instance

Dashboard has only one async operation (load). One `useAsyncState()` instance is sufficient, following the minimum-instances-needed rule.

```typescript
const { loading: isLoading, error: errorMessage, run } = useAsyncState();
```

The retry button calls `load()` which calls `run()` again.

### Four section groups with `auto-fill` grid

Cards are grouped into four labelled sections (General, Reservas, Facturas, Financiero) rather than one flat 10-column grid. This gives the dashboard semantic structure at a glance and avoids a single overwhelming row.

Each section uses `grid-template-columns: repeat(auto-fill, minmax(180px, 1fr))` — one CSS rule handles all screen sizes without explicit breakpoints: 5 columns at 1200 px, down to 2 on a 400 px phone.

### Revenue card visual distinction

The `totalRevenue` card uses a light-green background (`#f0fdf4`) and a slightly smaller value font (`1.5rem`) to accommodate the currency string, while remaining visually heavier than plain count cards. No extra component was created — it is a modifier class on the same `stat-card` element.

### Scoped styles in DashboardView

The card grid CSS (`.stats-grid`, `.stat-card`, `.stat-card--*`, `.stat-label`, `.stat-value`, `.section-label`) is scoped to `DashboardView.vue`. These are dashboard-specific layout classes — adding them to `shared.css` would pollute the global namespace with rules that benefit only one view.

Generic shared classes (`.page-main`, `.state-box`, `.spinner`, `.btn`, `.btn--ghost`) come from `shared.css` as in every other module.

### No extra navigation change

`/dashboard` was already registered in the router and linked in `AppNav.vue` from a previous session. No changes to routing or navigation were required.

---

## 5. Build and Test Results

### Backend

```
[INFO] Running com.novafacts.backend.dashboard.DashboardControllerTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0

[INFO] Tests run: 54, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Test `getDashboard_emptyDatabase_returnsAllZeros`: verifies all 10 fields are 0 when the database is clean.

Test `getDashboard_withSeedData_returnsCorrectAggregates`: seeds 3 guests, 2 properties, 4 reservations (2 CONFIRMED + 1 CANCELLED + 1 COMPLETED), 3 invoices (1 PENDING + 1 PAID + 1 CANCELLED), 1 payment of COP 476 000; verifies every field in the response.

All 52 previously existing tests continue to pass.

### Frontend

```
vite v8.0.16 building client environment for production...
✓ 117 modules transformed.

dist/assets/index-DwZJ6EHr.css    13.48 kB │ gzip: 2.85 kB
dist/assets/index-DWZ7NG2Z.js    180.47 kB │ gzip: 62.74 kB

✓ built in 320ms
```

+2 modules over the previous build (115). Zero TypeScript errors. Zero Vite warnings.

---

## 6. Assumptions

1. **`BigDecimal` serializes as a JSON number.** Jackson's default `ObjectMapper` serializes `BigDecimal` as a numeric literal (e.g., `476000.00` or `476000`). The frontend TypeScript type uses `number`, which handles both. No custom serializer is needed.

2. **`totalRevenue` = sum of payment amounts.** Each payment's `amount` field is copied from `invoice.total` at creation time (no discounts are applied post-creation). Summing payment amounts is therefore equivalent to summing paid invoice totals.

3. **Counts are point-in-time.** The endpoint does not cache or aggregate incrementally. Every request runs fresh `COUNT`/`SUM` queries in a read-only transaction. For the expected data volumes (class project), this is adequate; a caching layer can be added later without changing the frontend contract.

4. **No authentication change required.** `GET /api/dashboard` falls under `anyRequest().authenticated()` in `SecurityConfig`. The JWT filter passes it through correctly, matching all other protected endpoints.

5. **`DashboardView` does not need `AppModal`.** The dashboard is read-only — no create/edit/delete flows. No modal component is imported.
