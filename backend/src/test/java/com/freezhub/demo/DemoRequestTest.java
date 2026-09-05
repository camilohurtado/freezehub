package com.freezhub.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.freezhub.ContainersConfig;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Booking a demo, end to end (FZ-083). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class DemoRequestTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DemoRequestRepository requests;

    @BeforeEach
    void setUp() {
        requests.deleteAll();
    }

    private org.springframework.test.web.servlet.ResultActions submit(String body) throws Exception {
        return mockMvc.perform(post("/api/demo-requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    void anyoneCanBookADemoWithoutAnAccount() throws Exception {
        submit("""
                {"name":"Dana Ruiz","email":"dana@northwind.test","company":"Northwind",
                 "teamSize":"11-50","message":"We freeze for Black Friday.","source":"pricing-page"}
                """)
                .andExpect(status().isAccepted());

        List<DemoRequest> stored = requests.findAll();
        assertThat(stored).hasSize(1);
        DemoRequest request = stored.getFirst();
        assertThat(request.getName()).isEqualTo("Dana Ruiz");
        assertThat(request.getEmail()).isEqualTo("dana@northwind.test");
        assertThat(request.getCompany()).isEqualTo("Northwind");
        assertThat(request.getTeamSize()).isEqualTo("11-50");
        assertThat(request.getSource()).isEqualTo("pricing-page");
        assertThat(request.getStatus()).isEqualTo(DemoRequestStatus.NEW);
        assertThat(request.hasBeenNotified()).isFalse();
    }

    @Test
    void onlyNameEmailAndCompanyAreRequired() throws Exception {
        submit("""
                {"name":"Dana Ruiz","email":"dana@northwind.test","company":"Northwind"}
                """)
                .andExpect(status().isAccepted());

        DemoRequest request = requests.findAll().getFirst();
        assertThat(request.getTeamSize()).isNull();
        assertThat(request.getMessage()).isNull();
        assertThat(request.getSource()).isNull();
    }

    @Test
    void rejectsSomethingThatIsNotAnEmail() throws Exception {
        submit("""
                {"name":"Dana Ruiz","email":"not-an-email","company":"Northwind"}
                """)
                .andExpect(status().isBadRequest());

        assertThat(requests.findAll()).isEmpty();
    }

    @Test
    void refusesAMessageLongerThanItWillStore() throws Exception {
        // The first endpoint anyone can post to without a credential, so what it will
        // accept is part of its security rather than a formatting preference.
        String tooLong = "x".repeat(DemoRequest.MAX_MESSAGE_LENGTH + 1);
        submit("""
                {"name":"Dana Ruiz","email":"dana@northwind.test","company":"Northwind","message":"%s"}
                """.formatted(tooLong))
                .andExpect(status().isBadRequest());

        assertThat(requests.findAll()).isEmpty();
    }

    @Test
    void theRequestIsStoredEvenWithNowhereToAnnounceIt() throws Exception {
        // The lead is the row; the Slack message is a convenience on top of it. No webhook
        // is configured in tests, and losing a lead because a chat integration was not set
        // up would be the worst possible failure for this endpoint.
        submit("""
                {"name":"Dana Ruiz","email":"dana@northwind.test","company":"Northwind"}
                """)
                .andExpect(status().isAccepted());

        assertThat(requests.findAll()).hasSize(1);
    }

    @Test
    void thereIsNoWayToReadDemoRequestsThroughTheApi() throws Exception {
        // The table has no organization_id, so there is no organization to scope a read
        // to. A GET here must not quietly become "everyone's leads".
        mockMvc.perform(get("/api/demo-requests"))
                .andExpect(status().is4xxClientError());
    }
}
