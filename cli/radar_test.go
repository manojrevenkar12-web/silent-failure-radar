package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func good() Monitor {
	return Monitor{
		Name: "[book] error spike SLO burn rate", Type: "query alert", Query: "sum(last_1h):... > 0.0144",
		Message: "{{#is_alert}}... Runbook: https://runbooks.internal/book/error_spike @pagerduty-team-b @slack-team-b{{/is_alert}}",
		Tags:    []string{"service:book", "team:team-b", "tier:1", "mode:error_spike"},
		Options: map[string]any{"thresholds": map[string]any{"critical": 0.0144, "warning": 0.006}},
	}
}

func TestGoodMonitorPasses(t *testing.T) {
	if p := Problems(good()); len(p) != 0 {
		t.Fatalf("unexpected problems: %v", p)
	}
}

func TestProblemsAreNamed(t *testing.T) {
	m := good()
	m.Message = "something broke"
	m.Options["thresholds"] = map[string]any{"critical": 0.01, "warning": 0.02}
	got := strings.Join(Problems(m), "; ")
	for _, want := range []string{"@slack-team-b", "does not page", "runbook", "not below critical"} {
		if !strings.Contains(got, want) {
			t.Errorf("missing %q in %q", want, got)
		}
	}
	s := good()
	s.Tags = []string{"service:book", "team:team-b", "tier:2", "mode:silent_drop"}
	if !strings.Contains(strings.Join(Problems(s), ";"), "stops reporting") {
		t.Error("silent-drop monitors must notify on no data")
	}
}

func TestLintFilesAndDuplicates(t *testing.T) {
	dir := t.TempDir()
	raw, _ := json.Marshal(good())
	a, b := filepath.Join(dir, "a.json"), filepath.Join(dir, "b.json")
	os.WriteFile(a, raw, 0o600)
	os.WriteFile(b, raw, 0o600)
	var out bytes.Buffer
	if code := runLint([]string{a}, &out); code != 0 {
		t.Fatalf("one good file failed: %s", out.String())
	}
	out.Reset()
	if code := runLint([]string{a, b}, &out); code != 1 || !strings.Contains(out.String(), "duplicates") {
		t.Fatalf("duplicate not caught: %d %s", code, out.String())
	}
}

func TestLintTheGeneratedExamples(t *testing.T) {
	files, _ := filepath.Glob("../examples/datadog/*.json")
	if len(files) == 0 {
		t.Skip("no generated examples yet")
	}
	var out bytes.Buffer
	if code := runLint(files, &out); code != 0 {
		t.Fatalf("generated monitors fail lint:\n%s", out.String())
	}
}

func TestGateBlocksAndShips(t *testing.T) {
	answer := GateResponse{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/deploys/gate" {
			http.NotFound(w, r)
			return
		}
		json.NewEncoder(w).Encode(answer)
	}))
	defer srv.Close()
	args := []string{"-url", srv.URL, "-id", "d1", "-service", "book", "-changed", "400", "-tested", "50"}
	var out bytes.Buffer
	answer = GateResponse{Gate: "TESTS_FLAG_AND_CANARY", TestsNeededForLines: 310, FlagRequired: true}
	if code := runGate(args, &out); code != 1 || !strings.Contains(out.String(), "310 more changed lines") {
		t.Fatalf("expected a block: %d %s", code, out.String())
	}
	out.Reset()
	answer = GateResponse{Gate: "SHIP"}
	if code := runGate(args, &out); code != 0 || !strings.Contains(out.String(), "ROLLOUT=full") {
		t.Fatalf("expected ship: %d %s", code, out.String())
	}
}

func TestGateFallsBackToCanaryWhenRadarIsDown(t *testing.T) {
	var out bytes.Buffer
	code := runGate([]string{"-url", "http://127.0.0.1:1", "-id", "d1", "-service", "book", "-changed", "10", "-timeout", "200ms"}, &out)
	if code != 0 || !strings.Contains(out.String(), "ROLLOUT=canary") {
		t.Fatalf("radar down must mean canary, not a blocked pipeline: %d %s", code, out.String())
	}
}
