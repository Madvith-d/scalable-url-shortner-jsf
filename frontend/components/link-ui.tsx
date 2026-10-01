"use client";

import { useEffect, useState } from "react";
import { linkStatus } from "@/lib/helpers";
import type { ShortUrl } from "@/lib/types";

export function CopyButton({ value }: { value: string }) {
  const [state, setState] = useState<"idle" | "copied" | "failed">("idle");
  useEffect(() => {
    if (state === "idle") return;
    const timer = setTimeout(() => setState("idle"), 4000);
    return () => clearTimeout(timer);
  }, [state]);
  return (
    <div className="copy-control" data-state={state}>
      <button
        className="button secondary small"
        aria-label={`Copy short URL ${value}`}
        onClick={async () => {
          try { await navigator.clipboard.writeText(value); setState("copied"); }
          catch { setState("failed"); }
        }}
      >
        {state === "copied" ? "Copied" : "Copy URL"}
      </button>
      <span className={state === "failed" ? "copy-error" : "sr-only"} role="status">
        {state === "copied" ? "Short URL copied to clipboard." : state === "failed" ? "Copy unavailable. Select and copy the URL manually." : ""}
      </span>
    </div>
  );
}

export function StatusBadge({ url }: { url: Pick<ShortUrl, "active" | "expiresAt" | "activatesAt" | "maxClicks" | "clickCount"> }) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  const status = linkStatus(url, now);
  return <span className={`badge ${status.toLowerCase()}`}>{status}</span>;
}

export function ExternalLink({ href, children, className }: { href: string; children: React.ReactNode; className?: string }) {
  const safe = /^https?:\/\//i.test(href);
  return safe ? (
    <a className={className} href={href} target="_blank" rel="noopener noreferrer">
      {children}<span className="sr-only"> (opens in a new tab)</span>
    </a>
  ) : (
    <span className={className}>{children}</span>
  );
}
