package com.shortify.exception;

import java.util.List;

import com.shortify.controller.ShortUrlController;
import com.shortify.dto.ShortUrlPage;
import com.shortify.service.ShortUrlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ApiExceptionHandlerTest {

    @Mock
    private ShortUrlService service;
    @InjectMocks
    private ShortUrlController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PATCH", "DELETE"})
    void whitespaceIdIsAClientError(String method) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), "/api/urls/{id}", " ")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value("INVALID_PARAMETER"));
    }

    @Test
    void unsupportedMethodIncludesRequiredAllowHeader() throws Exception {
        mvc.perform(put("/api/urls/1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, containsString("GET")))
                .andExpect(header().string(HttpHeaders.ALLOW, containsString("PATCH")))
                .andExpect(header().string(HttpHeaders.ALLOW, containsString("DELETE")))
                .andExpect(jsonPath("code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unsupportedRequestMediaTypeAdvertisesSupportedTypes() throws Exception {
        mvc.perform(post("/api/urls").contentType(MediaType.TEXT_PLAIN).content("not JSON"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string(HttpHeaders.ACCEPT, containsString("application/json")))
                .andExpect(jsonPath("code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void unsupportedResponseMediaTypeIsAClientErrorNotServerError() throws Exception {
        when(service.list(0, 20)).thenReturn(new ShortUrlPage(List.of(), 0, 20, 0, 0));
        mvc.perform(get("/api/urls").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("status").value(406))
                .andExpect(jsonPath("code").value("NOT_ACCEPTABLE"));
    }
}
