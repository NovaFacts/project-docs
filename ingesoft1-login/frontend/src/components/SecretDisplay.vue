<template>
  <div class="container">
    <div class="card">
      
      <div class="icon">✓</div>
      
      <h2>¡Login Exitoso!</h2>

      <div class="secret-box">
        {{ secretPhrase }}
      </div>

      <button @click="handleLogout">
        Cerrar sesión
      </button>

    </div>
  </div>
</template>

<script>
export default {
  name: "SecretDisplay",

  props: {
    secretPhrase: {
      type: String,
      required: true
    }
  },

  methods: {
    handleLogout() {
      this.$emit("logout");
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
  background: linear-gradient(135deg, #56ab2f, #a8e063);
}

.card {
  background: white;
  padding: 2rem;
  border-radius: 12px;
  box-shadow: 0 10px 25px rgba(0,0,0,0.2);
  text-align: center;
  width: 320px;
}

.icon {
  font-size: 2.5rem;
  color: #4caf50;
  margin-bottom: 1rem;
}

h2 {
  margin-bottom: 1rem;
}

.secret-box {
  border: 2px dashed #4caf50;
  padding: 1rem;
  margin-bottom: 1.5rem;
  border-radius: 8px;
  background: #f6fff6;
  font-weight: bold;
}

button {
  padding: 10px;
  border: none;
  border-radius: 8px;
  background: #ccc;
  color: #333;
  cursor: pointer;
  transition: 0.3s;
}

button:hover {
  background: #bbb;
}
</style>
