import { createRouter, createWebHistory } from "vue-router";
import LoginView from "@/views/LoginView.vue";
import SecretView from "@/views/SecretView.vue";

//  Definir rutas
const routes = [
  {
    path: "/",
    name: "Login",
    component: LoginView
  },
  {
    path: "/secret/:secretPhrase?",
    name: "Secret",
    component: SecretView
  }
];

//  Crear router
const router = createRouter({
  history: createWebHistory(),
  routes
});

//  Exportar router
export default router;
