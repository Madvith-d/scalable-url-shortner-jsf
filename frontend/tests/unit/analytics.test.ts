import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { compareClicks, fillBuckets } from "../../lib/analytics.ts";

describe("analytics range helpers", () => {
  it("compares the selected UTC days with the immediately preceding period", () => {
    const rows = [
      { date: "2026-10-10", clicks: 2 },
      { date: "2026-10-20", clicks: 3 },
      { date: "2026-10-28", clicks: 4 },
    ];
    assert.deepEqual(compareClicks(rows, 7, new Date("2026-10-29T12:00:00Z")), {
      current: 4,
      previous: 3,
    });
  });

  it("compares all time with an equally long preceding range", () => {
    const rows = [
      { date: "2026-10-27", clicks: 3 },
      { date: "2026-10-28", clicks: 4 },
    ];
    assert.deepEqual(compareClicks(rows, "all", new Date("2026-10-29T12:00:00Z")), {
      current: 7,
      previous: 0,
    });
  });

  it("fills missing time buckets in the requested order", () => {
    assert.deepEqual(fillBuckets([{ label: "03", clicks: 2 }], ["00", "03", "23"]), [
      { label: "00", clicks: 0 },
      { label: "03", clicks: 2 },
      { label: "23", clicks: 0 },
    ]);
  });
});
