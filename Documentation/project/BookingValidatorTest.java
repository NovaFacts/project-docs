package com.novafacts.hotel.service;

import com.novafacts.hotel.model.Booking;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas unitarias para BookingValidator.
 * Cubre: validación de reglas de negocio y detección de conflictos de fechas.
 *
 * Casos límite cubiertos:
 * - Exactamente 4 huéspedes (máximo permitido)
 * - 5 huéspedes (supera el límite)
 * - Exactamente 30 noches (máximo permitido)
 * - 31 noches (supera el límite)
 * - Reservas que se solapan parcialmente
 * - Reservas que se tocan en fecha (sin solapamiento)
 * - Reservas completamente separadas
 * - Reserva nula
 */
@DisplayName("✔️ Pruebas: Validador de Reservas")
class BookingValidatorTest {

    private BookingValidator validator;
    private LocalDate tomorrow;

    @BeforeEach
    void setUp() {
        validator = new BookingValidator();
        tomorrow = LocalDate.now().plusDays(1);
    }

    // ─── validate ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ Reserva válida pasa la validación")
    void validate_reservaValida_retornaTrue() {
        Booking booking = new Booking("Carlos Pérez",
                tomorrow, tomorrow.plusDays(3), 100_000, 2);

        assertTrue(validator.validate(booking));
    }

    @Test
    @DisplayName("✅ Exactamente 4 huéspedes es válido (límite máximo)")
    void validate_cuatroHuespedes_esValido() {
        Booking booking = new Booking("Familia García",
                tomorrow, tomorrow.plusDays(2), 250_000, 4);

        assertTrue(validator.validate(booking));
    }

    @Test
    @DisplayName("❌ 5 huéspedes supera el límite y lanza excepción")
    void validate_cincoHuespedes_lanzarExcepcion() {
        Booking booking = new Booking("Grupo Grande",
                tomorrow, tomorrow.plusDays(2), 250_000, 5);

        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(booking));
    }

    @Test
    @DisplayName("✅ Exactamente 30 noches es válido (límite máximo)")
    void validate_treintaNoches_esValido() {
        Booking booking = new Booking("Ana López",
                tomorrow, tomorrow.plusDays(30), 80_000, 1);

        assertTrue(validator.validate(booking));
    }

    @Test
    @DisplayName("❌ 31 noches supera el límite y lanza excepción")
    void validate_treintaYUnaNoche_lanzarExcepcion() {
        Booking booking = new Booking("Pedro Ruiz",
                tomorrow, tomorrow.plusDays(31), 80_000, 1);

        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(booking));
    }

    @Test
    @DisplayName("❌ Reserva nula lanza excepción")
    void validate_reservaNula_lanzarExcepcion() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(null));
    }

    // ─── hasDateConflict ─────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ Reservas que se solapan tienen conflicto")
    void hasDateConflict_solapamiento_retornaTrue() {
        // B1: días 1-5 | B2: días 3-7 → se solapan en días 3-5
        Booking b1 = new Booking("Huésped A",
                tomorrow, tomorrow.plusDays(5), 100_000, 1);
        Booking b2 = new Booking("Huésped B",
                tomorrow.plusDays(3), tomorrow.plusDays(7), 100_000, 1);

        assertTrue(validator.hasDateConflict(b1, b2));
    }

    @Test
    @DisplayName("✅ Reservas que se tocan en fecha NO tienen conflicto")
    void hasDateConflict_fechasTocadas_retornaFalse() {
        // B1 sale el día 5, B2 entra el día 5 → válido, sin solapamiento
        Booking b1 = new Booking("Huésped A",
                tomorrow, tomorrow.plusDays(5), 100_000, 1);
        Booking b2 = new Booking("Huésped B",
                tomorrow.plusDays(5), tomorrow.plusDays(8), 100_000, 1);

        assertFalse(validator.hasDateConflict(b1, b2));
    }

    @Test
    @DisplayName("✅ Reservas completamente separadas no tienen conflicto")
    void hasDateConflict_reservasSeparadas_retornaFalse() {
        Booking b1 = new Booking("Huésped A",
                tomorrow, tomorrow.plusDays(3), 100_000, 1);
        Booking b2 = new Booking("Huésped B",
                tomorrow.plusDays(10), tomorrow.plusDays(13), 100_000, 1);

        assertFalse(validator.hasDateConflict(b1, b2));
    }

    @Test
    @DisplayName("❌ hasDateConflict con reserva nula lanza excepción")
    void hasDateConflict_reservaNula_lanzarExcepcion() {
        Booking b1 = new Booking("Huésped A",
                tomorrow, tomorrow.plusDays(3), 100_000, 1);

        assertThrows(IllegalArgumentException.class,
                () -> validator.hasDateConflict(b1, null));
    }
}
