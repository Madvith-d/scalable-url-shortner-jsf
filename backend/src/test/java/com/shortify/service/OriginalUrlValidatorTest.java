package com.shortify.service;

import com.shortify.exception.UrlException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OriginalUrlValidatorTest {

    private final OriginalUrlValidator validator = new OriginalUrlValidator();

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com", "http://example.com/a?b=c#fragment", "HTTPS://EXAMPLE.COM/path",
            "http://localhost:8081/path", "http://127.0.0.1:80", "https://[::1]:443/path",
            "https://example.com/a%20b?q=a%2Fb", "https://xn--bcher-kva.example/",
            "https://example.com/caf%C3%A9", "https://example.com/日本語", "http://example.com:65535"
    })
    void acceptsSyntacticallyValidHttpUrlsWithoutDnsLookup(String url) {
        assertThatCode(() -> validator.validate(url)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ", "example.com", "//example.com", "/relative", "ftp://example.com", "javascript:alert(1)",
            "https://", "https:///path", "http:/example.com", "https://exa mple.com", "https://bad_host.com",
            "https://user:password@example.com", "https://user@example.com", "https://@example.com",
            "https://example.com/\r\nInjected:yes", "https://example.com/\tpath", "https://example.com/\u0000",
            "https://example.com/%0d%0aInjected:yes", "https://example.com/%00", "https://example.com/%1F",
            "https://example.com/%7f", "https://example.com/\u0085", "https://example.com/%C2%85",
            "https://example.com/?q=%C2%9F", "https://example.com/#%0D", " https://example.com", "https://example.com ",
            "https://example.com/%zz", "https://example.com:65536", "https://example.com:-1",
            "https://example.com:", "https://example.com:abc", "https://[invalid]/", "https://example.com\\evil"
    })
    void rejectsInvalidOrUnsafeUrls(String url) {
        assertThatThrownBy(() -> validator.validate(url)).isInstanceOfSatisfying(UrlException.class, exception -> {
            assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(exception.getCode()).isEqualTo("INVALID_URL");
        });
    }
}
