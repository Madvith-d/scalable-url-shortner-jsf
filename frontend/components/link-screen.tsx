import Link from "next/link";
import type { ReactNode } from "react";

import { Countdown } from "@/components/countdown";
import { formatDate } from "@/lib/helpers";

export type ScreenState = "expired" | "scheduled" | "capped" | "inactive" | "unavailable";

export type LinkScreenProps = {
  state: ScreenState;
  code: string | null;
  expiresAt: string | null;
  activatesAt: string | null;
  continueUrl: string | null;
};

const COPY: Record<ScreenState, { badge: string | null; badgeClass: string | null; title: string; body: ReactNode }> = {
  expired: {
    badge: "Expired",
    badgeClass: "expired",
    title: "This link has expired.",
    body: "The short link you followed reached its expiry time and no longer redirects. Ask whoever shared it for an updated link.",
  },
  scheduled: {
    badge: "Scheduled",
    badgeClass: "scheduled",
    title: "This link isn't active yet.",
    body: "You're early. The link opens automatically as soon as the countdown completes.",
  },
  capped: {
    badge: "Click limit reached",
    badgeClass: "capped",
    title: "This link hit its click limit.",
    body: "The person who created this link limited how many times it can be opened, and that limit has been reached.",
  },
  inactive: {
    badge: "Inactive",
    badgeClass: "inactive",
    title: "This link has been turned off.",
    body: "The owner of this short link disabled it, so it no longer redirects.",
  },
  unavailable: {
    badge: null,
    badgeClass: null,
    title: "This link doesn't exist.",
    body: "The short link you followed isn't in use — it may have been deleted or mistyped. Check the address and try again.",
  },
};

export function screenTitle(state: ScreenState) {
  const titles: Record<ScreenState, string> = {
    expired: "Link expired",
    scheduled: "Not active yet",
    capped: "Click limit reached",
    inactive: "Link unavailable",
    unavailable: "Link not found",
  };
  return titles[state];
}

export function LinkScreen({ state, code, expiresAt, activatesAt, continueUrl }: LinkScreenProps) {
  const copy = COPY[state];
  return (
    <div className="container link-screen">
      {copy.badge && <span className={`badge ${copy.badgeClass}`}>{copy.badge}</span>}
      <h1>{copy.title}</h1>
      <p>{copy.body}</p>
      {state === "expired" && <p className="link-screen-date">Expired {expiresAt ? formatDate(expiresAt) : "at a previously set time"}.</p>}
      {state === "scheduled" &&
        (activatesAt ? <Countdown activatesAt={activatesAt} continueUrl={continueUrl} /> : (
          <p className="countdown-empty">This link becomes active at a configured time — reload the short link later.</p>
        ))}
      <Link className="button" href="/">Shorten a link</Link>
      {code && <p className="link-screen-code">Short code: <code>{code}</code></p>}
    </div>
  );
}

