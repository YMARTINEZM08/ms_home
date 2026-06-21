package com.liverpool.ms_home.adapter.inbound.rest.controller;

import java.util.List;
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

import com.liverpool.ms_home.adapter.inbound.rest.dto.HomeBlockResponse;
import com.liverpool.ms_home.adapter.inbound.rest.dto.HomePageResponse;
import com.liverpool.ms_home.adapter.inbound.rest.mapper.HomePageMapper;
import com.liverpool.ms_home.config.ContentstackProperties;
import com.liverpool.ms_home.config.MdcRequestContextFilter;
import com.liverpool.ms_home.config.SecurityConfig;
import com.liverpool.ms_home.domain.error.ContentServiceUnavailableException;
import com.liverpool.ms_home.domain.error.HomeDefinitionNotFoundException;
import com.liverpool.ms_home.domain.error.ServiceUnavailableException;
import com.liverpool.ms_home.domain.model.home.HomePageQuery;
import com.liverpool.ms_home.domain.model.home.HomePage;
import com.liverpool.ms_home.domain.model.home.SessionContext;
import com.liverpool.ms_home.domain.port.inbound.GetHomePageUseCase;
import com.liverpool.ms_home.domain.port.outbound.SessionContextPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test for {@link HomeController} — loads only the web layer.
 * {@link SecurityConfig} is imported explicitly so the custom permitAll rule applies instead of
 * Spring Boot's default basic-auth.
 */
