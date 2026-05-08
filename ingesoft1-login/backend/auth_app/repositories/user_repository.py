from auth_app.models import Ingesoft1User

class UserRepository:

    def find_by_correo(self, correo):
        try:
            return Ingesoft1User.objects.get(correo=correo)
        except Ingesoft1User.DoesNotExist:
            return None

    def create_user(self, correo, password_hash, secret_phrase):
        user = Ingesoft1User(
            correo=correo,
            password_hash=password_hash,
            secret_phrase=secret_phrase
        )
        user.save()
        return user
