import { notFound } from "next/navigation";
import { Protected } from "@/components/shell";
import { UrlAnalytics } from "@/components/url-analytics";

export const metadata = { title: "Link analytics" };
export default async function AnalyticsPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  if (!/^[1-9]\d*$/.test(id)) notFound();
  return <Protected><UrlAnalytics key={id} id={id} /></Protected>;
}