@WebMvcTest(HomeController.class)
@Import({SecurityConfig.class, MdcRequestContextFilter.class})
class HomeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GetHomePageUseCase getHomePageUseCase;

    @MockitoBean
    private SessionContextPort sessionContextPort;

    @MockitoBean
    private HomePageMapper homePageMapper;

    @MockitoBean
    private ContentstackProperties contentstackProperties;

    private static final SessionContext GUEST_SESSION = new SessionContext(false, "LP", "WEB", "es-mx");
    private static final SessionContext AUTH_SESSION   = new SessionContext(true,  "LP", "WEB", "es-mx");

    @BeforeEach
    void setUp() {
        when(contentstackProperties.previewHeader()).thenReturn("x-preview");
    }

    // ── 200 happy paths ──────────────────────────────────────────────────────────────────────────

    @Test
    void getHomePage_guestSession_returns200WithJsonBody() throws Exception {
        when(sessionContextPort.currentContext()).thenReturn(GUEST_SESSION);
        when(getHomePageUseCase.getHomePage(any())).thenReturn(new HomePage("es-mx", null, null, List.of()));
        when(homePageMapper.toResponse(any())).thenReturn(new HomePageResponse("es-mx", null, Map.of(), List.of()));

        mockMvc.perform(get("/home").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.locale").value("es-mx"))
                .andExpect(jsonPath("$.blocks").isArray());
    }

    @Test
    void getHomePage_authenticatedSession_returns200() throws Exception {
        when(sessionContextPort.currentContext()).thenReturn(AUTH_SESSION);
        when(getHomePageUseCase.getHomePage(any())).thenReturn(new HomePage("es-mx", null, null, List.of()));
        when(homePageMapper.toResponse(any())).thenReturn(new HomePageResponse("es-mx", null, Map.of(), List.of()));

        mockMvc.perform(get("/home").header("x-authenticated", "true"))
                .andExpect(status().isOk());
    }

    @Test
    void getHomePage_inboundRequestId_echoedInResponseHeader() throws Exception {
        when(sessionContextPort.currentContext()).thenReturn(GUEST_SESSION);
        when(getHomePageUseCase.getHomePage(any())).thenReturn(new HomePage("es-mx", null, null, List.of()));
        when(homePageMapper.toResponse(any())).thenReturn(new HomePageResponse("es-mx", null, Map.of(), List.of()));

        mockMvc.perform(get("/home").header("x-request-id", "test-req-id"))
                .andExpect(status().isOk())
                .andExpect(header().string("x-request-id", equalTo("test-req-id")));
    }

    @Test
    void getHomePage_noRequestId_generatesUuidInResponseHeader() throws Exception {
        when(sessionContextPort.currentContext()).thenReturn(GUEST_SESSION);
        when(getHomePageUseCase.getHomePage(any())).thenReturn(new HomePage("es-mx", null, null, List.of()));
        when(homePageMapper.toResponse(any())).thenReturn(new HomePageResponse("es-mx", null, Map.of(), List.of()));

        mockMvc.perform(get("/home"))
                .andExpect(status().isOk())
                .andExpect(header().string("x-request-id", notNullValue()));
    }

    @Test
    void getHomePage_responseContainsBlocks() throws Exception {
        when(sessionContextPort.currentContext()).thenReturn(GUEST_SESSION);
        when(getHomePageUseCase.getHomePage(any())).thenReturn(new HomePage("es-mx", null, null, List.of()));

        HomeBlockResponse blockDto = new HomeBlockResponse(
                "uid-1", "BANNER", "STATIC", null, null, null, null, null);
        when(homePageMapper.toResponse(any()))
                .thenReturn(new HomePageResponse("es-mx", null, Map.of(), List.of(blockDto)));

        mockMvc.perform(get("/home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks[0].blockId").value("uid-1"))
                .andExpect(jsonPath("$.blocks[0].kind").value("STATIC"));
    }

    // ── error scenarios ───────────────────────────────────────────────────────────────────────────

    @Test
    void getHomePage_contentServiceUnavailable_returns502ProblemDetail() throws Exception {
        when(sessionContextPort.currentContext()).thenReturn(GUEST_SESSION);
        when(getHomePageUseCase.getHomePage(any()))
                .thenThrow(new ContentServiceUnavailableException(
                        "content-service is down", "upstream 500", null));

        mockMvc.perform(get("/home"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value("CONTENT_SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void getHomePage_homeDefinitionNotFound_returns404ProblemDetail() throws Exception {
        when(sessionContextPort.currentContext()).thenReturn(GUEST_SESSION);
        when(getHomePageUseCase.getHomePage(any()))
                .thenThrow(new HomeDefinitionNotFoundException("not found", "brand=LP locale=es-mx"));

        mockMvc.perform(get("/home"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("HOME_DEFINITION_NOT_FOUND"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void getHomePage_circuitBreakerOpen_returns503ProblemDetail() throws Exception {
        when(sessionContextPort.currentContext()).thenReturn(GUEST_SESSION);
        when(getHomePageUseCase.getHomePage(any()))
                .thenThrow(new ServiceUnavailableException("breaker open", null));

        mockMvc.perform(get("/home"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void getHomePage_unexpectedError_returns500WithoutInternalDetail() throws Exception {
        when(sessionContextPort.currentContext()).thenReturn(GUEST_SESSION);
        when(getHomePageUseCase.getHomePage(any()))
                .thenThrow(new RuntimeException("secret internal detail: password=abc123"));

        mockMvc.perform(get("/home"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("UNEXPECTED_ERROR"))
                .andExpect(jsonPath("$.detail").value(
                        "An unexpected error occurred. Please try again later."));
    }

    // ── input validation ─────────────────────────────────────────────────────────────────────────

    @Test
    void getHomePage_pathExceedsMaxLength_returns400ValidationError() throws Exception {
        when(sessionContextPort.currentContext()).thenReturn(GUEST_SESSION);
        String tooLongPath = "a".repeat(257);

        mockMvc.perform(get("/home").param("path", tooLongPath))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    // ── preview mode ─────────────────────────────────────────────────────────────────────────────

    @Test
    void getHomePage_previewHeaderPresent_useCaseReceivesPreviewTrue() throws Exception {
        when(sessionContextPort.currentContext()).thenReturn(GUEST_SESSION);
        when(getHomePageUseCase.getHomePage(any())).thenReturn(new HomePage("es-mx", null, null, List.of()));
        when(homePageMapper.toResponse(any())).thenReturn(new HomePageResponse("es-mx", null, Map.of(), List.of()));

        mockMvc.perform(get("/home").header("x-preview", "true"))
                .andExpect(status().isOk());

        ArgumentCaptor<HomePageQuery> captor = ArgumentCaptor.forClass(HomePageQuery.class);
        Mockito.verify(getHomePageUseCase).getHomePage(captor.capture());
        assertThat(captor.getValue().preview()).isTrue();
    }

    @Test
    void getHomePage_noPreviewHeader_useCaseReceivesPreviewFalse() throws Exception {
        when(sessionContextPort.currentContext()).thenReturn(GUEST_SESSION);
        when(getHomePageUseCase.getHomePage(any())).thenReturn(new HomePage("es-mx", null, null, List.of()));
        when(homePageMapper.toResponse(any())).thenReturn(new HomePageResponse("es-mx", null, Map.of(), List.of()));

        mockMvc.perform(get("/home"))
                .andExpect(status().isOk());

        ArgumentCaptor<HomePageQuery> captor = ArgumentCaptor.forClass(HomePageQuery.class);
        Mockito.verify(getHomePageUseCase).getHomePage(captor.capture());
        assertThat(captor.getValue().preview()).isFalse();
    }
}
