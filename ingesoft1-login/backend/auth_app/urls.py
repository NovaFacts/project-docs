from django.urls import path
from auth_app.controllers.auth_controller import login, register, forgot_password

urlpatterns = [
    path('login', login),
    path('register', register),
    path('forgot-password', forgot_password),
]
