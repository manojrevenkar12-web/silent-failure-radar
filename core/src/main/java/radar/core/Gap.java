package radar.core;

/**
 * A failure mode on a service that no monitor sees well enough.
 *
 * @param caughtByChance past incidents in this mode that a customer or an engineer noticed first
 * @param score what closing the gap is worth: blast radius times how often it has hurt
 * @param fix the monitor that closes it
 */
public record Gap(String service, Mode mode, int caughtByChance, double score, MonitorKind fix) {}
