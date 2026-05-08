<template>
  <LoginForm @login-success="handleLoginSuccess" />
</template>

<script>
import LoginForm from "@/components/LoginForm.vue";

export default {
  name: "LoginView",

  components: {
    LoginForm
  },

  methods: {
    handleLoginSuccess(secretPhrase) {
      this.$router.push({
        path: "/secret",
        query: { phrase: secretPhrase }
      });
    }
  }
};
</script>
