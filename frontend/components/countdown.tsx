"use client";

import { useEffect, useState } from "react";

type CountdownProps = {
  activatesAt: string;
  continueUrl: string | null;
};

export function Countdown({ activatesAt, continueUrl }: CountdownProps) {
  const target = Date.parse(activatesAt);
  const [remaining, setRemaining] = useState<number | null>(null);

  const done = remaining !== null && remaining <= 0;

  useEffect(() => {
    if (!Number.isFinite(target)) return;
    const tick = () => {
      const ms = target - Date.now();
      if (ms <= 0) {
        setRemaining(0);
        if (continueUrl) window.location.assign(continueUrl);
        return;
      }
      setRemaining(ms);
    };
    tick();
    const id = setInterval(tick, 1000);
    return () => clearInterval(id);
  }, [target, continueUrl]);

  if (!Number.isFinite(target)) {
    return <p className="countdown-empty">This link becomes active at a configured time — reload the short link later.</p>;
  }

  if (done) {
    return (
      <p className="countdown-empty">
        {continueUrl ? "This link just went live — taking you there…" : "This link just became active. Reload the short link to continue."}
      </p>
    );
  }

  if (remaining === null) {
    return (
      <div className="countdown" aria-label="Activating soon">
        <div><strong>··</strong><span>hours</span></div>
        <div><strong>··</strong><span>minutes</span></div>
        <div><strong>··</strong><span>seconds</span></div>
      </div>
    );
  }

  const parts = split(remaining);
  const cells = (["days", "hours", "minutes", "seconds"] as const)
    .map(unit => ({ unit, value: parts[unit] }))
    .filter(cell => cell.unit !== "days" || parts.days > 0 || parts.hours > 0);

  return (
    <div className="countdown">
      {cells.map(({ unit, value }) => (
        <div key={unit}>
          <strong>{value < 10 ? `0${value}` : String(value)}</strong>
          <span>{unit}</span>
        </div>
      ))}
    </div>
  );
}

function split(ms: number): Record<"days" | "hours" | "minutes" | "seconds", number> {
  const seconds = Math.max(0, Math.ceil(ms / 1000));
  return {
    days: Math.floor(seconds / 86_400),
    hours: Math.floor(seconds % 86_400 / 3_600),
    minutes: Math.floor(seconds % 3_600 / 60),
    seconds: seconds % 60,
  };
}
