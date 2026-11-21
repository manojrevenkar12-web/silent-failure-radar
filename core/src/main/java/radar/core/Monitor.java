package radar.core;

/**
 * A monitor on one service for one failure mode.
 *
 * @param routedToOwner false when it notifies a shared channel nobody owns
 */
public record Monitor(String service, Mode mode, MonitorKind kind, boolean routedToOwner) {}
