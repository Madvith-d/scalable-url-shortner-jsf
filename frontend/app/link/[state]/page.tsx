import type { Metadata } from "next";
import { notFound } from "next/navigation";

import { LinkScreen, screenTitle, type ScreenState } from "@/components/link-screen";

const STATES: readonly ScreenState[] = ["expired", "scheduled", "capped", "inactive", "unavailable"];

type Props = {
  params: Promise<{ state: string }>;
  searchParams: Promise<Record<string, string | string[] | undefined>>;
};

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const { state } = await params;
  if (!STATES.includes(state as ScreenState)) notFound();
  return { title: screenTitle(state as ScreenState) };
}

export default async function LinkStatePage({ params, searchParams }: Props) {
  const [{ state: rawState }, query] = await Promise.all([params, searchParams]);
  if (!STATES.includes(rawState as ScreenState)) notFound();
  const state = rawState as ScreenState;

  const single = (key: string) => {
    const value = query[key];
    return typeof value === "string" ? value : null;
  };

  const code = matchCode(single("code"));
  const expiresAt = state === "expired" ? matchInstant(single("expiresAt")) : null;
  const activatesAt = state === "scheduled" ? matchInstant(single("activatesAt")) : null;
  const continueUrl = state === "scheduled" ? matchAfterContinue(single("continue")) : null;

  return <LinkScreen state={state} code={code} expiresAt={expiresAt} activatesAt={activatesAt} continueUrl={continueUrl} />;
}

function matchCode(value: string | null) {
  return value && /^[A-Za-z0-9_-]{1,64}$/.test(value) ? value : null;
}

function matchInstant(value: string | null) {
  if (!value) return null;
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return null;
  const year = date.getUTCFullYear();
  if (year < 1 || year > 9999) return null;
  return date.toISOString();
}

function matchAfterContinue(value: string | null) {
  if (!value) return null;
  try {
    const url = new URL(value);
    return /^https?:$/.test(url.protocol) ? url.toString() : null;
  } catch {
    return null;
  }
}
