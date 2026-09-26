import { notFound } from "next/navigation";
import { Protected } from "@/components/shell";
import { UrlDetail } from "@/components/url-detail";

export const metadata = { title: "Link details" };
export default async function UrlPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  if (!/^[1-9]\d*$/.test(id)) notFound();
  return <Protected><UrlDetail key={id} id={id} /></Protected>;
}
