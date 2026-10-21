package com.ascendion.datapowerAI.controller;

import com.ascendion.datapowerAI.dto.ApiBaseUrlRequestDTO;
import com.ascendion.datapowerAI.dto.ApiBaseUrlResponseDTO;
import com.ascendion.datapowerAI.enums.AuthType;
import com.ascendion.datapowerAI.service.ApiBaseUrlService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * Story ID: 111425
 * Title: Implement Comprehensive Unit Test Coverage for All Controller and Service Classes
 *
 * Covers: Representative controller coverage for success responses, payloads, validation,
 * boundary/invalid path parameters, and service-layer invocation behavior for ApiBaseUrlController.
 *
 * Class under test: ApiBaseUrlController
 * File: ApiBaseUrlControllerTest.java
 *
 * Test Case List:
 * TC-01 | shouldCreateBaseUrlAndReturnCreatedResponse | 201 Created with success wrapper and returned payload
 * TC-02 | shouldRejectCreateWhenRequestBodyViolatesValidation | 400 Bad Request for invalid @Valid request body
 * TC-03 | shouldGetAllBaseUrlsAndReturnOkResponse | 200 OK with list payload and success wrapper
 * TC-04 | shouldDeleteBaseUrlAndReturnOkResponse | 200 OK and service delete invoked with path UUID
 * TC-05 | shouldRejectDeleteWhenPathVariableIsNotUuid | 400 Bad Request for invalid path parameter format
 *
 * Assumptions made:
 * - Repo content shows Spring MVC controllers, JUnit 5, @WebMvcTest, MockMvc, ApiResponseGenric wrappers, and jakarta validation.
 * - Security/aspect enforcement is not imported into this slice test; controller behavior is validated in isolation.
 * - Global exception handling is not required for these focused validation cases because Spring MVC returns 400 for binding/validation errors.
 */
@WebMvcTest(ApiBaseUrlController.class)
@Import(com.ascendion.datapowerAI.config.JacksonConfig.class)
class ApiBaseUrlControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ApiBaseUrlService service;

    // TC-01: Every in-scope Controller must have tests for successful requests, response status codes, response payloads, and service invocation.
    @Test
    void shouldCreateBaseUrlAndReturnCreatedResponse() throws Exception {
        ApiBaseUrlRequestDTO request = ApiBaseUrlRequestDTO.builder()
                .name("Orders API")
                .baseUrl("https://example.test/api")
                .headers(Map.of("X-Test", "true"))
                .queryParams(Map.of("version", "v1"))
                .authType(AuthType.BEARER)
                .authConfig(Map.of("token", "masked-value"))
                .active(true)
                .build();

        ApiBaseUrlResponseDTO response = ApiBaseUrlResponseDTO.builder()
                .id(UUID.randomUUID())
                .name("Orders API")
                .baseUrl("https://example.test/api")
                .headers(Map.of("X-Test", "true"))
                .queryParams(Map.of("version", "v1"))
                .authType(AuthType.BEARER)
                .authConfigured(true)
                .active(true)
                .build();

        when(service.create(any(ApiBaseUrlRequestDTO.class))).thenReturn(response);

        mockMvc.perform(post("/api/base-urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("CREATED"))
                .andExpect(jsonPath("$.message").value("Base URL created successfully"))
                .andExpect(jsonPath("$.data.id").value(response.getId().toString()))
                .andExpect(jsonPath("$.data.name").value("Orders API"))
                .andExpect(jsonPath("$.data.baseUrl").value("https://example.test/api"))
                .andExpect(jsonPath("$.data.authType").value("BEARER"))
                .andExpect(jsonPath("$.data.active").value(true));

        ArgumentCaptor<ApiBaseUrlRequestDTO> captor = ArgumentCaptor.forClass(ApiBaseUrlRequestDTO.class);
        verify(service, times(1)).create(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Orders API");
        assertThat(captor.getValue().getBaseUrl()).isEqualTo("https://example.test/api");
    }

    // TC-02: Controller tests must cover request validation, including @Valid, required fields, invalid values, and malformed requests.
    @Test
    void shouldRejectCreateWhenRequestBodyViolatesValidation() throws Exception {
        String invalidBody = """
                {
                  "name": "",
                  "baseUrl": "ftp://invalid-host"
                }
                """;

        mockMvc.perform(post("/api/base-urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest());
    }

    // TC-03: Controller coverage must include successful GET requests, response payloads, and empty/non-empty list handling where applicable.
    @Test
    void shouldGetAllBaseUrlsAndReturnOkResponse() throws Exception {
        ApiBaseUrlResponseDTO first = ApiBaseUrlResponseDTO.builder()
                .id(UUID.randomUUID())
                .name("Orders API")
                .baseUrl("https://example.test/orders")
                .authType(AuthType.NONE)
                .authConfigured(false)
                .active(true)
                .build();

        ApiBaseUrlResponseDTO second = ApiBaseUrlResponseDTO.builder()
                .id(UUID.randomUUID())
                .name("Customers API")
                .baseUrl("https://example.test/customers")
                .authType(AuthType.BASIC)
                .authConfigured(true)
                .active(true)
                .build();

        when(service.getMyBaseUrls()).thenReturn(List.of(first, second));

        mockMvc.perform(get("/api/base-urls"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("Base URLs fetched successfully"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].name").value("Orders API"))
                .andExpect(jsonPath("$.data[1].name").value("Customers API"));

        verify(service, times(1)).getMyBaseUrls();
    }

    // TC-04: Controller tests must cover successful DELETE requests, status codes, and correct service-layer invocation and argument handling.
    @Test
    void shouldDeleteBaseUrlAndReturnOkResponse() throws Exception {
        UUID id = UUID.randomUUID();
        doNothing().when(service).delete(eq(id));

        mockMvc.perform(delete("/api/base-urls/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("Base URL deleted successfully"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(service, times(1)).delete(id);
    }

    // TC-05: Controller tests must cover invalid path parameters and malformed requests.
    @Test
    void shouldRejectDeleteWhenPathVariableIsNotUuid() throws Exception {
        mockMvc.perform(delete("/api/base-urls/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }
}
