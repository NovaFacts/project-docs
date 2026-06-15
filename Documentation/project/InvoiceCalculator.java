package com.novafacts.hotel.service;

import com.novafacts.hotel.model.Booking;

/**
 * Servicio encargado de calcular los valores de una factura
 * a partir de una reserva de hospedaje.
 *
 * Lógica de negocio:
 * - Subtotal = noches * precio por noche
 * - IVA = 19% del subtotal
 * - Descuento por estadía larga: 10% si >= 7 noches
 * - Total = subtotal + IVA - descuento
 */
public class InvoiceCalculator {

    private static final double TAX_RATE = 0.19;
    private static final double LONG_STAY_DISCOUNT_RATE = 0.10;
    private static final int LONG_STAY_THRESHOLD_NIGHTS = 7;

    /**
     * Calcula el subtotal (sin impuestos ni descuentos).
     */
    public double calculateSubtotal(Booking booking) {
        if (booking == null) {
            throw new IllegalArgumentException("La reserva no puede ser nula");
        }
        return booking.getNumberOfNights() * booking.getPricePerNight();
    }

    /**
     * Calcula el IVA sobre el subtotal.
     */
    public double calculateTax(double subtotal) {
        if (subtotal < 0) {
            throw new IllegalArgumentException("El subtotal no puede ser negativo");
        }
        return subtotal * TAX_RATE;
    }

    /**
     * Calcula el descuento por estadía larga (10% si >= 7 noches).
     */
    public double calculateDiscount(Booking booking) {
        if (booking == null) {
            throw new IllegalArgumentException("La reserva no puede ser nula");
        }
        if (booking.getNumberOfNights() >= LONG_STAY_THRESHOLD_NIGHTS) {
            return calculateSubtotal(booking) * LONG_STAY_DISCOUNT_RATE;
        }
        return 0.0;
    }

    /**
     * Calcula el total final de la factura.
     */
    public double calculateTotal(Booking booking) {
        double subtotal = calculateSubtotal(booking);
        double tax = calculateTax(subtotal);
        double discount = calculateDiscount(booking);
        return subtotal + tax - discount;
    }
}
