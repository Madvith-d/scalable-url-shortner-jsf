import { Suspense } from "react";
import { AuthForm } from "@/components/auth-form";
import { Loading } from "@/components/shell";

export const metadata = { title: "Sign in" };
export default function LoginPage() {
  return <Suspense fallback={<Loading />}><AuthForm mode="login" /></Suspense>;
}
