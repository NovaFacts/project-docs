from django.db import models

class Ingesoft1User(models.Model):
    correo = models.EmailField(unique=True, null=False)
    password_hash = models.CharField(max_length=128, null=False)
    secret_phrase = models.CharField(max_length=255, null=False)

    class Meta:
        db_table = 'ingesoft1_users'
