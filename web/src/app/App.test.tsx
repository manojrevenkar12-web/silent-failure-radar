import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import fixture from "../test/report.fixture.json";
import { App } from "./App";

afterEach(() => vi.unstubAllGlobals());

describe("App", () => {
  it("shows a skeleton, then detection times, the trade-off, every design and the gaps", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify(fixture), { status: 200 })));
    render(<App />);
    expect(screen.getByRole("status")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByLabelText(/Median minutes until an incident is noticed/)).toBeInTheDocument());
    expect(screen.getByLabelText(/Pages per team-week against customer impact/)).toBeInTheDocument();
    expect(screen.getAllByText("This radar").length).toBeGreaterThan(1);
    expect(screen.getAllByText("svc-008", { selector: "code" }).length).toBeGreaterThan(0);
  });

  it("offers a retry when the report fails", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("x", { status: 500 })));
    render(<App />);
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("HTTP 500"));
  });

  it("explains a missing report", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("", { status: 404 })));
    render(<App />);
    await waitFor(() => expect(screen.getByText(/No held-out report yet/)).toBeInTheDocument());
  });
});
