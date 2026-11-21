import { describe, expect, it } from "vitest";

import { minutes, toneOf } from "./report";

describe("report helpers", () => {
  it("says minutes the way on-call does", () => {
    expect(minutes(48)).toBe("48 min");
    expect(minutes(118)).toBe("2.0 h");
    expect(minutes(810)).toBe("14 h");
    expect(minutes(4320)).toBe("3.0 days");
  });
  it("gives colour only to the radar and to today", () => {
    expect(toneOf("radar")).toBe("radar");
    expect(toneOf("status_quo")).toBe("today");
    expect(toneOf("no_gate")).toBe("other");
  });
});
