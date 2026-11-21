package radar.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The service catalog and the dependency graph the service mesh reports. */
public final class Catalog {
    private final Map<String, Service> services = new HashMap<>();
    private final Map<String, Team> teams = new HashMap<>();
    private final Map<String, List<String>> callers = new HashMap<>();
    private final Map<String, Double> blast = new HashMap<>();

    public Catalog(Collection<Service> services, Collection<Team> teams) {
        for (Team t : teams) this.teams.put(t.name(), t);
        for (Service s : services) {
            if (!this.teams.containsKey(s.team())) throw new IllegalArgumentException(s.name() + " has no owning team " + s.team());
            this.services.put(s.name(), s);
        }
        for (Service s : services) {
            for (String dep : s.calls()) {
                if (!this.services.containsKey(dep)) throw new IllegalArgumentException(s.name() + " calls unknown " + dep);
                callers.computeIfAbsent(dep, k -> new ArrayList<>()).add(s.name());
            }
        }
    }

    public Service service(String name) {
        Service s = services.get(name);
        if (s == null) throw new IllegalArgumentException("unknown service " + name);
        return s;
    }

    public Team owner(String service) {
        return teams.get(service(service).team());
    }

    public Collection<Service> services() {
        return services.values();
    }

    public Collection<Team> teams() {
        return teams.values();
    }

    /** Everything that calls this service, directly or through other services. */
    public Set<String> dependents(String service) {
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> todo = new ArrayDeque<>(callers.getOrDefault(service, List.of()));
        while (!todo.isEmpty()) {
            String s = todo.pop();
            if (seen.add(s)) todo.addAll(callers.getOrDefault(s, List.of()));
        }
        return seen;
    }

    /** Blast radius: this service plus everything that depends on it, weighted by tier (tier 1 = 3, tier 3 = 1). */
    public double blastRadius(String service) {
        return blast.computeIfAbsent(service, s -> {
            double w = tierWeight(service(s).tier());
            for (String d : dependents(s)) w += tierWeight(service(d).tier());
            return w;
        });
    }

    public static double tierWeight(int tier) {
        return 4 - tier;
    }
}
