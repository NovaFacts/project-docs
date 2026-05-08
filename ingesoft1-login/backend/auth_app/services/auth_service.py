import hashlib
from auth_app.repositories.user_repository import UserRepository


class AuthService:

    @staticmethod
    def hash_password(password):
        password_bytes = password.encode('utf-8')
        hash_object = hashlib.sha512(password_bytes)
        password_hash = hash_object.hexdigest()
        return password_hash

    @staticmethod
    def authenticate(correo, password):

        # 1. Buscar el usuario usando el repositorio
        repo = UserRepository()
        user = repo.find_by_correo(correo)

        # 2. Si no existe, error
        if not user:
            return {
                "success": False,
                "message": "Usuario no encontrado"
            }

        # 3. Aplicar hash al password recibido para comparar
        input_password_hash = AuthService.hash_password(password)

        # 4. Comparar con el hash almacenado en la base de datos
        if input_password_hash != user.password_hash:
            return {
                "success": False,
                "message": "Contraseña incorrecta"
            }

        # 5. Login exitoso
        return {
            "success": True,
            "message": "Login exitoso",
            "data": {
                "secret_phrase": user.secret_phrase
            }
        }

    @staticmethod
    def register(correo, password, secret_phrase):

        repo = UserRepository()

        # 1. Verificar si el usuario ya existe
        if repo.find_by_correo(correo):
            return {
                "success": False,
                "message": "El usuario ya existe"
            }

        # 2. Hash password
        password_hash = AuthService.hash_password(password)

        # 3. Crear usuario
        user = repo.create_user(correo, password_hash, secret_phrase)

        return {
            "success": True,
            "message": "Usuario creado correctamente",
            "data": {
                "user_id": user.id
            }
        }

    @staticmethod
    def recover_password(correo, secret_phrase, new_password):

        repo = UserRepository()

        # 1. Buscar usuario
        user = repo.find_by_correo(correo)

        if not user:
            return {
                "success": False,
                "message": "Usuario no encontrado"
            }

        # 2. Validar frase secreta
        if user.secret_phrase != secret_phrase:
            return {
                "success": False,
                "message": "Frase secreta incorrecta"
            }

        # 3. Hashear nueva contraseña
        new_password_hash = AuthService.hash_password(new_password)

        # 4. Actualizar contraseña en BD
        user.password_hash = new_password_hash
        user.save()

        return {
            "success": True,
            "message": "Contraseña actualizada correctamente"
        }
