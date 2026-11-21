package radar.service;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class RadarControllerTest {
    @Autowired
    MockMvc mvc;

    @Test
    void aBigUntestedChangeOnTheCheckoutPathNeedsTestsAFlagAndACanary() throws Exception {
        mvc.perform(post("/api/deploys/gate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"d1\",\"service\":\"payment-gateway\",\"changedLines\":4000,\"testedLines\":100,\"flagged\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gate").value("TESTS_FLAG_AND_CANARY"))
                .andExpect(jsonPath("$.flagRequired").value(true))
                .andExpect(jsonPath("$.testsNeededForLines").value(3500));
    }

    @Test
    void aSmallTestedChangeShipsStraightOut() throws Exception {
        mvc.perform(post("/api/deploys/gate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"d2\",\"service\":\"search\",\"changedLines\":10,\"testedLines\":10,\"flagged\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gate").value("SHIP"));
    }

    @Test
    void badInputIsRefused() throws Exception {
        mvc.perform(post("/api/deploys/gate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"bad id!\",\"service\":\"search\",\"changedLines\":10,\"testedLines\":10}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/deploys/gate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"d3\",\"service\":\"nope\",\"changedLines\":10,\"testedLines\":10}"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void anAlertGetsABriefAndARepeatIsRecognised() throws Exception {
        mvc.perform(post("/api/deploys/gate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"d9\",\"service\":\"inventory\",\"changedLines\":50,\"testedLines\":40,\"flagged\":true}"));
        String alert = "{\"service\":\"booking-api\",\"mode\":\"error_spike\",\"title\":\"NullPointerException in booking-api BookingHandler.java:%d\",\"alertId\":\"a%d\"}";
        mvc.perform(post("/webhooks/datadog").contentType(MediaType.APPLICATION_JSON).content(alert.formatted(120, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brief.team").value("bookings"))
                .andExpect(jsonPath("$.brief.suspects[0].id").value("d9"))
                .andExpect(jsonPath("$.brief.firstStep").value("Turn off the feature flag of d9"));
        mvc.perform(post("/webhooks/datadog").contentType(MediaType.APPLICATION_JSON).content(alert.formatted(987, 2)))
                .andExpect(jsonPath("$.brief.priors[0].id").value("a1"));
    }

    @Test
    void monitorsAsCode() throws Exception {
        mvc.perform(get("/api/monitors/booking-api/silent_drop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options.notify_no_data").value(true))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("@pagerduty-bookings")));
        mvc.perform(get("/api/monitors/booking-api/error_spike").param("format", "terraform"))
                .andExpect(jsonPath("$.terraform").value(org.hamcrest.Matchers.containsString("resource \"datadog_monitor\"")));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
