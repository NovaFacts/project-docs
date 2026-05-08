import axios from "axios";

const api = axios.create({
  baseURL: "http://127.0.0.1:8000/api" 
});

export default {
  async login(correo, password) {
    try {
      const response = await api.post("/login", {
        correo,
        password
      });

      return response.data;

    } catch (error) {
      if (error.response) {
        return error.response.data;
      } else {
        return {
          success: false,
          message: "Error de conexión con el servidor"
        };
      }
    }
  }
};
