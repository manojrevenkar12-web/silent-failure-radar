package radar.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * The first five minutes of an incident, gathered before a person opens a dashboard: who owns the service, what
 * changed recently on it and on what it calls, what depends on it, and whether this fingerprint has happened before
 * along with the actions that were promised then.
 */
public final class IncidentBrief {
    private IncidentBrief() {}

    /** A deploy the brief may point to. */
    public record RecentDeploy(String id, String service, long minute, boolean flagged) {}

    /** A past incident with the same fingerprint, and its open follow-up actions. */
    public record Prior(String id, long minute, List<String> openActions) {}

    public record Brief(String service, String team, String slack, int tier, List<RecentDeploy> suspects, List<String> dependents,
                        List<Prior> priors, String firstStep) {}

    public static Brief build(Catalog catalog, String service, long nowMinute, List<RecentDeploy> deploys, List<Prior> priors, int windowMinutes) {
        Service s = catalog.service(service);
        Team t = catalog.owner(service);
        Set<String> near = new java.util.HashSet<>(s.calls());
        near.add(service);
        List<RecentDeploy> suspects = new ArrayList<>();
        for (RecentDeploy d : deploys) {
            if (near.contains(d.service()) && d.minute() <= nowMinute && nowMinute - d.minute() <= windowMinutes) suspects.add(d);
        }
        suspects.sort(Comparator.comparingLong(RecentDeploy::minute).reversed().thenComparing(RecentDeploy::id));
        List<String> dependents = new ArrayList<>(catalog.dependents(service));
        dependents.sort(Comparator.comparingInt((String x) -> catalog.service(x).tier()).thenComparing(x -> x));
        String step;
        if (!suspects.isEmpty() && suspects.get(0).flagged()) step = "Turn off the feature flag of " + suspects.get(0).id();
        else if (!suspects.isEmpty()) step = "Roll back " + suspects.get(0).id() + " on " + suspects.get(0).service();
        else if (!priors.isEmpty()) step = "Same fingerprint as " + priors.get(0).id() + ": follow its runbook";
        else step = "Page " + t.name() + " and open the service dashboard";
        return new Brief(service, t.name(), t.slack(), s.tier(), suspects, dependents.subList(0, Math.min(10, dependents.size())), priors, step);
    }
}
