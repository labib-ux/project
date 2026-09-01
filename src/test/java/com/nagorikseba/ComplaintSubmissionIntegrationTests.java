package com.nagorikseba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.storage.upload-dir=target/test-uploads")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ComplaintSubmissionIntegrationTests {

    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
    };

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void citizenCanSubmitComplaintWithPhotoAndRetrieveIt() throws Exception {
        String token = registerCitizen("submission@example.com", "01712345679");
        MockMultipartFile photo = new MockMultipartFile("photos", "broken-road.png", "image/png", PNG_BYTES);

        MvcResult submission = mockMvc.perform(multipart("/api/complaints")
                        .file(photo)
                        .param("title", "Large pothole on Lake Road")
                        .param("description", "The pothole is dangerous for motorcycles, especially after rain.")
                        .param("category", "ROADS")
                        .param("latitude", "23.7465")
                        .param("longitude", "90.3742")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.attachments[0].fileType").value("IMAGE"))
                .andExpect(jsonPath("$.attachments[0].fileUrl").value(org.hamcrest.Matchers.startsWith("/uploads/")))
                .andExpect(jsonPath("$.timeline[0].toStatus").value("SUBMITTED"))
                .andReturn();

        JsonNode response = objectMapper.readTree(submission.getResponse().getContentAsString());
        String fileUrl = response.at("/attachments/0/fileUrl").asText();
        long complaintId = response.path("id").asLong();

        mockMvc.perform(get(fileUrl))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PNG_BYTES));

        mockMvc.perform(get("/api/complaints/{complaintId}", complaintId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Large pothole on Lake Road"));
    }

    @Test
    void submissionRejectsAFileThatIsNotAnImage() throws Exception {
        String token = registerCitizen("invalid-upload@example.com", "01712345670");
        MockMultipartFile textFile = new MockMultipartFile("photos", "notes.txt", "image/png", "not an image".getBytes());

        mockMvc.perform(multipart("/api/complaints")
                        .file(textFile)
                        .param("title", "Broken light")
                        .param("description", "Streetlight has been out for three nights.")
                        .param("category", "ELECTRICITY")
                        .param("latitude", "23.7465")
                        .param("longitude", "90.3742")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only valid JPEG, PNG, and WebP images are allowed"));
    }

    private String registerCitizen(String email, String phone) throws Exception {
        String request = """
                {
                  "fullName": "Test Citizen",
                  "email": "%s",
                  "phone": "%s",
                  "password": "a-secure-password"
                }
                """.formatted(email, phone);

        MvcResult registration = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(registration.getResponse().getContentAsString()).path("accessToken").asText();
    }
}
