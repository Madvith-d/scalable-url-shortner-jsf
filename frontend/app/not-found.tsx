import Link from "next/link";

export default function NotFound() {
  return (
    <div className="container not-found">
      <h1>Page not found</h1>
      <p>Check the address or return to your dashboard to find your links.</p>
      <Link className="button" href="/dashboard">Back to dashboard</Link>
    </div>
  );
}
