package com.novafacts.hotel.model;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Representa una reserva de hospedaje en el sistema NovaFacts.
 * Contiene la información del huésped, fechas y habitación reservada.
 */
public class Booking {

    private String guestName;
    private LocalDate checkIn;
    private LocalDate checkOut;
    private double pricePerNight;
    private int numberOfGuests;

    public Booking(String guestName, LocalDate checkIn, LocalDate checkOut,
                   double pricePerNight, int numberOfGuests) {
        if (guestName == null || guestName.isBlank()) {
            throw new IllegalArgumentException("El nombre del huésped no puede estar vacío");
        }
        if (checkIn == null || checkOut == null) {
            throw new IllegalArgumentException("Las fechas no pueden ser nulas");
        }
        if (!checkOut.isAfter(checkIn)) {
            throw new IllegalArgumentException("La fecha de salida debe ser posterior a la de entrada");
        }
        if (pricePerNight <= 0) {
            throw new IllegalArgumentException("El precio por noche debe ser mayor a cero");
        }
        if (numberOfGuests <= 0) {
            throw new IllegalArgumentException("El número de huéspedes debe ser mayor a cero");
        }
        this.guestName = guestName;
        this.checkIn = checkIn;
        this.checkOut = checkOut;
        this.pricePerNight = pricePerNight;
        this.numberOfGuests = numberOfGuests;
    }

    public long getNumberOfNights() {
        return ChronoUnit.DAYS.between(checkIn, checkOut);
    }

    public String getGuestName() { return guestName; }
    public LocalDate getCheckIn() { return checkIn; }
    public LocalDate getCheckOut() { return checkOut; }
    public double getPricePerNight() { return pricePerNight; }
    public int getNumberOfGuests() { return numberOfGuests; }
}
