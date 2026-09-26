import Link from "next/link";

export default function NotFound() {
  return <div className="container not-found"><p className="eyebrow">404 / A MISSING CONNECTION</p><h1>This page isn’t here.</h1><p>The address may be incorrect. Head back to your workspace to find your links.</p><Link className="button" href="/dashboard">Back to dashboard →</Link></div>;
}
