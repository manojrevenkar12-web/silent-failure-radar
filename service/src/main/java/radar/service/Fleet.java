package radar.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import radar.core.Catalog;
import radar.core.DeployRisk;
import radar.core.IncidentBrief;
import radar.core.Service;
import radar.core.Team;

/**
 * What the service knows about the fleet: the catalog (in production, synced from the service catalog and the mesh),
 * the fitted risk model, recent deploys and past incidents with their open follow-up actions.
 */
public final class Fleet {
    private volatile Catalog catalog;
    private volatile DeployRisk risk;
    private final List<IncidentBrief.RecentDeploy> deploys = new CopyOnWriteArrayList<>();
    private final Map<String, List<IncidentBrief.Prior>> priors = new ConcurrentHashMap<>();
    private final Map<String, Integer> recentIncidents = new ConcurrentHashMap<>();

    public Fleet(Catalog catalog, DeployRisk risk) {
        this.catalog = catalog;
        this.risk = risk;
    }

    public Catalog catalog() {
        return catalog;
    }

    public DeployRisk risk() {
        return risk;
    }

    public void replace(List<Service> services, List<Team> teams) {
        this.catalog = new Catalog(services, teams);
    }

    public void recordDeploy(IncidentBrief.RecentDeploy d) {
        deploys.add(d);
        if (deploys.size() > 5000) deploys.remove(0);
    }

    public List<IncidentBrief.RecentDeploy> deploys() {
        return new ArrayList<>(deploys);
    }

    public List<IncidentBrief.Prior> priors(String fingerprint) {
        return priors.getOrDefault(fingerprint, List.of());
    }

    public void recordIncident(String fingerprint, IncidentBrief.Prior p, String service) {
        priors.computeIfAbsent(fingerprint, k -> new CopyOnWriteArrayList<>()).add(p);
        recentIncidents.merge(service, 1, Integer::sum);
    }

    public int recentIncidents(String service) {
        return recentIncidents.getOrDefault(service, 0);
    }
}
