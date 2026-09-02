/**
 * Centralized API base URL resolver.
 * Priority:
 * 1. NEXT_PUBLIC_API_URL environment variable (configured per deployment environment)
 * 2. In browser production runtime: falls back dynamically to current origin
 * 3. In local dev runtime: falls back to default local backend (http://localhost:8080)
 */
export function getApiBaseUrl(): string {
  if (process.env.NEXT_PUBLIC_API_URL) {
    return process.env.NEXT_PUBLIC_API_URL.trim().replace(/\/+$/, "");
  }
  if (typeof window !== "undefined") {
    // If running on custom domain in production, use origin
    if (!window.location.hostname.includes("localhost") && !window.location.hostname.includes("127.0.0.1")) {
      return window.location.origin;
    }
  }
  return "http://localhost:8080";
}

/**
 * Proactively provisions a unique cryptographic Bearer token from /api/auth/token
 * and caches the user's isolated identity and token in localStorage.
 * Each browser receives its own isolated, non-forgeable identity.
 */
export async function getOrCreateAuthToken(): Promise<string> {
  if (typeof window === "undefined") return "";
  let token = localStorage.getItem("syed_auth_token");
  if (token) return token;

  const storedUserId = localStorage.getItem("syed_user_id");
  const storedUserSecret = localStorage.getItem("syed_user_secret");

  try {
    const apiBase = getApiBaseUrl();
    const payload = storedUserId && storedUserSecret
      ? { userId: storedUserId, userSecret: storedUserSecret }
      : {};

    const res = await fetch(`${apiBase}/api/auth/token`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });

    if (res.ok) {
      const data = await res.json();
      if (data.token) {
        localStorage.setItem("syed_auth_token", data.token);
        if (data.userId) localStorage.setItem("syed_user_id", data.userId);
        if (data.userSecret) localStorage.setItem("syed_user_secret", data.userSecret);
        return data.token;
      }
    }
  } catch (e) {
    console.warn("Could not provision auth token automatically:", e);
  }
  return "";
}

/**
 * Fetch wrapper that attaches Authorization: Bearer <token> automatically.
 */
export async function authenticatedFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  const token = await getOrCreateAuthToken();
  const headers = new Headers(init?.headers || {});
  if (token && !headers.has("Authorization")) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  return fetch(input, { ...init, headers });
}
