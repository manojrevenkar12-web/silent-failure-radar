package radar.core;

/** The three monitor styles the fleet uses. */
public enum MonitorKind {
    /** "error rate above 5% for 5 minutes": simple, noisy, blind to slow burns and small regressions. */
    STATIC_THRESHOLD,
    /** Multi-window SLO burn rate: pages when the error budget burns fast, warns when it burns steadily. */
    BURN_RATE,
    /** Anomaly detection on a business metric, for failures that throw no errors. */
    ANOMALY
}
