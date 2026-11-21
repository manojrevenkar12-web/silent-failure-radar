package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net/http"
	"time"
)

// GateResponse mirrors POST /api/deploys/gate.
type GateResponse struct {
	ID                  string  `json:"id"`
	Risk                float64 `json:"risk"`
	Gate                string  `json:"gate"`
	TestsNeededForLines int     `json:"testsNeededForLines"`
	FlagRequired        bool    `json:"flagRequired"`
	Canary              bool    `json:"canary"`
}

func runGate(args []string, w io.Writer) int {
	fs := flag.NewFlagSet("gate", flag.ContinueOnError)
	fs.SetOutput(w)
	url := fs.String("url", "http://localhost:8080", "radar service base URL")
	id := fs.String("id", "", "deploy id")
	service := fs.String("service", "", "service name")
	changed := fs.Int("changed", 0, "changed lines")
	tested := fs.Int("tested", 0, "changed lines covered by tests")
	flagged := fs.Bool("flagged", false, "the change ships behind a feature flag")
	timeout := fs.Duration("timeout", 5*time.Second, "request timeout")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if *id == "" || *service == "" || *changed <= 0 {
		fmt.Fprintln(w, "radar gate: -id, -service and -changed are required")
		return 2
	}
	body, _ := json.Marshal(map[string]any{"id": *id, "service": *service, "changedLines": *changed, "testedLines": *tested, "flagged": *flagged})
	client := &http.Client{Timeout: *timeout}
	resp, err := client.Post(*url+"/api/deploys/gate", "application/json", bytes.NewReader(body))
	if err != nil {
		// the radar being down must not block every deploy: fall back to a canary and say so
		fmt.Fprintf(w, "radar unreachable (%v): rolling out as a canary\n", err)
		fmt.Fprintln(w, "ROLLOUT=canary")
		return 0
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		msg, _ := io.ReadAll(resp.Body)
		fmt.Fprintf(w, "radar refused the request (HTTP %d): %s\n", resp.StatusCode, bytes.TrimSpace(msg))
		return 2
	}
	var g GateResponse
	if err := json.NewDecoder(resp.Body).Decode(&g); err != nil {
		fmt.Fprintf(w, "radar answer unreadable: %v\n", err)
		return 2
	}
	fmt.Fprintf(w, "risk %.3f -> %s\n", g.Risk, g.Gate)
	switch g.Gate {
	case "SHIP":
		fmt.Fprintln(w, "ROLLOUT=full")
		return 0
	case "CANARY":
		fmt.Fprintln(w, "ROLLOUT=canary")
		return 0
	default:
		if g.TestsNeededForLines > 0 {
			fmt.Fprintf(w, "blocked: add tests covering %d more changed lines\n", g.TestsNeededForLines)
		}
		if g.FlagRequired {
			fmt.Fprintln(w, "blocked: put the change behind a feature flag")
		}
		if g.TestsNeededForLines > 0 || g.FlagRequired {
			return 1
		}
		fmt.Fprintln(w, "ROLLOUT=canary")
		return 0
	}
}
