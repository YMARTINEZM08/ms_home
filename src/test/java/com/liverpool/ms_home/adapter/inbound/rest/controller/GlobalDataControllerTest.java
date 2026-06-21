package com.liverpool.ms_home.adapter.inbound.rest.controller;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.liverpool.ms_home.adapter.inbound.rest.dto.GlobalDataResponse;
import com.liverpool.ms_home.adapter.inbound.rest.mapper.GlobalDataMapper;
import com.liverpool.ms_home.config.ContentstackProperties;
import com.liverpool.ms_home.config.MdcRequestContextFilter;
import com.liverpool.ms_home.config.SecurityConfig;
import com.liverpool.ms_home.domain.error.ContentServiceUnavailableException;
import com.liverpool.ms_home.domain.error.ServiceUnavailableException;
import com.liverpool.ms_home.domain.model.globaldata.GlobalData;
import com.liverpool.ms_home.domain.model.globaldata.GlobalDataQuery;
import com.liverpool.ms_home.domain.model.home.SessionContext;
import com.liverpool.ms_home.domain.port.inbound.GetGlobalDataUseCase;
import com.liverpool.ms_home.domain.port.outbound.SessionContextPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test for {@link GlobalDataController} — loads only the web layer.
 */
@WebMvcTest(GlobalDataController.class)
@Import({SecurityConfig.class, MdcRequestContextFilter.class})
class GlobalDataControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GetGlobalDataUseCase getGlobalDataUseCase;

    @MockitoBean
    private SessionContextPort sessionContextPort;

    @MockitoBean
    private GlobalDataMapper globalDataMapper;

    @MockitoBean
    private ContentstackProperties contentstackProperties;

    private static final SessionContext SESSION = new SessionContext(false, "LP", "WEB", "es-mx");

    private static final GlobalData DOMAIN_DATA = new GlobalData(
            "es-mx",
            Map.of("salesforce", true),
            Map.of("site_domain", "https://www.liverpool.com.mx"),
            Map.of("primary_color", "#E31837"),
            Map.of("logo_url", "https://cdn.liverpool.com.mx/logo.svg"),
            Map.of("copyright", "© Liverpool 2025"));

    private static final GlobalDataResponse RESPONSE_DTO = new GlobalDataResponse(
            "es-mx",
            Map.of("salesforce", true),
            Map.of("site_domain", "https://www.liverpool.com.mx"),
            Map.of("primary_color", "#E31837"),
            Map.of("logo_url", "https://cdn.liverpool.com.mx/logo.svg"),
            Map.of("copyright", "© Liverpool 2025"));

    @BeforeEach
    void setUp() {
        when(contentstackProperties.previewHeader()).thenReturn("x-preview");
        when(sessionContextPort.currentContext()).thenReturn(SESSION);
    }

    // ── 200 happy paths ──────────────────────────────────────────────────────────────────────────

    @Test
    void getGlobalData_success_returns200WithJsonBody() throws Exception {
        when(getGlobalDataUseCase.getGlobalData(any())).thenReturn(DOMAIN_DATA);
        when(globalDataMapper.toResponse(DOMAIN_DATA)).thenReturn(RESPONSE_DTO);

        mockMvc.perform(get("/global-data").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.locale").value("es-mx"))
                .andExpect(jsonPath("$.featureFlags.salesforce").value(true))
                .andExpect(jsonPath("$.publicVariables.site_domain").value("https://www.liverpool.com.mx"))
                .andExpect(jsonPath("$.themes.primary_color").value("#E31837"))
                .andExpect(jsonPath("$.header").isMap())
                .andExpect(jsonPath("$.footer").isMap());
    }

    @Test
    void getGlobalData_emptyMaps_returns200WithEmptyObjects() throws Exception {
        GlobalData empty = new GlobalData("es-mx", null, null, null, null, null);
        GlobalDataResponse emptyDto = new GlobalDataResponse("es-mx", Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        when(getGlobalDataUseCase.getGlobalData(any())).thenReturn(empty);
        when(globalDataMapper.toResponse(empty)).thenReturn(emptyDto);

        mockMvc.perform(get("/global-data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locale").value("es-mx"))
                .andExpect(jsonPath("$.featureFlags").isMap())
                .andExpect(jsonPath("$.publicVariables").isMap())
                .andExpect(jsonPath("$.themes").isMap())
                .andExpect(jsonPath("$.header").isMap())
                .andExpect(jsonPath("$.footer").isMap());
    }

    @Test
    void getGlobalData_headerAndFooterPresent_returnedInResponse() throws Exception {
        when(getGlobalDataUseCase.getGlobalData(any())).thenReturn(DOMAIN_DATA);
        when(globalDataMapper.toResponse(DOMAIN_DATA)).thenReturn(RESPONSE_DTO);

        mockMvc.perform(get("/global-data").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.logo_url").value("https://cdn.liverpool.com.mx/logo.svg"))
                .andExpect(jsonPath("$.footer.copyright").value("© Liverpool 2025"));
    }

    @Test
    void getGlobalData_requestIdEchoedInResponseHeader() throws Exception {
        when(getGlobalDataUseCase.getGlobalData(any())).thenReturn(DOMAIN_DATA);
        when(globalDataMapper.toResponse(any())).thenReturn(RESPONSE_DTO);

        mockMvc.perform(get("/global-data").header("x-request-id", "gd-req-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("x-request-id", "gd-req-1"));
    }

    @Test
    void getGlobalData_noRequestId_generatesUuidInResponseHeader() throws Exception {
        when(getGlobalDataUseCase.getGlobalData(any())).thenReturn(DOMAIN_DATA);
        when(globalDataMapper.toResponse(any())).thenReturn(RESPONSE_DTO);

        mockMvc.perform(get("/global-data"))
                .andExpect(status().isOk())
                .andExpect(header().string("x-request-id", notNullValue()));
    }

    // ── preview mode ─────────────────────────────────────────────────────────────────────────────

    @Test
    void getGlobalData_previewHeaderPresent_useCaseReceivesPreviewTrue() throws Exception {
        when(getGlobalDataUseCase.getGlobalData(any())).thenReturn(DOMAIN_DATA);
        when(globalDataMapper.toResponse(any())).thenReturn(RESPONSE_DTO);

        mockMvc.perform(get("/global-data").header("x-preview", "1"))
                .andExpect(status().isOk());

        ArgumentCaptor<GlobalDataQuery> captor = ArgumentCaptor.forClass(GlobalDataQuery.class);
        Mockito.verify(getGlobalDataUseCase).getGlobalData(captor.capture());
        assertThat(captor.getValue().preview()).isTrue();
        assertThat(captor.getValue().brand()).isEqualTo("LP");
        assertThat(captor.getValue().locale()).isEqualTo("es-mx");
    }

    @Test
    void getGlobalData_noPreviewHeader_useCaseReceivesPreviewFalse() throws Exception {
        when(getGlobalDataUseCase.getGlobalData(any())).thenReturn(DOMAIN_DATA);
        when(globalDataMapper.toResponse(any())).thenReturn(RESPONSE_DTO);

        mockMvc.perform(get("/global-data"))
                .andExpect(status().isOk());

        ArgumentCaptor<GlobalDataQuery> captor = ArgumentCaptor.forClass(GlobalDataQuery.class);
        Mockito.verify(getGlobalDataUseCase).getGlobalData(captor.capture());
        assertThat(captor.getValue().preview()).isFalse();
    }

    // ── error scenarios ───────────────────────────────────────────────────────────────────────────

    @Test
    void getGlobalData_contentServiceUnavailable_returns502ProblemDetail() throws Exception {
        when(getGlobalDataUseCase.getGlobalData(any()))
                .thenThrow(new ContentServiceUnavailableException(
                        "global data unavailable", "upstream 500", null));

        mockMvc.perform(get("/global-data"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value("CONTENT_SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void getGlobalData_circuitBreakerOpen_returns503ProblemDetail() throws Exception {
        when(getGlobalDataUseCase.getGlobalData(any()))
                .thenThrow(new ServiceUnavailableException("global-data breaker open", null));

        mockMvc.perform(get("/global-data"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void getGlobalData_unexpectedError_returns500WithoutInternalDetail() throws Exception {
        when(getGlobalDataUseCase.getGlobalData(any()))
                .thenThrow(new RuntimeException("internal: secret=abc123"));

        mockMvc.perform(get("/global-data"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("UNEXPECTED_ERROR"))
                .andExpect(jsonPath("$.detail").value(
                        "An unexpected error occurred. Please try again later."));
    }
}
