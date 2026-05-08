from rest_framework.decorators import api_view
from rest_framework.response import Response
from rest_framework import status

from auth_app.services.auth_service import AuthService


@api_view(['POST'])
def login(request):

    # 1. Extraer datos
    correo = request.data.get('correo')
    password = request.data.get('password')

    # 2. Validar campos
    if not correo or not password:
        return Response(
            {"success": False, "message": "Correo y contraseña son requeridos"},
            status=status.HTTP_400_BAD_REQUEST
        )

    # 3. Llamar al service
    result = AuthService.authenticate(correo, password)

    # 4. Retornar respuesta según resultado
    if result["success"]:
        return Response(result, status=status.HTTP_200_OK)
    else:
        return Response(result, status=status.HTTP_401_UNAUTHORIZED)

@api_view(['POST'])
def register(request):

    correo = request.data.get('correo')
    password = request.data.get('password')
    secret_phrase = request.data.get('secret_phrase')

    # Validación básica
    if not correo or not password or not secret_phrase:
        return Response(
            {"success": False, "message": "Campos incompletos"},
            status=status.HTTP_400_BAD_REQUEST
        )

    # Llamar service
    result = AuthService.register(correo, password, secret_phrase)

    # Respuesta
    if result["success"]:
        return Response(result, status=status.HTTP_201_CREATED)
    else:
        return Response(result, status=status.HTTP_400_BAD_REQUEST)

@api_view(['POST'])
def forgot_password(request):

    correo = request.data.get('correo')
    secret_phrase = request.data.get('secret_phrase')
    new_password = request.data.get('new_password')

    # Validación básica
    if not correo or not secret_phrase or not new_password:
        return Response(
            {"success": False, "message": "Campos incompletos"},
            status=status.HTTP_400_BAD_REQUEST
        )

    # Llamar service
    result = AuthService.recover_password(
        correo,
        secret_phrase,
        new_password
    )

    # Respuesta
    if result["success"]:
        return Response(result, status=status.HTTP_200_OK)
    else:
        return Response(result, status=status.HTTP_400_BAD_REQUEST)
