# Sprint 6 Report — System Stabilization
**Proyecto:** NovaFacts  
**Fecha:** 2026-06-29  
**Autor:** Julian Andres Foglia Wilches

---

## Objetivo

Estabilizar el sistema antes del QA final mediante tres mejoras transversales:

1. **Soft-delete de usuarios** — proteger la integridad referencial histórica.  
2. **Paginación backend** — preparar los endpoints de listado para volúmenes reales.  
3. **Development Data Seeder** — re-habilitar la siembra de datos de prueba determinista.

---

## Resumen de cambios

### 1. Soft-Delete de Usuarios

**Problema:** `UserService.deleteUser()` llamaba `repository.deleteById()`, eliminando físicamente al usuario y rompiendo todas las FK que apuntan a `usuario_id` en `reserva`, `anticipo`, `penalidad`, `factura` y `devolucion`.

**Solución:**
- `UserService.deleteUser(id)` ahora busca el usuario, invoca `setActivo(false)` y guarda.
- `UserDetailsServiceImpl.loadUserByUsername()` construye el `UserDetails` con `.disabled(!activo)` — Spring Security rechaza automáticamente las solicitudes con el token de un usuario desactivado.
- `UserService.login()` verifica `activo == false` antes de generar el JWT, retornando `401 Unauthorized` con el mensaje _"Cuenta de usuario desactivada"_.
- `UserResponse` ahora expone el campo `activo: Boolean` para que el frontend muestre el estado.

**Archivos modificados (backend):**
- `auth/dto/UserResponse.java`
- `auth/service/UserService.java`
- `auth/service/UserDetailsServiceImpl.java`
- `auth/controller/UserController.java`

**Archivos modificados (frontend):**
- `src/services/userService.ts` — interfaz `UserResponse` incluye `activo`; nueva función `deleteUsuario(id)`.
- `src/views/UsuariosView.vue` — columna "Acciones" con botón "Desactivar" (oculto si ya inactivo); actualización reactiva del estado local sin recarga.

---

### 2. Paginación Backend

**Diseño:** Se creó `PageResponse<T>` (`common/PageResponse.java`), un DTO inmutable que envuelve un `Page<T>` de Spring Data y expone únicamente los campos necesarios para el cliente:

```json
{
  "content": [...],
  "page": 0,
  "size": 20,
  "totalElements": 42,
  "totalPages": 3,
  "last": false
}
```

Los repositorios ya heredan `findAll(Pageable)` de `JpaRepository` — no se necesitaron cambios en la capa de repositorios.

**Endpoints paginados:**

| Endpoint | Orden por defecto | Parámetros |
|---|---|---|
| `GET /api/facturas` | `emitidaEn DESC` | `page=0`, `size=20` |
| `GET /api/reservations` | `checkIn DESC` | `page=0`, `size=20` |
| `GET /api/usuarios` | `nombre ASC` | `page=0`, `size=20` |

**Archivos modificados (backend):**
- `common/PageResponse.java` _(nuevo)_
- `factura/service/FacturaService.java`
- `factura/controller/FacturaController.java`
- `reservation/service/ReservationService.java`
- `reservation/controller/ReservationController.java`
- `auth/service/UserService.java`
- `auth/controller/UserController.java`

**Archivos modificados (frontend):**
- `src/services/facturaService.ts` — `getFacturas()` lee `response.data.content` con `?page=0&size=50`.
- `src/services/reservationService.ts` — `getReservations()` lee `response.data.content` con `?page=0&size=50`.
- `src/services/userService.ts` — `getUsuarios()` lee `response.data.content` con `?page=0&size=50`.

**Test actualizado:**
- `FacturaControllerTest.get_all_facturas_returns_list` — aserciones cambiadas de `$.length()` a `$.content.length()` y `$.totalElements`.

---

### 3. Development Data Seeder

**Problema:** `DevelopmentDataSeeder` tenía `@Profile("never")` y un `run()` vacío.

**Solución:** Reescrito con `@Profile("dev")`, lógica idempotente y semilla aleatoria fija.

**Características:**
- **Activación:** solo corre bajo el perfil `dev` (no afecta `test` ni `prod`).
- **Idempotencia:** si `reservationRepository.count() > 0`, omite la siembra por completo.
- **Determinismo:** usa `new Random(42)` (constante de clase) para cualquier valor pseudoaleatorio futuro.
- **No altera el esquema:** no crea migraciones Flyway; usa las tablas/columnas existentes.

**Datos sembrados:**

| Entidad | Cantidad |
|---|---|
| Usuarios | 4 (admin, contador, auxiliar, recepcionista) |
| Temporadas | 2 (Alta 2025, Baja 2025) |
| Propiedades | 3 |
| Políticas de cancelación | 3 (una por propiedad) |
| Reservas | 8 (3 COMPLETED, 3 CONFIRMED, 2 CANCELLED) |
| Anticipos | 5 |
| Penalidades | 2 (para reservas CANCELLED) |
| Facturas | 3 (2 PAID, 1 PENDING) |
| Devoluciones | 2 (1 procesada, 1 rechazada) |

**FK lookup:** Roles (Flyway V2 IDs 1–4) y Canales (Flyway V3) son consultados por ID/lista existente, no recreados.

**Archivos modificados (backend):**
- `config/DevelopmentDataSeeder.java` — reescritura completa.

---

## Verificación

### Backend

```
./mvnw clean test
Tests run: 69, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### Frontend

```
npm run build
✓ 143 modules transformed.
✓ built in 431ms
```

---

## Próximos pasos sugeridos

- Añadir un endpoint `GET /api/usuarios/{id}` para consulta individual.
- Implementar guards de autorización en el frontend (redirección si el token expira).
- Agregar soporte de filtros/búsqueda en los endpoints paginados (`findByEstado`, `findByClienteNombre`, etc.).
- Configurar el perfil `dev` en el `docker-compose.yml` para que el seeder corra automáticamente en ambientes de desarrollo.
