import { Protected } from "@/components/shell";
import { Dashboard } from "@/components/dashboard";

export const metadata = { title: "Dashboard" };
export default function DashboardPage() {
  return <Protected><Dashboard /></Protected>;
}
