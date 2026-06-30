# Sprint 3 — Informe de Implementación

**Proyecto:** NovaFacts — Sistema de gestión financiera de alquileres a corto plazo  
**Sprint:** 3  
**Fecha:** 2026-06-28  
**Estado:** COMPLETADO ✓

---

## Objetivos del Sprint

1. Reconstruir el módulo `reserva` alineándolo al esquema real de `Esquema_BD.sql`.
2. Crear el módulo `politica_cancelacion` (políticas de cancelación por propiedad).
3. Erradicar permanentemente el módulo `huesped` (Guest) de la BD, el backend y el frontend.

---

## Base de datos — V4__reserva_rebuild_y_politica.sql

### Estrategia TRUNCATE + NOT NULL

Al agregar columnas `NOT NULL` a `reserva` con filas existentes y con FK hacia tablas que pueden estar vacías (`canal`, `temporada`, `politica_cancelacion`), se produce un problema de bootstrap: no hay valores válidos para satisfacer las restricciones al mismo tiempo que se crean las tablas referenciadas.

**Solución adoptada:** `TRUNCATE TABLE reserva RESTART IDENTITY` antes de agregar las columnas. En un proyecto académico sin datos de producción que preservar, esto es seguro y elimina el problema de raíz.

### Tabla nueva: `politica_cancelacion`

| Columna               | Tipo            | Restricción   |
|-----------------------|-----------------|---------------|
| id                    | INT IDENTITY    | PK            |
| propiedad_id          | BIGINT          | NOT NULL, FK → propiedad(id) |
| nombre                | VARCHAR(150)    | NOT NULL      |
| descripcion           | TEXT            |               |
| porcentaje_reembolso  | DECIMAL(5,2)    | NOT NULL      |
| dias_aviso            | INT             | NOT NULL      |

> **Nota de tipo:** `propiedad_id` es `BIGINT` porque `propiedad.id` es `BIGSERIAL` (BIGINT en PostgreSQL). Las FK deben tener el mismo tipo exacto.

### Cambios en `reserva`

- **Eliminada:** columna `huesped_id`
- **Agregadas:** `canal_id`, `temporada_id`, `politica_cancelacion_id`, `usuario_creador_id`, `cliente_nombre`, `cliente_email`, `cliente_telefono`, `monto_total`
- **Eliminada al final:** `DROP TABLE huesped CASCADE`

---

## Backend — Cambios implementados

### Módulo eliminado: `guest/`

Eliminados completamente:
- `Guest.java`, `GuestRepository.java`, `GuestService.java`, `GuestController.java`
- `CreateGuestRequest.java`, `GuestResponse.java`, `UpdateGuestRequest.java`

`DevelopmentDataSeeder` fue limpiado — las referencias a `GuestRepository` y `Guest` se eliminaron (el seeder ya estaba desactivado con `@Profile("never")`).

### Módulo nuevo: `politicacancelacion/`

- **Entidad:** `PoliticaCancelacion` — `@ManyToOne` hacia `Property`
- **Repositorio:** `PoliticaCancelacionRepository` con `findByPropiedadId(Long)`
- **DTOs:** `PoliticaCancelacionRequest` (con validaciones `@Valid`), `PoliticaCancelacionResponse` (flat)
- **Servicio:** `PoliticaCancelacionService` — CRUD completo; `eliminar()` deja que la FK violation propagada por Spring sea manejada por `GlobalExceptionHandler` → 409
- **Controlador:** `PoliticaCancelacionController` — REST en `/api/politicas`

### Módulo modificado: `reservation/`

**Entidad `Reservation`:**
- Cuatro nuevas asociaciones `@ManyToOne(FetchType.EAGER)`: `canal`, `temporada`, `politicaCancelacion`, `usuarioCreador`
- Datos de cliente embebidos: `clienteNombre`, `clienteEmail`, `clienteTelefono`
- Campo financiero: `montoTotal`
- Eliminada: referencia a `huesped`

**`ReservationService.create()` — extracción segura del usuario:**

```java
String email = SecurityContextHolder.getContext().getAuthentication().getName();
User usuarioCreador = userRepository.findByUsername(email)
    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
        "Usuario autenticado no encontrado en el sistema"));
```

El token JWT ya fue validado por el filtro de seguridad antes de llegar al servicio; el `username` del `SecurityContext` es confiable. **Nunca se acepta un `userId` del frontend.**

