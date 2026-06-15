# 🛠️ Skill Review — Backend
### Proyecto NovaFacts · Spring Boot 3.5 + Java 21 + PostgreSQL
**Curso:** Ingeniería de Software 1 (2016701) — Universidad Nacional de Colombia  
**Fecha:** Junio 2025

---

## 1. Descripción de la Arquitectura

El backend sigue una arquitectura en capas clásica de Spring Boot, organizada por **feature packages** (en lugar de por tipo técnico). La única feature actual es `auth`, que agrupa todo lo relacionado con autenticación y gestión de usuarios.

```
com.novafacts.backend
│
├── BackendApplication.java          ← Punto de entrada Spring Boot
├── config/
│   └── SecurityConfig.java          ← Configuración de seguridad (BCrypt, CSRF, filtros)
│
└── auth/
    ├── controller/
    │   ├── AuthController.java       ← POST /api/auth/login
    │   └── UserController.java       ← POST /api/users  |  GET /api/users
    ├── service/
    │   └── UserService.java          ← Lógica de negocio: crear usuario, login, listar
    ├── repository/
    │   └── UserRepository.java       ← Acceso a datos (JpaRepository + findByUsername)
    ├── entity/
    │   └── User.java                 ← Entidad JPA mapeada a la tabla `users`
    └── dto/
        ├── CreateUserRequest.java
        ├── UserResponse.java
        ├── LoginRequest.java
        └── LoginResponse.java
```

**Dependencias principales:**

| Capa | Tecnología |
|---|---|
| Framework | Spring Boot 3.5 |
| Lenguaje | Java 21 |
| Persistencia | Spring Data JPA + PostgreSQL |
| Seguridad | Spring Security + BCrypt |
| Build | Maven |
| Utilidades | Lombok |

**Flujo de una petición típica:**
```
Cliente HTTP
    └─► Controller  (recibe y valida forma del request)
            └─► Service  (lógica de negocio, reglas)
                    └─► Repository  (acceso a BD)
                            └─► PostgreSQL
```

---

## 2. Buenas Prácticas de Desarrollo — Java / Spring Boot

### ✅ MUST HAVE (obligatorio)

| # | Regla | ¿Se cumple? | Evidencia / Observación |
|---|---|:---:|---|
| M1 | **Inyección de dependencias por constructor** (no por campo `@Autowired`) | ✅ | `AuthController`, `UserService` usan constructor explícito |
| M2 | **DTOs separados de las entidades** — nunca exponer la entidad JPA directamente | ✅ | `CreateUserRequest`, `UserResponse`, `LoginRequest`, `LoginResponse` |
| M3 | **Contraseñas hasheadas** — nunca guardar texto plano | ✅ | `BCryptPasswordEncoder` configurado en `SecurityConfig` y usado en `UserService` |
| M4 | **Capas separadas** Controller → Service → Repository | ✅ | Separación clara; el Controller no accede al Repository directamente |
| M5 | **Nombre de clases en PascalCase**, métodos y variables en camelCase | ✅ | Convención Java respetada en todo el código |
| M6 | **Manejo de Optional** al buscar por ID o campo único | ✅ | `UserRepository.findByUsername()` retorna `Optional<User>` |
| M7 | **Anotaciones de capa correctas** (`@RestController`, `@Service`, `@Repository`) | ✅ | Correctamente anotadas |
| M8 | **`@RequestMapping` en el controller** para definir la URL base del recurso | ✅ | `/api/auth` y `/api/users` correctamente definidos |
| M9 | **Sin lógica de negocio en el Controller** | ✅ | El Controller solo delega al Service |
| M10 | **Sin SQL manual** — usar Spring Data / JPA | ✅ | Solo se usa `JpaRepository` y query method derivado |

### 🟡 SHOULD HAVE (recomendado)

