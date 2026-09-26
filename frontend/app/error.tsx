"use client";

import { ErrorState } from "@/components/shell";

export default function ErrorPage({ reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return <div className="container workspace"><h1>Something went wrong.</h1><ErrorState message="This page could not be displayed. Please try again." retry={reset} /></div>;
}
