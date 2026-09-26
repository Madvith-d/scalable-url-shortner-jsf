import Link from "next/link";
import { CreateForm } from "@/components/create-form";

export default function HomePage() {
  return (
    <div className="container home-page">
      <header className="home-heading">
        <h1 id="create-title">Shorten a link.</h1>
        <p>A shorter URL, ready to share. Add an alias or expiration if you need one.</p>
      </header>
      <section className="panel create-panel" aria-labelledby="create-title">
        <CreateForm />
      </section>
      <div className="home-bottom">
        <Link className="text-link" href="/dashboard">Open your dashboard</Link>
      </div>
    </div>
  );
}
