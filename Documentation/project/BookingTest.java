package com.novafacts.hotel.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas unitarias para la entidad Booking.
 * Cubre: creación válida, cálculo de noches y validaciones de constructor.
 *
 * Casos límite cubiertos:
 * - Fechas iguales (checkIn == checkOut → inválido)
 * - checkOut anterior a checkIn → inválido
 * - Nombre vacío o en blanco → inválido
 * - Precio cero → inválido
 * - Precio negativo → inválido
 * - numberOfGuests = 0 → inválido
 * - Estadía de 1 noche (mínimo válido)
 */
@DisplayName("🏨 Pruebas: Entidad Booking (Reserva)")
class BookingTest {

    private final LocalDate tomorrow = LocalDate.now().plusDays(1);

    @Test
    @DisplayName("✅ Crear reserva válida correctamente")
    void booking_datosValidos_creaCorrectamente() {
        Booking booking = new Booking("Carlos Pérez",
                tomorrow, tomorrow.plusDays(3), 200_000, 2);

        assertAll(
            () -> assertEquals("Carlos Pérez", booking.getGuestName()),
            () -> assertEquals(3, booking.getNumberOfNights()),
            () -> assertEquals(200_000, booking.getPricePerNight(), 0.01),
            () -> assertEquals(2, booking.getNumberOfGuests())
        );
    }

    @Test
    @DisplayName("✅ Cálculo correcto de noches")
    void getNumberOfNights_cincoNoches_retornaCinco() {
        Booking booking = new Booking("Ana Gómez",
                tomorrow, tomorrow.plusDays(5), 100_000, 1);

        assertEquals(5, booking.getNumberOfNights());
    }

    @Test
    @DisplayName("✅ Estadía de exactamente 1 noche (caso mínimo válido)")
    void booking_unaNoche_esValida() {
        assertDoesNotThrow(() ->
            new Booking("Luis Torres", tomorrow, tomorrow.plusDays(1), 80_000, 1)
        );
    }

    @Test
    @DisplayName("❌ Nombre vacío lanza excepción")
    void booking_nombreVacio_lanzarExcepcion() {
        assertThrows(IllegalArgumentException.class, () ->
            new Booking("", tomorrow, tomorrow.plusDays(2), 100_000, 1)
        );
    }

    @Test
    @DisplayName("❌ Nombre solo con espacios lanza excepción")
    void booking_nombreEnBlanco_lanzarExcepcion() {
        assertThrows(IllegalArgumentException.class, () ->
            new Booking("   ", tomorrow, tomorrow.plusDays(2), 100_000, 1)
        );
    }

    @Test
    @DisplayName("❌ checkOut igual a checkIn lanza excepción (0 noches)")
    void booking_fechasIguales_lanzarExcepcion() {
        assertThrows(IllegalArgumentException.class, () ->
            new Booking("Pedro Ramos", tomorrow, tomorrow, 100_000, 1)
        );
    }

    @Test
    @DisplayName("❌ checkOut anterior a checkIn lanza excepción")
    void booking_checkOutAntes_lanzarExcepcion() {
        assertThrows(IllegalArgumentException.class, () ->
            new Booking("María López", tomorrow, tomorrow.minusDays(1), 100_000, 1)
        );
    }

    @Test
    @DisplayName("❌ Precio por noche de cero lanza excepción")
    void booking_precioCero_lanzarExcepcion() {
        assertThrows(IllegalArgumentException.class, () ->
            new Booking("Jorge Díaz", tomorrow, tomorrow.plusDays(2), 0, 1)
        );
    }

    @Test
    @DisplayName("❌ Precio por noche negativo lanza excepción")
    void booking_precioNegativo_lanzarExcepcion() {
        assertThrows(IllegalArgumentException.class, () ->
            new Booking("Sofia Vargas", tomorrow, tomorrow.plusDays(2), -50_000, 1)
        );
    }

    @Test
    @DisplayName("❌ Número de huéspedes cero lanza excepción")
    void booking_huespedesCero_lanzarExcepcion() {
        assertThrows(IllegalArgumentException.class, () ->
            new Booking("Laura Morales", tomorrow, tomorrow.plusDays(2), 100_000, 0)
        );
    }
}
