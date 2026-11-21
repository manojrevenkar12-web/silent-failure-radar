package radar.core;

/**
 * A deploy as CI sees it.
 *
 * @param changedLines lines changed in the diff
 * @param testedLines changed lines that some test executes (diff coverage)
 * @param flagged whether the change ships behind a feature flag
 */
public record Change(String id, String service, int changedLines, int testedLines, boolean flagged) {
    public Change {
        if (testedLines < 0 || testedLines > changedLines) throw new IllegalArgumentException("tested lines must be within changed lines");
    }

    public int untestedLines() {
        return changedLines - testedLines;
    }

    public double testedShare() {
        return changedLines == 0 ? 1.0 : (double) testedLines / changedLines;
    }
}
