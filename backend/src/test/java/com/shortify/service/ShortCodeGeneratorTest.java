package com.shortify.service;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShortCodeGeneratorTest {

    private final ShortCodeGenerator generator = new ShortCodeGenerator();

    @Test
    void generatesFixedLengthBase62Codes() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String code = generator.generate();
            assertThat(code).matches("[0-9A-Za-z]{8}");
            codes.add(code);
        }
        assertThat(codes).hasSize(10_000);
    }

    @Test
    void supportsConcurrentGeneration() {
        Set<String> codes = ConcurrentHashMap.newKeySet();
        IntStream.range(0, 10_000).parallel().forEach(i -> codes.add(generator.generate()));
        assertThat(codes).hasSize(10_000).allSatisfy(code -> assertThat(code).matches("[0-9A-Za-z]{8}"));
    }
}
