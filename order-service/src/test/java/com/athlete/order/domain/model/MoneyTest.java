package com.athlete.order.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void adds_amounts_in_the_same_currency() {
        assertThat(Money.of("10.00", "USD").add(Money.of("5.50", "USD")))
                .isEqualTo(Money.of("15.50", "USD"));
    }

    @Test
    void multiplies_by_a_quantity() {
        assertThat(Money.of("2.50", "USD").multipliedBy(4))
                .isEqualTo(Money.of("10.00", "USD"));
    }

    @Test
    void rejects_mixing_currencies() {
        assertThatThrownBy(() -> Money.of("1.00", "USD").add(Money.of("1.00", "EUR")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizes_scale_to_the_currency() {
        assertThat(Money.of("9.5", "USD")).isEqualTo(Money.of("9.50", "USD"));
    }
}
