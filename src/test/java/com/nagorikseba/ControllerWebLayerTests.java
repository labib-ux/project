package com.nagorikseba;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nagorikseba.complaint.api.AuthorityComplaintController;
import com.nagorikseba.complaint.api.CitizenComplaintController;
import com.nagorikseba.complaint.api.dto.ComplaintResponse;
import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.enums.Category;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import com.nagorikseba.complaint.domain.enums.ModerationStatus;
import com.nagorikseba.complaint.domain.enums.Priority;
import com.nagorikseba.complaint.lifecycle.ComplaintLifecycleService;
import com.nagorikseba.complaint.repo.ComplaintRepository;
import com.nagorikseba.complaint.service.AttachmentService;
import com.nagorikseba.complaint.service.ComplaintQueryService;
import com.nagorikseba.complaint.submission.AnonymousComplaintSubmission;
import com.nagorikseba.complaint.submission.StandardComplaintSubmission;
import com.nagorikseba.controller.HomeController;
import com.nagorikseba.controller.PublicController;
import com.nagorikseba.identity.api.AuthController;
import com.nagorikseba.identity.api.dto.AuthResponse;
import com.nagorikseba.identity.api.dto.UserResponse;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.identity.repo.MembershipRepository;
import com.nagorikseba.identity.repo.UserRepository;
import com.nagorikseba.identity.service.AuthService;
import com.nagorikseba.municipality.api.WardBoundaryController;
import com.nagorikseba.municipality.controller.MunicipalityController;
import com.nagorikseba.municipality.dto.MunicipalityResponse;
import com.nagorikseba.municipality.entity.Municipality;
import com.nagorikseba.municipality.entity.Ward;
import com.nagorikseba.municipality.repository.DepartmentRepository;
import com.nagorikseba.municipality.repository.MunicipalityRepository;
import com.nagorikseba.municipality.repository.WardRepository;
import com.nagorikseba.municipality.service.MunicipalityService;
import com.nagorikseba.municipality.service.WardBoundaryService;
import com.nagorikseba.repository.SlaRuleRepository;
import com.nagorikseba.shared.config.SecurityConfig;
import com.nagorikseba.shared.security.JwtTokenProvider;
import com.nagorikseba.shared.security.PrincipalContext;
import com.nagorikseba.shared.time.ClockConfig;
import com.nagorikseba.transparency.HeatmapService;
import com.nagorikseba.transparency.ScoreboardService;
import com.nagorikseba.transparency.api.PublicTransparencyController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer slices (Phase 6, D6): one slice per controller family plus the
 * rate-limit proof. Service beans are mocked; the security chain, validation
 * and error bodies are real.
 *
 * <p>Rate limiting is enabled for this class only
 * ({@code app.rate-limit.enabled=true}); every other suite runs with it off.
 * Tests that must not share a bucket send distinct {@code X-Forwarded-For}
 * addresses — the 429 test owns {@code 10.9.9.9} alone.
 */