| # | Regla | ¿Se cumple? | Observación / Mejora sugerida |
|---|---|:---:|---|
| S1 | **Manejo de excepciones centralizado** con `@ControllerAdvice` | ⚠️ No | `UserService` lanza `RuntimeException` genérica; falta una clase `GlobalExceptionHandler` que devuelva respuestas HTTP con código apropiado (401, 404…) |
| S2 | **Validación de entrada** con `@Valid` y Bean Validation (`@NotBlank`, `@Size`) | ⚠️ No | `CreateUserRequest` y `LoginRequest` no tienen restricciones; un campo vacío llegaría al Service sin control |
| S3 | **Lombok en entidades y DTOs** para reducir boilerplate | ⚠️ Parcial | Lombok está en el `pom.xml` pero `User.java` y los DTOs escriben getters/setters manualmente; se podría usar `@Data`, `@Getter`, `@AllArgsConstructor` |
| S4 | **Configuración externalizada** en `application.properties` sin credenciales en código | ✅ | URL y credenciales de BD se leen de variables de entorno |
| S5 | **Nombre del artefacto y descripción en `pom.xml`** | ⚠️ No | Los campos `<name/>` y `<description/>` están vacíos |
| S6 | **CSRF deshabilitado justificado** (solo válido en APIs stateless con tokens) | ⚠️ Pendiente | CSRF está deshabilitado pero aún no hay JWT implementado; debe justificarse en comentario o implementar autenticación stateless |
| S7 | **Javadoc en métodos públicos de Service** | ⚠️ No | `UserService` no tiene ningún comentario que describa qué hace cada método |

---

## 3. Buenas Prácticas de Arquitectura

### ✅ MUST HAVE

| # | Regla | ¿Se cumple? | Evidencia |
|---|---|:---:|---|
| A1 | **Separación por capas** (Controller / Service / Repository / Entity / DTO) | ✅ | Estructura de paquetes lo refleja claramente |
| A2 | **Feature-based packaging** (agrupar por dominio, no por tipo técnico) | ✅ | Todo `auth/` agrupa controller + service + repo + entity + dto |
| A3 | **El Repository no contiene lógica de negocio** | ✅ | `UserRepository` solo extiende `JpaRepository` y declara un query method |
| A4 | **El Service es el único punto de acceso al Repository** | ✅ | Ningún Controller inyecta directamente el Repository |
| A5 | **Configuración de seguridad separada** en clase propia | ✅ | `SecurityConfig.java` aislada en `config/` |

### 🟡 SHOULD HAVE

| # | Regla | ¿Se cumple? | Observación |
|---|---|:---:|---|
| AR1 | **Manejo de errores HTTP semántico** (400, 401, 404, 500) | ⚠️ No | Actualmente todos los errores devuelven 500; falta `@ControllerAdvice` |
| AR2 | **Autenticación stateless con JWT** coherente con la desactivación de CSRF | ⚠️ Pendiente | `LoginResponse` retorna un mensaje de texto, no un token; el flujo de seguridad queda incompleto |
| AR3 | **Archivos de configuración duplicados eliminados** | ⚠️ No | Existe `com.novafacts.SecurityConfig` (raíz) Y `com.novafacts.backend.config.SecurityConfig`; el primero es un residuo que debería borrarse |
| AR4 | **`TestController` y `HelloController` eliminados en producción** | ⚠️ No | Hay dos controllers de prueba en el source principal; no deberían estar en `main/`, solo en `test/` |

---

## 4. Código Limpio

| Principio | ¿Se aplica? | Nota |
|---|:---:|---|
| Nombres descriptivos (clases, métodos, variables) | ✅ | `handleLoginSuccess`, `createUser`, `getUsers` son autoexplicativos |
| Métodos cortos y con una sola responsabilidad | ✅ | Ningún método supera 20 líneas |
| Sin código comentado ni dead code en producción | ⚠️ | `TestController.java` y `SecurityConfig` duplicado son dead code |
| Sin números mágicos o strings literales sueltos | ✅ | Rutas definidas en `@RequestMapping`, no hardcodeadas en lógica |
| Una clase = una responsabilidad (SRP) | ✅ | Cada clase tiene un rol claro y delimitado |

---

## 5. Resumen de Cumplimiento

```
MUST HAVE   ██████████████████░░  10/10 ✅  (100%)
SHOULD HAVE ████████░░░░░░░░░░░░   3/7  ⚠️  (43%)
```

> 💡 **Prioridad de mejora:** Implementar `@ControllerAdvice` para manejo de errores, agregar `@Valid` en los DTOs de entrada, y eliminar los archivos residuales (`SecurityConfig` duplicado, `TestController`, `HelloController`).
