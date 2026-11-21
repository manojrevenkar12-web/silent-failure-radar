package main

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"sort"
	"strings"
)

// Monitor is the part of a Datadog monitor definition the linter checks.
type Monitor struct {
	Name     string         `json:"name"`
	Type     string         `json:"type"`
	Query    string         `json:"query"`
	Message  string         `json:"message"`
	Tags     []string       `json:"tags"`
	Priority int            `json:"priority"`
	Options  map[string]any `json:"options"`
}

func tag(m Monitor, key string) string {
	for _, t := range m.Tags {
		if strings.HasPrefix(t, key+":") {
			return strings.TrimPrefix(t, key+":")
		}
	}
	return ""
}

// Problems returns what is wrong with one monitor; empty means it may be applied.
func Problems(m Monitor) []string {
	var out []string
	team, service, mode := tag(m, "team"), tag(m, "service"), tag(m, "mode")
	if team == "" || service == "" || mode == "" {
		out = append(out, "needs service:, team: and mode: tags so alerts reach an owner")
	}
	if strings.TrimSpace(m.Query) == "" {
		out = append(out, "empty query")
	}
	if team != "" && !strings.Contains(m.Message, "@slack-"+team) {
		out = append(out, "message does not notify the owning team's channel @slack-"+team)
	}
	if tag(m, "tier") == "1" && !strings.Contains(m.Message, "@pagerduty-") {
		out = append(out, "tier 1 monitor does not page")
	}
	if !strings.Contains(m.Message, "runbooks.internal/") {
		out = append(out, "no runbook link")
	}
	if th, ok := m.Options["thresholds"].(map[string]any); ok {
		c, cok := th["critical"].(float64)
		w, wok := th["warning"].(float64)
		if cok && wok && w >= c {
			out = append(out, fmt.Sprintf("warning threshold %g is not below critical %g", w, c))
		}
	}
	if mode == "silent_drop" {
		if nd, _ := m.Options["notify_no_data"].(bool); !nd {
			out = append(out, "a silent-drop monitor must alert when the metric stops reporting")
		}
	}
	return out
}

func runLint(files []string, w io.Writer) int {
	if len(files) == 0 {
		fmt.Fprintln(w, "radar lint: no files")
		return 2
	}
	seen := map[string]string{}
	bad := 0
	sort.Strings(files)
	for _, f := range files {
		raw, err := os.ReadFile(f)
		if err != nil {
			fmt.Fprintf(w, "%s: %v\n", f, err)
			bad++
			continue
		}
		var m Monitor
		if err := json.Unmarshal(raw, &m); err != nil {
			fmt.Fprintf(w, "%s: not a monitor: %v\n", f, err)
			bad++
			continue
		}
		probs := Problems(m)
		key := tag(m, "service") + "/" + tag(m, "mode")
		if prev, ok := seen[key]; ok && key != "/" {
			probs = append(probs, "duplicates "+prev)
		}
		seen[key] = f
		for _, p := range probs {
			fmt.Fprintf(w, "%s: %s\n", f, p)
		}
		if len(probs) > 0 {
			bad++
		}
	}
	fmt.Fprintf(w, "%d monitors, %d with problems\n", len(files), bad)
	if bad > 0 {
		return 1
	}
	return 0
}
