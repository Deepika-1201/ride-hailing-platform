package com.ridehailing.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PhonesTests {

    @Test
    void masksAllButTheCountryCodeAndTheLastFourDigits() {
        assertThat(Phones.mask("+919845012345")).isEqualTo("+91******2345");
    }

    @Test
    void masksShortOrMissingNumbersCompletely() {
        assertThat(Phones.mask("+1234")).isEqualTo("***");
        assertThat(Phones.mask(null)).isEqualTo("***");
    }
}
