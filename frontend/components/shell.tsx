"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, type ReactNode } from "react";
import { useAuth } from "./auth-provider";
import { ThemeControl } from "./theme-provider";

export function Shell({ children }: { children: ReactNode }) {
  const { session, ready, logout, notice } = useAuth();
  const pathname = usePathname();
  const router = useRouter();
  return (
    <>
      <a className="skip-link" href="#main">Skip to content</a>
      <header className="site-header">
        <div className="header-inner">
          <Link className="brand" href="/" aria-label="Shortify home">shortify<span className="brand-dot">.</span></Link>
          <nav aria-label="Main navigation">
            <Link href="/" aria-current={pathname === "/" ? "page" : undefined}>Create a link</Link>
            <Link href="/dashboard" aria-current={pathname.startsWith("/dashboard") ? "page" : undefined}>Dashboard</Link>
          </nav>
          <div className="account-nav">
            <ThemeControl />
            {ready && session ? (
              <>
                <span className="account-email" title={session.email}>{session.email}</span>
                <button className="button small secondary" onClick={() => { logout(); router.replace("/login"); }}>Sign out</button>
              </>
            ) : (
              <>
                <Link className="login-link" href="/login">Sign in</Link>
                <Link className="button small" href="/register">Get started</Link>
              </>
            )}
          </div>
        </div>
      </header>
      <main id="main" tabIndex={-1}>
        {notice && <div className="session-notice" role="status">{notice}</div>}
        {ready ? (
          <div key={session?.accessToken || "guest"}>{children}</div>
        ) : (
          <div className="container"><Loading message="Restoring your session…" /></div>
        )}
      </main>
      <footer className="site-footer"><span>Shortify</span><span>Links &amp; analytics</span></footer>
    </>
  );
}

export function Protected({ children }: { children: ReactNode }) {
  const { session, ready } = useAuth();
  const pathname = usePathname();
  const router = useRouter();
  useEffect(() => {
    if (ready && !session) router.replace(`/login?next=${encodeURIComponent(pathname)}`);
  }, [ready, session, pathname, router]);
  if (!ready || !session) return <div className="container"><Loading message="Checking your session…" /></div>;
  return children;
}

export function Loading({ message = "Loading your data…" }: { message?: string }) {
  return (
    <div className="loading-state" role="status">
      <span className="spinner" aria-hidden="true" />
      {message}
    </div>
  );
}

export function ErrorState({ message, retry }: { message: string; retry?: () => void }) {
  return (
    <div className="error-state" role="alert">
      <strong>We couldn’t complete that request.</strong>
      <p>{message}</p>
      {retry && <button className="button secondary small" onClick={retry}>Try again</button>}
    </div>
  );
}
