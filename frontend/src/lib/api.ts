/**
 * Centralized API base URL resolver.
 * Priority:
 * 1. NEXT_PUBLIC_API_URL environment variable
 * 2. In local dev runtime: http://localhost:8080
 * 3. In browser production runtime: https://syed-api-testing-agent.onrender.com
 */
export function getApiBaseUrl(): string {
  if (process.env.NEXT_PUBLIC_API_URL && process.env.NEXT_PUBLIC_API_URL.trim().length > 0) {
    return process.env.NEXT_PUBLIC_API_URL.trim().replace(/\/+$/, "");
  }
  if (typeof window !== "undefined") {
    if (window.location.hostname.includes("localhost") || window.location.hostname.includes("127.0.0.1")) {
      return "http://localhost:8080";
    }
  }
  return "https://syed-api-testing-agent.onrender.com";
}

/**
 * Checks if a syed_sec_v1 HMAC token is expired or will expire in under 60 seconds.
 */
export function isTokenExpired(token: string | null): boolean {
  if (!token || !token.startsWith("syed_sec_v1.")) return true;
  try {
    const raw = token.substring("syed_sec_v1.".length);
    const dotIdx = raw.indexOf(".");
    if (dotIdx === -1) return true;
    const b64 = raw.substring(0, dotIdx);
    const normalized = b64.replace(/-/g, "+").replace(/_/g, "/");
    const decoded = atob(normalized);
    const colon = decoded.lastIndexOf(":");
    if (colon === -1) return true;
    const expiresAt = parseInt(decoded.substring(colon + 1), 10);
    return isNaN(expiresAt) || (Date.now() / 1000) > (expiresAt - 60);
  } catch {
    return true;
  }
}

/**
 * Proactively provisions a unique cryptographic Bearer token from /api/auth/token
 * and caches the user's isolated identity and token in localStorage.
 * Automatically refreshes if expired.
 */
export async function getOrCreateAuthToken(forceRefresh = false): Promise<string> {
  if (typeof window === "undefined") return "";

  let token = localStorage.getItem("syed_auth_token");
  if (!forceRefresh && token && !isTokenExpired(token)) {
    return token;
  }

  // Token is expired, invalid, or force refresh requested
  localStorage.removeItem("syed_auth_token");

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
 * Fetch wrapper that attaches Authorization: Bearer <token> automatically
 * and automatically retries with a fresh token if a 401 is encountered.
 */
export async function authenticatedFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  let token = await getOrCreateAuthToken();
  const headers = new Headers(init?.headers || {});
  if (token && !headers.has("Authorization")) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  let response = await fetch(input, { ...init, headers });

  // If unauthorized / token expired, re-provision token and retry once
  if (response.status === 401) {
    token = await getOrCreateAuthToken(true);
    if (token) {
      const retryHeaders = new Headers(init?.headers || {});
      retryHeaders.set("Authorization", `Bearer ${token}`);
      response = await fetch(input, { ...init, headers: retryHeaders });
    }
  }

  return response;
}