**Validación politica–propiedad:**

```java
if (!politica.getPropiedad().getId().equals(propertyId)) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
        "La política de cancelación no corresponde a la propiedad seleccionada");
}
```

### DashboardService

`totalGuests` ahora es `reservationRepository.count()` — semántica: un cliente por reserva, ya que la tabla `huesped` no existe.

### SecurityConfig

Rutas `POST/PUT/DELETE /api/politicas/**` restringidas al rol `ROLE_ADMINISTRADOR`.

---

## Tests — Verificación

### Resultado final

```
Tests run: 54, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### Tests modificados

| Archivo | Cambio |
|---|---|
| `ReservationControllerTest` | Reescritura completa; setUp crea Rol→User(username="user")→Canal→Temporada→Property→PoliticaCancelacion |
| `DashboardControllerTest` | Eliminado GuestRepository; `totalGuests = 4` (reservationRepository.count()) |
| `InvoiceControllerTest` | Eliminado GuestRepository; setUp crea toda la cadena FK |
| `PaymentControllerTest` | Mismo patrón que InvoiceControllerTest |
| `PropertyControllerTest` | setUp agrega `reservationRepository.deleteAll()` y `politicaRepository.deleteAll()` antes de `propertyRepository.deleteAll()` para respetar el orden FK |

**Patrón `@WithMockUser` + User real:**  
`@WithMockUser(username = "user")` pone "user" en el `SecurityContext`. `ReservationService.create()` llama `userRepository.findByUsername("user")`. Por eso el `setUp` crea un `User` con `username = "user"` — sin esto, el servicio lanzaría 401.

---

## Frontend — Cambios implementados

### Archivos eliminados

- `views/GuestsView.vue`
- `services/guestService.ts`
- `types/guest.ts`

### Archivos nuevos

- `types/politicaCancelacion.ts` — interfaces `PoliticaCancelacion` y `PoliticaCancelacionRequest`
- `services/politicaCancelacionService.ts` — `getPoliticas`, `getPoliticasByPropiedad`, `createPolitica`, `updatePolitica`, `deletePolitica`
- `views/PoliticasView.vue` — tabla con filtro por propiedad; modales crear/editar/eliminar; controles CRUD solo visibles para Administrador

### Archivos modificados

| Archivo | Cambio |
|---|---|
| `types/reservation.ts` | Eliminado `guestId`; agregados `canalId/Nombre`, `temporadaId/Nombre`, `politicaCancelacionId/Nombre`, `usuarioCreadorId/Nombre`, `clienteNombre/Email/Telefono`, `montoTotal` |
| `views/ReservationsView.vue` | Eliminado dropdown de huésped; agregados dropdowns de canal, temporada, politica; `watch(propertyId)` recarga políticas del servidor al cambiar propiedad; tabla muestra `clienteNombre` y `montoTotal` |
| `views/DashboardView.vue` | Eliminado `getGuests()` y `guestMap`; `propertyMap` usa solo `p.name`; columna "Huésped" → "Cliente" en tabla reciente; stat card "Huéspedes" → "Clientes" |
| `router/index.js` | Eliminada ruta `/guests`; agregada ruta `/politicas` |
| `components/AppNav.vue` | Eliminado link "Huéspedes"; agregado link "Políticas" |

### Build frontend

```
✓ built in 423ms — 0 warnings
```

---

## Lista de verificación final

- [x] `./mvnw clean test` → BUILD SUCCESS (54/54 tests)
- [x] `npm run build` → BUILD SUCCESS (0 warnings)
- [x] Tabla `huesped` eliminada de la BD (`DROP TABLE huesped CASCADE`)
- [x] Módulo `guest/` eliminado del backend
- [x] Archivos `GuestsView.vue`, `guestService.ts`, `guest.ts` eliminados del frontend
- [x] `usuario_creador` extraído del `SecurityContext` (no del body del request)
- [x] `politica_cancelacion` verificada contra `propiedad` de la reserva
- [x] RBAC: POST/PUT/DELETE de políticas restringido a `ROLE_ADMINISTRADOR`
- [x] Todos los mensajes de validación en español
- [x] Controllers no exponen entidades JPA (solo DTOs)
- [x] Módulos Invoice y Penalty no modificados
- [x] `DevelopmentDataSeeder` mantiene `@Profile("never")`
