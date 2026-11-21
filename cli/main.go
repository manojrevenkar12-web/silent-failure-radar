// Command radar is the CI side of silent-failure-radar.
//
//	radar lint examples/datadog/*.json       check monitors-as-code before they are applied
//	radar gate -url http://radar:8080 ...     ask the radar service whether a deploy may roll out
package main

import (
	"fmt"
	"os"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: radar lint FILE... | radar gate -url URL -id ID -service NAME -changed N -tested N [-flagged]")
		os.Exit(2)
	}
	var code int
	switch os.Args[1] {
	case "lint":
		code = runLint(os.Args[2:], os.Stdout)
	case "gate":
		code = runGate(os.Args[2:], os.Stdout)
	default:
		fmt.Fprintf(os.Stderr, "unknown command %q\n", os.Args[1])
		code = 2
	}
	os.Exit(code)
}
