package radar.service;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import radar.core.Change;
import radar.core.Coverage;
import radar.core.DeployRisk;
import radar.core.Fingerprint;
import radar.core.IncidentBrief;
import radar.core.Mode;
import radar.core.Monitors;
import radar.core.Rollout;
import radar.core.Settings;
import tools.jackson.databind.JsonNode;

/**
 * POST /api/deploys/gate — CI asks before a rollout: risk score, the gate, and what it needs (tests, flag, canary).
 * POST /webhooks/datadog — a Datadog alert comes in, an incident brief goes back to the alert's channel.
 * GET /api/monitors/{service}/{mode} — the Datadog monitor that closes a gap, as code.
 */
@RestController
public class RadarController {
    private final Fleet fleet;
    private final JsonNode cfg = Settings.load("radar");

    private final int canaryPercent;

    public RadarController(Fleet fleet, @Value("${radar.canary-percent:5}") int canaryPercent) {
        this.fleet = fleet;
        this.canaryPercent = canaryPercent;
    }

    public record DeployRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,64}") String id, @NotBlank String service, @Min(1) @Max(1_000_000) int changedLines,
                                @Min(0) int testedLines, Boolean flagged) {
        boolean isFlagged() {
            return Boolean.TRUE.equals(flagged);
        }
    }

    public record GateResponse(String id, double risk, DeployRisk.Gate gate, int testsNeededForLines, boolean flagRequired, boolean canary) {}

    @PostMapping("/api/deploys/gate")
    public GateResponse gate(@Valid @RequestBody DeployRequest r) {
        Change c = new Change(r.id(), r.service(), r.changedLines(), Math.min(r.testedLines(), r.changedLines()), r.isFlagged());
        double risk = fleet.risk().score(DeployRisk.features(c, fleet.catalog().blastRadius(c.service()), fleet.recentIncidents(c.service())));
        JsonNode g = cfg.get("gate");
        DeployRisk.Gate gate = DeployRisk.gate(risk, g.get("canaryAt").asDouble(), g.get("testsAt").asDouble());
        int need = gate == DeployRisk.Gate.TESTS_FLAG_AND_CANARY ? Math.max(0, (int) Math.ceil(c.changedLines() * g.get("testedShareAfterGate").asDouble()) - c.testedLines()) : 0;
        fleet.recordDeploy(new IncidentBrief.RecentDeploy(c.id(), c.service(), Instant.now().getEpochSecond() / 60, c.flagged()));
        return new GateResponse(c.id(), Math.round(risk * 10000) / 10000.0, gate, need, gate == DeployRisk.Gate.TESTS_FLAG_AND_CANARY && !c.flagged(),
                gate != DeployRisk.Gate.SHIP);
    }

    public record DatadogAlert(@NotBlank String service, @NotBlank String mode, @NotBlank String title, String alertId) {}

    @PostMapping("/webhooks/datadog")
    public Map<String, Object> alert(@Valid @RequestBody DatadogAlert a) {
        Mode mode = Mode.valueOf(a.mode().toUpperCase(java.util.Locale.ROOT));
        String fp = Fingerprint.of(a.service(), mode, a.title());
        long now = Instant.now().getEpochSecond() / 60;
        List<IncidentBrief.Prior> priors = fleet.priors(fp);
        IncidentBrief.Brief brief = IncidentBrief.build(fleet.catalog(), a.service(), now, fleet.deploys(), priors, cfg.get("briefWindowMinutes").asInt());
        fleet.recordIncident(fp, new IncidentBrief.Prior(a.alertId() == null ? "alert-" + now : a.alertId(), now, List.of("root-cause fix", "add regression test")), a.service());
        return Map.of("fingerprint", fp, "brief", brief);
    }

    @GetMapping("/api/monitors/{service}/{mode}")
    public Map<String, Object> monitor(@PathVariable String service, @PathVariable String mode, @RequestParam(defaultValue = "json") String format) {
        Map<String, Object> m = Monitors.datadog(fleet.catalog(), service, Mode.valueOf(mode.toUpperCase(java.util.Locale.ROOT)), cfg.get("sloTarget").asDouble());
        return format.equals("terraform") ? Map.of("terraform", Monitors.terraform(m)) : m;
    }

    /** The Istio traffic split for a canary of this deploy, at {@code radar.canary-percent} from application.properties. */
    @GetMapping(value = "/api/rollouts/{service}/{deployId}", produces = "application/yaml")
    public String rollout(@PathVariable String service, @PathVariable String deployId, @RequestParam(defaultValue = "default") String namespace) {
        fleet.catalog().service(service);
        return Rollout.istio(service, namespace, deployId, canaryPercent);
    }

    @GetMapping("/api/coverage")
    public Object coverage() {
        return Coverage.gaps(fleet.catalog(), List.of(), List.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> bad(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(Map.of("error", e.getMessage()));
    }
}
