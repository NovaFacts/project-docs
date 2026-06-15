package com.novafacts.hotel.service;

import com.novafacts.hotel.model.Booking;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas unitarias para InvoiceCalculator.
 * Cubre: subtotal, IVA, descuento por estadía larga y total.
 *
 * Casos límite cubiertos:
 * - Estadía de exactamente 1 noche (mínimo)
 * - Estadía de exactamente 6 noches (justo antes del descuento)
 * - Estadía de exactamente 7 noches (justo con descuento)
 * - Subtotal con precio decimal
 * - IVA de subtotal cero
 * - Reserva nula (excepción esperada)
 */
@DisplayName("🧾 Pruebas: Calculadora de Facturas")
class InvoiceCalculatorTest {

    private InvoiceCalculator calculator;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        calculator = new InvoiceCalculator();
        today = LocalDate.now().plusDays(1); // mañana para evitar fechas pasadas
    }

    // ─── calculateSubtotal ───────────────────────────────────────────────────

    @Test
    @DisplayName("✅ Subtotal correcto para 3 noches a $200.000")
    void subtotal_tresDias_calcularBien() {
        Booking booking = new Booking("Carlos Pérez",
                today, today.plusDays(3), 200_000, 2);

        double result = calculator.calculateSubtotal(booking);

        assertEquals(600_000, result, 0.01,
                "3 noches × $200.000 debe dar $600.000");
    }

    @Test
    @DisplayName("✅ Subtotal para estadía de 1 noche (caso mínimo)")
    void subtotal_unaNoche_calcularBien() {
        Booking booking = new Booking("Ana Gómez",
                today, today.plusDays(1), 150_000, 1);

        double result = calculator.calculateSubtotal(booking);

        assertEquals(150_000, result, 0.01);
    }

    @Test
    @DisplayName("✅ Subtotal con precio decimal")
    void subtotal_precioDecimal_calcularBien() {
        Booking booking = new Booking("Luis Torres",
                today, today.plusDays(2), 99_999.50, 1);

        double result = calculator.calculateSubtotal(booking);

        assertEquals(199_999.0, result, 0.01);
    }

    @Test
    @DisplayName("❌ Subtotal con reserva nula lanza excepción")
    void subtotal_reservaNula_lanzarExcepcion() {
        assertThrows(IllegalArgumentException.class,
                () -> calculator.calculateSubtotal(null));
    }

    // ─── calculateTax ────────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ IVA del 19% calculado correctamente")
    void tax_subtotalNormal_aplicar19Porciento() {
        double tax = calculator.calculateTax(100_000);

        assertEquals(19_000, tax, 0.01,
                "IVA del 19% sobre $100.000 debe ser $19.000");
    }

    @Test
    @DisplayName("✅ IVA de subtotal cero es cero")
    void tax_subtotalCero_esoCero() {
        double tax = calculator.calculateTax(0);

        assertEquals(0, tax, 0.01);
    }

    @Test
    @DisplayName("❌ IVA con subtotal negativo lanza excepción")
    void tax_subtotalNegativo_lanzarExcepcion() {
        assertThrows(IllegalArgumentException.class,
                () -> calculator.calculateTax(-1));
    }

    // ─── calculateDiscount ───────────────────────────────────────────────────

    @Test
    @DisplayName("✅ Sin descuento para estadía de 6 noches (justo bajo el umbral)")
    void discount_seisNoches_sinDescuento() {
        Booking booking = new Booking("María López",
                today, today.plusDays(6), 100_000, 2);

        double discount = calculator.calculateDiscount(booking);

        assertEquals(0.0, discount, 0.01,
                "6 noches no califica para descuento (umbral es 7)");
    }

    @Test
    @DisplayName("✅ Descuento del 10% para estadía de exactamente 7 noches")
    void discount_sieteNoches_aplicarDescuento() {
        Booking booking = new Booking("Pedro Ramos",
                today, today.plusDays(7), 100_000, 2);

        double discount = calculator.calculateDiscount(booking);

        assertEquals(70_000, discount, 0.01,
                "7 noches × $100.000 = $700.000 subtotal → 10% = $70.000");
    }

    @Test
    @DisplayName("✅ Descuento aplicado para estadía larga de 14 noches")
    void discount_catorcheNoches_aplicarDescuento() {
        Booking booking = new Booking("Sofia Vargas",
                today, today.plusDays(14), 200_000, 3);

        double discount = calculator.calculateDiscount(booking);

        assertEquals(280_000, discount, 0.01,
                "14 noches × $200.000 = $2.800.000 → 10% = $280.000");
    }

    // ─── calculateTotal ──────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ Total correcto sin descuento (3 noches)")
    void total_tresDias_sinDescuento() {
        // 3 noches × $100.000 = $300.000 subtotal + 19% IVA = $357.000
        Booking booking = new Booking("Jorge Díaz",
                today, today.plusDays(3), 100_000, 1);

        double total = calculator.calculateTotal(booking);

        assertEquals(357_000, total, 0.01);
    }

    @Test
    @DisplayName("✅ Total correcto con descuento por larga estadía (7 noches)")
    void total_sieteNoches_conDescuento() {
        // 7 noches × $100.000 = $700.000 subtotal
        // IVA: $133.000 | Descuento: $70.000
        // Total: $700.000 + $133.000 - $70.000 = $763.000
        Booking booking = new Booking("Laura Morales",
                today, today.plusDays(7), 100_000, 2);

        double total = calculator.calculateTotal(booking);

        assertEquals(763_000, total, 0.01);
    }
}
