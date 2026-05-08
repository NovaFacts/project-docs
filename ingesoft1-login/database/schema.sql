--tabla de roles

CREATE TABLE roles (
    id_rol SERIAL PRIMARY KEY,
    nombre_rol VARCHAR(50) NOT NULL,
    descripcion_rol VARCHAR(150)
);

--tabla de permisos
CREATE TABLE permisos (
    id_permiso SERIAL PRIMARY KEY,
    nombre_permiso VARCHAR(50) NOT NULL
);
--un rol puede tener muchos permisos, un permiso puede tener muchos roles
CREATE TABLE roles_permisos (
    id_rol_fk INT,
    id_permiso_fk INT,   
    FOREIGN KEY (id_rol_fk) REFERENCES roles(id_rol),
    FOREIGN KEY (id_permiso_fk) REFERENCES permisos(id_permiso)
    PRIMARY KEY (id_rol_fk, id_permiso_fk)
);

--tabla usuarios
CREATE TABLE usuario (
    id_user SERIAL PRIMARY KEY,
    nombre_user VARCHAR(50) NOT NULL,
    apellido_user VARCHAR(50) NOT NULL,
    correo_user VARCHAR(50) NOT NULL UNIQUE,
    celular_user VARCHAR(50) NOT NULL UNIQUE CHECK (length(celular_user) >= 10),
    estado_user BOOLEAN NOT NULL DEFAULT true,
    -- fk Un usuario pertenece a un rol
    id_rol_fk INT REFERENCES roles(id_rol)
);

--tabla sesion_log

CREATE TABLE sesion_log (
    id_sesion SERIAL PRIMARY KEY,
    fecha_ingreso TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    direccion_ip INET NOT NULL,
    -- fk: Una sesión pertenece a un usuario
    id_user_fk INT references usuario(id_user)
);
