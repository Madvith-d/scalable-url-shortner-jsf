type DailyClick = { date: string; clicks: number };
type ClickBucket = { label: string; clicks: number };
type Period = 7 | 30 | 90 | 365 | "all";

export function fillBuckets(rows: ClickBucket[], labels: string[]): ClickBucket[] {
  const counts = new Map(rows.map(row => [row.label, row.clicks]));
  return labels.map(label => ({ label, clicks: counts.get(label) ?? 0 }));
}

export function compareClicks(rows: DailyClick[], period: Period, now = new Date()) {
  const end = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()));
  const endDate = end.toISOString().slice(0, 10);
  const firstDate = period === "all" ? rows[0]?.date ?? endDate : undefined;
  const days = period === "all"
    ? Math.max(1, Math.floor((end.getTime() - Date.parse(`${firstDate}T00:00:00Z`)) / 86_400_000) + 1)
    : period;
  const currentStart = period === "all" ? firstDate! : shiftDate(endDate, 1 - days);
  const previousEnd = shiftDate(currentStart, -1);
  const previousStart = shiftDate(previousEnd, 1 - days);
  const sum = (start: string, finish: string) => rows.reduce((total, row) =>
    row.date >= start && row.date <= finish ? total + row.clicks : total, 0);
  return {
    current: sum(currentStart, endDate),
    previous: sum(previousStart, previousEnd),
  };
}

function shiftDate(date: string, days: number) {
  const value = new Date(`${date}T00:00:00Z`);
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}
