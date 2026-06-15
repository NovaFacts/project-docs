# 🎨 Skill Review — Frontend
### Proyecto NovaFacts · Vue 3 + Vite + Vue Router + Axios
**Curso:** Ingeniería de Software 1 (2016701) — Universidad Nacional de Colombia  
**Fecha:** Junio 2025

---

## 1. Descripción de la Arquitectura

El frontend es una SPA (Single Page Application) construida con Vue 3 usando la Composition API (`<script setup>`). La navegación es manejada por Vue Router y las llamadas HTTP se centralizan en una capa de servicios.

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

## 2. Buenas Prácticas de Desarrollo — Vue 3 / JavaScript

### ✅ MUST HAVE (obligatorio)

| # | Regla | ¿Se cumple? | Evidencia / Observación |
|---|---|:---:|---|
| M1 | **Composition API con `<script setup>`** (patrón recomendado en Vue 3) | ✅ | Todos los componentes usan `<script setup>` |
| M2 | **Separación Views / Components** — las Views orquestan, los Components son UI | ✅ | `LoginView` maneja navegación; `LoginForm` solo maneja su estado interno y emite eventos |
| M3 | **Comunicación por props y emits** — no acceso directo al estado del padre | ✅ | `LoginForm` emite `login-success`; `SecretDisplay` emite `logout` |
| M4 | **Capa de servicios HTTP separada** — no llamar Axios directamente en los componentes | ✅ | `authService.js` centraliza toda la comunicación con el backend |
| M5 | **Estado reactivo con `ref()`** para datos que cambian en el template | ✅ | `correo`, `password`, `loading`, `errorMessage`, `secretPhrase` son todos `ref()` |
| M6 | **`v-model`** para binding bidireccional en inputs de formulario | ✅ | Usado en ambos campos del `LoginForm` |
| M7 | **`@submit.prevent`** para evitar recarga de página en formularios | ✅ | `<form @submit.prevent="handleSubmit">` |
| M8 | **Nombres de componentes en PascalCase** | ✅ | `LoginForm`, `SecretDisplay`, `LoginView`, `SecretView` |
| M9 | **`defineEmits`** declarado explícitamente en el componente | ✅ | `const emit = defineEmits(['login-success'])` en `LoginForm` |
| M10 | **Manejo de errores en llamadas HTTP** con try/catch | ✅ | `authService.js` captura errores de red y de respuesta por separado |

### 🟡 SHOULD HAVE (recomendado)

| # | Regla | ¿Se cumple? | Observación / Mejora sugerida |
|---|---|:---:|---|
| S1 | **Protección de rutas con Navigation Guards** (`router.beforeEach`) | ⚠️ No | `SecretView` hace su propia redirección en `onMounted`, lo cual tiene un "flash" visual antes de redirigir; un guard en el router sería más robusto |
| S2 | **Comentarios JSDoc en funciones de los servicios** | ⚠️ Parcial | `authService.js` tiene comentarios en línea útiles pero no JSDoc formal; `LoginView` sí documenta `handleLoginSuccess` correctamente con `@param` |
| S3 | **Variables de entorno con `import.meta.env`** para la URL base de la API | ⚠️ No | La URL `http://localhost:8000/api` está hardcodeada en `authService.js`; debería ser `import.meta.env.VITE_API_URL` |
| S4 | **`scoped` en todos los estilos** de los componentes | ✅ | Todos los componentes tienen `<style scoped>` |
| S5 | **Feedback visual durante la carga** (estado `loading`) | ✅ | El botón muestra "Ingresando..." y se deshabilita mientras espera |
| S6 | **Consistencia en el idioma del código** (variables en inglés) | ⚠️ Parcial | La mayoría está en inglés (`loading`, `password`, `router`), pero `correo` mezcla español; se recomienda uniformidad: `email` o `correo` en todo |
| S7 | **`key` en listas renderizadas con `v-for`** | ➖ N/A | No hay listas en el proyecto actualmente |

---

## 3. Buenas Prácticas de Arquitectura

### ✅ MUST HAVE

| # | Regla | ¿Se cumple? | Evidencia |
|---|---|:---:|---|
| A1 | **SPA con enrutamiento del lado del cliente** (Vue Router) | ✅ | `router/index.js` con `createWebHistory` |
| A2 | **Separación de responsabilidades** Views ↔ Components ↔ Services | ✅ | Cada carpeta tiene un rol claro y no se mezclan responsabilidades |
| A3 | **`App.vue` como shell mínimo** — sin lógica de negocio | ✅ | `App.vue` solo contiene `<router-view />` y un reset de estilos global |
| A4 | **`main.js` limpio** — solo registra plugins y monta la app | ✅ | Registro de router y mount, sin más |
| A5 | **Instancia de Axios centralizada** con `baseURL` y headers por defecto | ✅ | `apiClient` en `authService.js` evita repetir configuración |

### 🟡 SHOULD HAVE

| # | Regla | ¿Se cumple? | Observación |
|---|---|:---:|---|
| AR1 | **Variables de entorno para configuración por ambiente** (dev vs prod) | ⚠️ No | Sin `.env` ni `.env.production`; la URL de la API es fija |
| AR2 | **Estado global con Pinia o Vuex** si hay datos compartidos entre rutas | ⚠️ Pendiente | Hoy la `secretPhrase` pasa por query params (frágil); si el proyecto crece, un store evitaría URL manipulation |
| AR3 | **Guarda de navegación en el router** para rutas protegidas | ⚠️ No | Ver S1; actualmente la protección está en el componente, no en el router |
| AR4 | **`name` en todas las rutas** del router para usar `router.push({ name: '...' })` | ✅ | Las rutas tienen `name: 'login'` y `name: 'secret'` |

---

## 4. Código Limpio

| Principio | ¿Se aplica? | Nota |
|---|:---:|---|
| Nombres descriptivos (componentes, funciones, variables) | ✅ | `handleLoginSuccess`, `handleSubmit`, `handleLogout` son autoexplicativos |
| Componentes con una sola responsabilidad | ✅ | `LoginForm` solo gestiona el formulario; `LoginView` solo gestiona la navegación |
| Sin CSS global innecesario | ✅ | `style.css` solo contiene el reset; cada componente tiene su `<style scoped>` |
| Comentarios útiles (no redundantes) | ✅ | Los comentarios explican el **por qué**, no el qué (ej. "El ':' indica un parámetro dinámico") |
| Sin `console.log` de debug en producción | ✅ | No se encontraron `console.log` en el código |

---

## 5. Resumen de Cumplimiento

```
MUST HAVE   ████████████████████  10/10 ✅  (100%)
SHOULD HAVE ████████████░░░░░░░░   5/8  ⚠️  (63%)
```

> 💡 **Prioridad de mejora:** Mover la URL de la API a una variable de entorno (`VITE_API_URL`), implementar un Navigation Guard en el router para proteger `/secret`, y uniformizar el idioma de las variables (`correo` → `email`).
