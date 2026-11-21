package radar.core;

/** Who owns services, and where their alerts go. */
public record Team(String name, String slack, String pagerduty) {}
