package com.novafacts.hotel.service;

import com.novafacts.hotel.model.Booking;
import java.time.LocalDate;

/**
 * Servicio que valida las reglas de negocio de una reserva
 * antes de registrarla en el sistema.
 */
public class BookingValidator {

    private static final int MAX_GUESTS_PER_ROOM = 4;
    private static final int MAX_STAY_NIGHTS = 30;

    /**
     * Valida que una reserva sea válida según las reglas de negocio.
     * @return true si es válida
     * @throws IllegalArgumentException si alguna regla se viola
     */
    public boolean validate(Booking booking) {
        if (booking == null) {
            throw new IllegalArgumentException("La reserva no puede ser nula");
        }
        if (booking.getCheckIn().isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("No se puede reservar en fechas pasadas");
        }
        if (booking.getNumberOfGuests() > MAX_GUESTS_PER_ROOM) {
            throw new IllegalArgumentException(
                "Máximo " + MAX_GUESTS_PER_ROOM + " huéspedes por habitación");
        }
        if (booking.getNumberOfNights() > MAX_STAY_NIGHTS) {
            throw new IllegalArgumentException(
                "La estadía no puede superar " + MAX_STAY_NIGHTS + " noches");
        }
        return true;
    }

    /**
     * Verifica si dos reservas se solapan en fechas.
     */
    public boolean hasDateConflict(Booking b1, Booking b2) {
        if (b1 == null || b2 == null) {
            throw new IllegalArgumentException("Las reservas no pueden ser nulas");
        }
        return b1.getCheckIn().isBefore(b2.getCheckOut()) &&
               b2.getCheckIn().isBefore(b1.getCheckOut());
    }
}
