<template>
  <SecretDisplay 
    v-if="secretPhrase"
    :secretPhrase="secretPhrase"
    @logout="handleLogout"
  />
</template>

<script>
import SecretDisplay from "@/components/SecretDisplay.vue";

export default {
  name: "SecretView",

  components: {
    SecretDisplay
  },

  data() {
    return {
      secretPhrase: ""
    };
  },

  created() {
    //  Obtener frase desde la URL
    const phrase = this.$route.query.phrase;

    if (!phrase) {
      //  Si no hay frase → volver al login
      this.$router.push("/");
    } else {
      this.secretPhrase = phrase;
    }
  },

  methods: {
    handleLogout() {
      //  Redirigir al login
      this.$router.push("/");
    }
  }
};
</script>
