"use client";

import { useEffect, useState, useCallback } from "react";
import { useAuth } from "@/components/auth-provider";
import { errorMessage, isAbort } from "./api";

export function useResource<T>(path: string) {
  const { request } = useAuth();
  const [state, setState] = useState<{ path: string; data: T | null; loading: boolean; error: string }>({ path, data: null, loading: true, error: "" });
  const [revision, setRevision] = useState(0);
  const reload = useCallback(() => setRevision(value => value + 1), []);
  useEffect(() => {
    const controller = new AbortController();
    setState({ path, data: null, loading: true, error: "" });
    request<T>(path, { signal: controller.signal }).then(data => {
      if (!controller.signal.aborted) setState({ path, data, loading: false, error: "" });
    }).catch(error => {
      if (!controller.signal.aborted && !isAbort(error)) setState({ path, data: null, loading: false, error: errorMessage(error) });
    });
    return () => controller.abort();
  }, [path, request, revision]);
  return { ...(state.path === path ? state : { data: null, loading: true, error: "" }), reload };
}
