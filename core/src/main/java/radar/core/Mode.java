package radar.core;

/** How a service fails. Each mode needs a different kind of monitor to be seen. */
public enum Mode {
    /** Requests start failing: 5xx, exceptions. */
    ERROR_SPIKE,
    /** Requests get slow. */
    LATENCY,
    /** CPU, memory or connection pools run out. */
    SATURATION,
    /** Nothing errors, but the business outcome stops: bookings confirmed, emails sent, payouts made. */
    SILENT_DROP,
    /** A dependency fails and this service passes the failure on. */
    DEPENDENCY
}