@WebMvcTest({
        AuthController.class,
        CitizenComplaintController.class,
        AuthorityComplaintController.class,
        MunicipalityController.class,
        WardBoundaryController.class,
        PublicController.class,
        PublicTransparencyController.class,
        HomeController.class
})
@Import({SecurityConfig.class, ClockConfig.class})
@TestPropertySource(properties = "app.rate-limit.enabled=true")
class ControllerWebLayerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @org.springframework.boot.test.mock.mockito.MockBean
    private JwtTokenProvider jwtTokenProvider;

    @org.springframework.boot.test.mock.mockito.MockBean
    private AuthService authService;

    @org.springframework.boot.test.mock.mockito.MockBean
    private PrincipalContext principalContext;

    @org.springframework.boot.test.mock.mockito.MockBean
    private StandardComplaintSubmission standardSubmission;

    @org.springframework.boot.test.mock.mockito.MockBean
    private AnonymousComplaintSubmission anonymousSubmission;

    @org.springframework.boot.test.mock.mockito.MockBean
    private ComplaintLifecycleService lifecycleService;

    @org.springframework.boot.test.mock.mockito.MockBean
    private ComplaintQueryService queryService;

    @org.springframework.boot.test.mock.mockito.MockBean
    private ComplaintRepository complaintRepository;

    @org.springframework.boot.test.mock.mockito.MockBean
    private UserRepository userRepository;

    @org.springframework.boot.test.mock.mockito.MockBean
    private MembershipRepository membershipRepository;

    @org.springframework.boot.test.mock.mockito.MockBean
    private WardRepository wardRepository;

    @org.springframework.boot.test.mock.mockito.MockBean
    private SlaRuleRepository slaRuleRepository;

    @org.springframework.boot.test.mock.mockito.MockBean
    private AttachmentService attachmentService;

    @org.springframework.boot.test.mock.mockito.MockBean
    private MunicipalityService municipalityService;

    @org.springframework.boot.test.mock.mockito.MockBean
    private MunicipalityRepository municipalityRepository;

    @org.springframework.boot.test.mock.mockito.MockBean
    private DepartmentRepository departmentRepository;

    @org.springframework.boot.test.mock.mockito.MockBean
    private WardBoundaryService wardBoundaryService;

    @org.springframework.boot.test.mock.mockito.MockBean
    private HeatmapService heatmapService;

    @org.springframework.boot.test.mock.mockito.MockBean
    private ScoreboardService scoreboardService;

    @org.springframework.boot.test.context.TestConfiguration
    static class SliceConfig {
        @org.springframework.context.annotation.Bean
        com.nagorikseba.shared.config.StorageProperties storageProperties() {
            com.nagorikseba.shared.config.StorageProperties properties =
                    new com.nagorikseba.shared.config.StorageProperties();
            properties.setUploadDir("uploads");
            return properties;
        }
    }

    // ------------------------------------------------------------------ auth

    @Test
    void loginValidationFailsWithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.identifier").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    @Test
    void registerHappyPathReturns201() throws Exception {
        when(authService.register(any(), any())).thenReturn(authResponse());
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Slice Citizen","email":"slice@example.com",
                                 "phone":"01712345678","password":"a-secure-password"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").value("slice-access-token"));
    }

    @Test
    void sixthLoginFromOneIpIsRateLimited() throws Exception {
        when(authService.login(any(), any())).thenReturn(authResponse());
        String body = "{\"identifier\":\"slice@example.com\",\"password\":\"a-secure-password\"}";
        for (int attempt = 1; attempt <= 5; attempt++) {
            mockMvc.perform(post("/api/auth/login")
                            .header("X-Forwarded-For", "10.9.9.9")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", "10.9.9.9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(429))
                .andExpect(header().exists("Retry-After"));
    }

    // ------------------------------------------------------------- authz matrix

    @Test
    void protectedEndpointsNeedAToken() throws Exception {
        mockMvc.perform(get("/api/complaints/my"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/authority/dashboard"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "CITIZEN")
    void citizenCannotReachAuthorityEndpoints() throws Exception {
        mockMvc.perform(get("/api/authority/dashboard"))
                .andExpect(status().isForbidden());
    }

    // --------------------------------------------------------------- citizen api

    @Test
    @WithMockUser(roles = "CITIZEN")
    void submitWithoutTitleIsAValidationFailure() throws Exception {
        when(principalContext.currentUserId()).thenReturn(Optional.of(7L));
        when(userRepository.findById(7L)).thenReturn(Optional.of(citizen(7L)));
        mockMvc.perform(multipart("/api/complaints")
                        .file(new MockMultipartFile("photos", "issue.png", "image/png", new byte[]{1, 2, 3}))
                        .param("description", "desc")
                        .param("category", "ROADS")
                        .param("latitude", "23.79")
                        .param("longitude", "90.41"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.title").exists());
    }

    @Test
    @WithMockUser(roles = "CITIZEN")
    void submitHappyPathReturns201() throws Exception {
        when(principalContext.currentUserId()).thenReturn(Optional.of(7L));
        when(userRepository.findById(7L)).thenReturn(Optional.of(citizen(7L)));
        when(standardSubmission.submit(any(), any())).thenReturn(complaint());
        when(queryService.describe(any())).thenReturn(complaintResponse());
        mockMvc.perform(multipart("/api/complaints")
                        .file(new MockMultipartFile("photos", "issue.png", "image/png", new byte[]{1, 2, 3}))
                        .param("title", "Pothole")
                        .param("description", "desc")
                        .param("category", "ROADS")
                        .param("latitude", "23.79")
                        .param("longitude", "90.41"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.referenceCode").value("NS-2026-000001"));
    }

    // -------------------------------------------------------------- authority api

    @Test
    @WithMockUser(roles = "DEPT_OFFICER")
    void verifyHappyPathReturns200() throws Exception {
        when(complaintRepository.findByReferenceCode("NS-2026-000001"))
                .thenReturn(Optional.of(complaint()));
        when(principalContext.requireUserId()).thenReturn(9L);
        when(lifecycleService.execute(any())).thenReturn(complaint());
        when(queryService.describe(any())).thenReturn(complaintResponse());
        mockMvc.perform(post("/api/authority/complaints/NS-2026-000001/verify")
                        .param("note", "ok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"));
    }

    // ------------------------------------------------------------------ public

    @Test
    void publicHeatmapIsOpen() throws Exception {
        when(heatmapService.heatmap(anyString(), any(double.class), any(double.class),
                any(double.class), any(double.class)))
                .thenReturn(Map.of("clustered", false, "points", List.of()));
        mockMvc.perform(get("/api/public/heatmap")
                        .param("municipality", "dhaka-north")
                        .param("minLng", "90.36").param("minLat", "23.72")
                        .param("maxLng", "90.44").param("maxLat", "23.88"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clustered").value(false));
    }

    @Test
    void publicWardsLookupIsOpen() throws Exception {
        Municipality municipality = Municipality.builder()
                .id(1L).slug("dhaka-north").name("Dhaka North").isActive(true).build();
        when(municipalityRepository.findBySlugAndIsActiveTrue("dhaka-north"))
                .thenReturn(Optional.of(municipality));
        when(wardBoundaryService.resolvePoint(1L, 23.79, 90.41))
                .thenReturn(Ward.builder().id(3L).municipality(municipality)
                        .wardNumber(1).areaName("Gulshan").isActive(true).build());
        mockMvc.perform(get("/api/municipalities/dhaka-north/wards/containing")
                        .param("lat", "23.79").param("lng", "90.41"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wardNumber").value(1));
    }

    @Test
    void municipalityListIsOpen() throws Exception {
        when(municipalityService.findAllActiveMunicipalities()).thenReturn(List.of(
                MunicipalityResponse.builder().id(1L).slug("dhaka-north")
                        .name("Dhaka North").isActive(true).build()));
        mockMvc.perform(get("/api/municipalities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").value("dhaka-north"));
    }

    @Test
    void landingPageRenders() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ helpers

    private static AuthResponse authResponse() {
        return new AuthResponse("slice-access-token", "Bearer", 900L, "slice-refresh",
                new UserResponse(1L, "Slice Citizen", "slice@example.com",
                        "01712345678", com.nagorikseba.enums.UserRole.CITIZEN, java.util.Set.of()));
    }

    private static User citizen(Long id) {
        return User.builder().id(id).fullName("Slice Citizen")
                .email("slice@example.com").phone("01712345678")
                .passwordHash("hash").role(com.nagorikseba.enums.UserRole.CITIZEN)
                .active(true).build();
    }

    private static Complaint complaint() {
        return Complaint.builder().id(11L).referenceCode("NS-2026-000001")
                .municipality(Municipality.builder().id(1L).slug("dhaka-north")
                        .name("Dhaka North").isActive(true).build())
                .title("Pothole").description("desc").category(Category.ROADS)
                .status(ComplaintStatus.VERIFIED).priority(Priority.NORMAL)
                .publicVisible(true).moderationStatus(ModerationStatus.APPROVED)
                .submittedAt(Instant.parse("2026-09-01T00:00:00Z")).build();
    }

    private static ComplaintResponse complaintResponse() {
        return ComplaintResponse.builder().id(11L).referenceCode("NS-2026-000001")
                .title("Pothole").description("desc").category(Category.ROADS)
                .status(ComplaintStatus.VERIFIED).priority(Priority.NORMAL)
                .publicVisible(true).moderationStatus(ModerationStatus.APPROVED)
                .attachments(List.of()).timeline(List.of()).build();
    }
}
