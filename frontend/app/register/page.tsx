import { Suspense } from "react";
import { AuthForm } from "@/components/auth-form";
import { Loading } from "@/components/shell";

export const metadata = { title: "Create account" };
export default function RegisterPage() {
  return <Suspense fallback={<Loading />}><AuthForm mode="register" /></Suspense>;
}
