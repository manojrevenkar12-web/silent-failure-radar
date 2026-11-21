package radar.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** The radar as a service: CI asks it for a deploy gate, Datadog webhooks get an incident brief back. */
@SpringBootApplication
public class RadarApplication {
    public static void main(String[] args) {
        SpringApplication.run(RadarApplication.class, args);
    }
}
