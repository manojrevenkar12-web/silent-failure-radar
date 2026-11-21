package radar.core;

import java.util.Locale;

/**
 * The traffic split for a canary as an Istio {@code VirtualService}: the stable subset keeps most traffic and the
 * canary subset gets the rest, with the change's deploy id in a header route so engineers can reach the canary
 * directly. A matching {@code DestinationRule} defines the two subsets by the {@code version} label.
 */
public final class Rollout {
    private Rollout() {}

    public static String istio(String service, String namespace, String deployId, int canaryPercent) {
        if (canaryPercent < 0 || canaryPercent > 100) throw new IllegalArgumentException("canary percent must be 0..100");
        if (!service.matches("[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?") || !namespace.matches("[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?")) {
            throw new IllegalArgumentException("service and namespace must be Kubernetes names");
        }
        if (!deployId.matches("[A-Za-z0-9._-]{1,64}")) throw new IllegalArgumentException("deploy id must be 1-64 of [A-Za-z0-9._-]");
        String host = service + "." + namespace + ".svc.cluster.local";
        return String.format(Locale.ROOT, """
                apiVersion: networking.istio.io/v1
                kind: VirtualService
                metadata:
                  name: %1$s
                  namespace: %2$s
                  labels:
                    managed-by: silent-failure-radar
                spec:
                  hosts:
                    - %3$s
                  http:
                    - match:
                        - headers:
                            x-canary-deploy:
                              exact: "%4$s"
                      route:
                        - destination: {host: %3$s, subset: canary}
                    - route:
                        - destination: {host: %3$s, subset: stable}
                          weight: %5$d
                        - destination: {host: %3$s, subset: canary}
                          weight: %6$d
                ---
                apiVersion: networking.istio.io/v1
                kind: DestinationRule
                metadata:
                  name: %1$s
                  namespace: %2$s
                spec:
                  host: %3$s
                  subsets:
                    - name: stable
                      labels: {version: stable}
                    - name: canary
                      labels: {version: canary}
                """, service, namespace, host, deployId, 100 - canaryPercent, canaryPercent);
    }
}
