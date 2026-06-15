# Skill Review — Frontend  
### Proyecto NovaFacts · Vue 3 + Vite + Vue Router + Axios  
**Curso:** Ingeniería de Software 1 (2016701) — Universidad Nacional de Colombia  
**Fecha:** Junio 2025  

---

## 1. Descripción de la Arquitectura

El frontend es una SPA (Single Page Application) construida con Vue 3 usando la Composition API (`<script setup>`). La navegación se maneja con Vue Router y las llamadas HTTP se centralizan en una capa de servicios.



```
frontend/src
│
├── main.js                  ← Punto de entrada: monta la app, registra router
├── App.vue                  ← Shell de la aplicación (solo contiene <router-view />)
├── style.css                ← Reset y estilos globales
│
├── router/
│   └── index.js             ← Definición de rutas: / (login) y /secret/:phrase?
│
├── services/
│   └── authService.js       ← Capa HTTP: instancia Axios + función login()
│
├── views/                   ← Páginas completas (una por ruta)
│   ├── LoginView.vue        ← Contenedor de la vista login, maneja navegación
│   └── SecretView.vue       ← Muestra la frase secreta, protege acceso directo
│
└── components/              ← Componentes reutilizables (UI puro)
    ├── LoginForm.vue        ← Formulario de login con validación y estado de carga
    └── SecretDisplay.vue    ← Visualización de la frase + botón logout
```

**Dependencias principales:**

| Rol | Tecnología |
|---|---|
| Framework UI | Vue 3 (Composition API) |
| Bundler | Vite 8 |
| Enrutamiento | Vue Router 5 |
| HTTP Client | Axios 1.x |
| Lenguaje | JavaScript (ES Modules) |

**Flujo de datos:**

```
Usuario
  └─► LoginForm.vue  (estado local: correo, password, loading, errorMessage)
          └─► authService.js  (llama a POST /api/login/)
                  └─► Backend Spring Boot
          └─► emite 'login-success' con secretPhrase
  └─► LoginView.vue  (recibe el evento, usa router.push a /secret)
  └─► SecretView.vue  (lee phrase del query, protege acceso sin login)
          └─► SecretDisplay.vue  (muestra la frase, emite 'logout')
```

---

**Dependencias principales:**  
- Vue 3 (Composition API)  
- Vite 8  
- Vue Router 5  
- Axios 1.x  
- JavaScript (ES Modules)  

**Flujo de datos:**  
Usuario → LoginForm.vue → authService.js → Backend → LoginView.vue → SecretView.vue → SecretDisplay.vue  

---

## 2. Buenas Prácticas de Desarrollo — Vue 3 / JavaScript

### Obligatorio (MUST HAVE)

| # | Regla | Cumplimiento | Evidencia |
|---|---|:---:|---|
| M1 | Uso de Composition API con `<script setup>` | Sí | Todos los componentes lo usan |
| M2 | Separación Views / Components | Sí | `LoginView` orquesta, `LoginForm` solo UI |
| M3 | Comunicación por props y emits | Sí | `LoginForm` emite `login-success` |
| M4 | Capa de servicios HTTP separada | Sí | `authService.js` centraliza Axios |
| M5 | Estado reactivo con `ref()` | Sí | Variables como `correo`, `password`, `loading` |
| M6 | Uso de `v-model` en inputs | Sí | Binding en formulario |
| M7 | `@submit.prevent` en formularios | Sí | Evita recarga |
| M8 | Nombres de componentes en PascalCase | Sí | `LoginForm`, `SecretDisplay` |
| M9 | `defineEmits` explícito | Sí | Declarado en `LoginForm` |
| M10 | Manejo de errores HTTP con try/catch | Sí | Implementado en `authService.js` |

### Recomendado (SHOULD HAVE)

| # | Regla | Cumplimiento | Observación |
|---|---|:---:|---|
| S1 | Guards en router para rutas protegidas | No | Protección en componente, no en router |
| S2 | Comentarios JSDoc en servicios | Parcial | Comentarios útiles pero no formales |
| S3 | Variables de entorno para API | No | URL hardcodeada en `authService.js` |
| S4 | Estilos `scoped` en componentes | Sí | Todos los componentes lo usan |
| S5 | Feedback visual en carga | Sí | Botón muestra "Ingresando..." |
| S6 | Consistencia en idioma del código | Parcial | Mezcla inglés/español (`correo`, `loading`) |
| S7 | `key` en listas con `v-for` | N/A | No hay listas |

---

## 3. Buenas Prácticas de Arquitectura

### Obligatorio

| # | Regla | Cumplimiento | Evidencia |
|---|---|:---:|---|
| A1 | SPA con Vue Router | Sí | `router/index.js` con `createWebHistory` |
| A2 | Separación Views / Components / Services | Sí | Carpetas bien definidas |
| A3 | `App.vue` como shell mínimo | Sí | Solo `<router-view />` |
| A4 | `main.js` limpio | Sí | Solo registra router y monta app |
| A5 | Instancia Axios centralizada | Sí | `apiClient` en `authService.js` |

### Recomendado

| # | Regla | Cumplimiento | Observación |
|---|---|:---:|---|
| AR1 | Variables de entorno para ambientes | No | Sin `.env` configurado |
| AR2 | Estado global con Pinia/Vuex | Pendiente | `secretPhrase` pasa por query params |
| AR3 | Guards en router | No | Ver S1 |
| AR4 | `name` en rutas | Sí | Definidos en router |

---

## 4. Código Limpio

- Nombres descriptivos: Sí  
- Componentes con una sola responsabilidad: Sí  
- Sin CSS global innecesario: Sí  
- Comentarios útiles: Sí  
- Sin `console.log` en producción: Sí  

---

## 5. Resumen de Cumplimiento

- MUST HAVE: 10/10 (100%)  
- SHOULD HAVE: 5/8 (63%)  

