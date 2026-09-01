/**
 * Centralized API base URL resolver.
 * Priority:
 * 1. NEXT_PUBLIC_API_URL environment variable (configured per deployment environment)
 * 2. In browser production runtime: falls back dynamically to current origin
 * 3. In local dev runtime: falls back to default local backend (http://localhost:8080)
 */
export function getApiBaseUrl(): string {
  if (process.env.NEXT_PUBLIC_API_URL) {
    return process.env.NEXT_PUBLIC_API_URL.replace(/\/$/, "");
  }
  if (typeof window !== "undefined") {
    // If running on custom domain in production, use origin
    if (!window.location.hostname.includes("localhost") && !window.location.hostname.includes("127.0.0.1")) {
      return window.location.origin;
    }
  }
  return "http://localhost:8080";
}
