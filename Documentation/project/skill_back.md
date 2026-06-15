# Skill Review — Backend  
### Proyecto NovaFacts · Spring Boot 3.5 + Java 21 + PostgreSQL  
**Curso:** Ingeniería de Software 1 (2016701) — Universidad Nacional de Colombia  
**Fecha:** Junio 2025  

---

## 1. Descripción de la Arquitectura

El backend está organizado en capas siguiendo el modelo clásico de Spring Boot, pero agrupando por funcionalidades (feature packages). Por ahora solo existe la funcionalidad de `auth`, que concentra todo lo relacionado con autenticación y gestión de usuarios.


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
- Spring Boot 3.5  
- Java 21  
- Spring Data JPA + PostgreSQL  
- Spring Security + BCrypt  
- Maven  
- Lombok  

**Flujo de una petición típica:**  
Cliente HTTP → Controller → Service → Repository → PostgreSQL  

---

## 2. Buenas Prácticas de Desarrollo — Java / Spring Boot

### Obligatorio (MUST HAVE)

| # | Regla | Cumplimiento | Evidencia |
|---|---|:---:|---|
| M1 | Inyección de dependencias por constructor | Sí | `AuthController`, `UserService` |
| M2 | DTOs separados de entidades | Sí | `CreateUserRequest`, `UserResponse`, etc. |
| M3 | Contraseñas hasheadas | Sí | BCrypt en `SecurityConfig` y `UserService` |
| M4 | Capas separadas | Sí | Controller → Service → Repository |
| M5 | Convenciones de nombres | Sí | PascalCase y camelCase |
| M6 | Uso de Optional | Sí | `findByUsername()` retorna `Optional<User>` |
| M7 | Anotaciones correctas | Sí | `@RestController`, `@Service`, `@Repository` |
| M8 | `@RequestMapping` en controllers | Sí | `/api/auth`, `/api/users` |
| M9 | Sin lógica en Controller | Sí | Solo delega al Service |
| M10 | Sin SQL manual | Sí | Solo `JpaRepository` |

### Recomendado (SHOULD HAVE)

| # | Regla | Cumplimiento | Observación |
|---|---|:---:|---|
| S1 | Manejo de excepciones centralizado | No | Falta `GlobalExceptionHandler` |
| S2 | Validación de entrada con `@Valid` | No | DTOs sin restricciones |
| S3 | Uso de Lombok en entidades/DTOs | Parcial | Getters/setters escritos a mano |
| S4 | Configuración externalizada | Sí | BD desde variables de entorno |
| S5 | Artefacto y descripción en `pom.xml` | No | Campos vacíos |
| S6 | CSRF deshabilitado justificado | Pendiente | No hay JWT aún |
| S7 | Javadoc en métodos públicos | No | `UserService` sin comentarios |

---

## 3. Buenas Prácticas de Arquitectura

### Obligatorio

| # | Regla | Cumplimiento | Evidencia |
|---|---|:---:|---|
| A1 | Separación por capas | Sí | Paquetes reflejan la estructura |
| A2 | Feature-based packaging | Sí | `auth/` agrupa todo |
| A3 | Repository sin lógica | Sí | Solo extiende `JpaRepository` |
| A4 | Service como único acceso al Repository | Sí | Controllers no inyectan repos |
| A5 | Configuración de seguridad separada | Sí | `SecurityConfig.java` en `config/` |

### Recomendado

| # | Regla | Cumplimiento | Observación |
|---|---|:---:|---|
| AR1 | Manejo de errores HTTP semántico | No | Todos devuelven 500 |
| AR2 | Autenticación stateless con JWT | Pendiente | `LoginResponse` no devuelve token |
| AR3 | Config duplicada eliminada | No | Dos `SecurityConfig` en el proyecto |
| AR4 | Controllers de prueba eliminados | No | `TestController` y `HelloController` siguen en `main/` |

---

## 4. Código Limpio

- Nombres descriptivos: Sí  
- Métodos cortos y con una sola responsabilidad: Sí  
- Sin código comentado ni duplicado: No (controllers de prueba y config duplicada)  
- Sin números mágicos: Sí  
- Una clase = una responsabilidad: Sí  

---

## 5. Resumen de Cumplimiento

- MUST HAVE: 10/10 (100%)  
- SHOULD HAVE: 3/7 (43%)  

