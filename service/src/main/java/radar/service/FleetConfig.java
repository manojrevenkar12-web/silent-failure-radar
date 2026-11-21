package radar.service;

import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import radar.core.DeployRisk;
import radar.core.Service;
import radar.core.Settings;
import radar.core.Team;
import tools.jackson.databind.JsonNode;

/** Starts with a small demo catalog and default risk weights; production replaces both through the API at deploy time. */
@Configuration
public class FleetConfig {
    @Bean
    Fleet fleet() {
        List<Team> teams = List.of(new Team("bookings", "slack-bookings", "pagerduty-bookings"), new Team("payments", "slack-payments", "pagerduty-payments"),
                new Team("discovery", "slack-discovery", "pagerduty-discovery"));
        List<Service> services = new ArrayList<>();
        services.add(new Service("payment-gateway", "payments", 1, List.of(), "payments.captured"));
        services.add(new Service("inventory", "bookings", 2, List.of(), null));
        services.add(new Service("booking-api", "bookings", 1, List.of("payment-gateway", "inventory"), "bookings.confirmed"));
        services.add(new Service("search", "discovery", 2, List.of("inventory"), "searches.served"));
        services.add(new Service("web-frontend", "discovery", 1, List.of("booking-api", "search"), null));
        JsonNode w = Settings.load("radar").path("defaultRiskWeights");
        double[] weights = new double[DeployRisk.FEATURES + 1];
        if (w.isArray() && w.size() == weights.length) for (int i = 0; i < weights.length; i++) weights[i] = w.get(i).asDouble();
        else weights = new double[] {-4.7, 0.8, -0.25, -0.08, 0.0, 0.03};
        return new Fleet(new radar.core.Catalog(services, teams), new DeployRisk(weights));
    }
}
