<template>
  <div class="container">
    <form class="form" @submit.prevent="handleSubmit">

      <h2>Login</h2>

      <input
        type="email"
        placeholder="Correo"
        v-model="correo"
        required
      />

      <input
        type="password"
        placeholder="Contraseña"
        v-model="password"
        required
      />

      <div v-if="errorMessage" class="error">
        {{ errorMessage }}
      </div>

      <button type="submit" :disabled="loading">
        {{ loading ? "Ingresando..." : "Ingresar" }}
      </button>

    </form>
  </div>
</template>

<script>
import authService from "@/services/authService";

export default {
  name: "LoginForm",

  data() {
    return {
      correo: "",
      password: "",
      loading: false,
      errorMessage: ""
    };
  },

  methods: {
    async handleSubmit() {
      this.loading = true;
      this.errorMessage = "";

      const result = await authService.login(this.correo, this.password);

      if (result.success) {
        // 🔥 CORRECCIÓN IMPORTANTE
        this.$emit("login-success", result.data.secret_phrase);
      } else {
        this.errorMessage = result.message;
      }

      this.loading = false;
    }
  }
};
</script>

<style scoped>
.container {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100vh;
  background: linear-gradient(135deg, #667eea, #764ba2);
}

.form {
  background: white;
  padding: 2rem;
  border-radius: 12px;
  box-shadow: 0 10px 25px rgba(0,0,0,0.2);
  display: flex;
  flex-direction: column;
  width: 300px;
}

.form h2 {
  text-align: center;
  margin-bottom: 1rem;
}

input {
  margin-bottom: 1rem;
  padding: 10px;
  border-radius: 8px;
  border: 1px solid #ccc;
  transition: 0.3s;
}

input:focus {
  border-color: #667eea;
  outline: none;
}

button {
  padding: 10px;
  border: none;
  border-radius: 8px;
  background: linear-gradient(135deg, #667eea, #764ba2);
  color: white;
  font-weight: bold;
  cursor: pointer;
  transition: 0.3s;
}

button:hover {
  opacity: 0.9;
}

button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.error {
  background: #ffe0e0;
  color: #d8000c;
  padding: 8px;
  margin-bottom: 10px;
  border-radius: 6px;
  text-align: center;
}
</style>
