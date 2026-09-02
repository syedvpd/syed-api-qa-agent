"use client";

import { useState } from "react";
import { PlayCircle, ShieldCheck, AlertCircle, Info, Plus, Trash2, CheckCircle2, RefreshCw, Key, User, Lock, Layers } from "lucide-react";
import { getApiBaseUrl, authenticatedFetch } from "@/lib/api";

export interface IdentityProfileUI {
  id: string;
  name: string;
  strategy: "AUTO_DISCOVERED" | "BEARER_TOKEN" | "API_KEY" | "BASIC_AUTH" | "COOKIE" | "CUSTOM_HEADER" | "OAUTH2_CLIENT_CREDENTIALS" | "NO_AUTH";
  usernameOrEmail?: string;
  secretOrPassword?: string;
  token?: string;
  headerName?: string;
  cookieName?: string;
  tenantId?: string;
  scopes?: string;
}

export default function NewRunPage() {
  const [openapiUrl, setOpenapiUrl] = useState("");
  const [environmentType, setEnvironmentType] = useState<"DEVELOPMENT" | "STAGING" | "PRODUCTION">("STAGING");
  const [authMode, setAuthMode] = useState<"AUTO" | "NONE" | "MANUAL">("AUTO");
  
  // Multi-Identity profiles state
  const [profiles, setProfiles] = useState<IdentityProfileUI[]>([
    {
      id: "id_default",
      name: "Primary Identity",
      strategy: "AUTO_DISCOVERED",
      usernameOrEmail: "",
      secretOrPassword: "",
    },
  ]);

  const [statusMsg, setStatusMsg] = useState<string | null>(null);
  const [preflightResults, setPreflightResults] = useState<any | null>(null);
  const [isPreflighting, setIsPreflighting] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);

  const addProfile = () => {
    const idx = profiles.length + 1;
    setProfiles([
      ...profiles,
      {
        id: `id_${Date.now()}_${idx}`,
        name: `Identity Profile ${idx}`,
        strategy: "BEARER_TOKEN",
        usernameOrEmail: "",
        secretOrPassword: "",
      },
    ]);
  };

  const removeProfile = (id: string) => {
    if (profiles.length === 1) return;
    setProfiles(profiles.filter((p) => p.id !== id));
  };

  const updateProfile = (id: string, field: keyof IdentityProfileUI, value: any) => {
    setProfiles(
      profiles.map((p) => (p.id === id ? { ...p, [field]: value } : p))
    );
  };

  const handlePreflight = async () => {
    if (!openapiUrl) {
      setStatusMsg("Error: Please specify the OpenAPI/Swagger URL first.");
      return;
    }

    setIsPreflighting(true);
    setStatusMsg(null);
    setPreflightResults(null);

    const apiBase = getApiBaseUrl();
    try {
      const payloadProfiles = authMode === "NONE" ? [] : profiles.map(p => ({
        ...p,
        scopes: p.scopes ? p.scopes.split(",").map(s => s.trim()) : []
      }));

      const res = await authenticatedFetch(`${apiBase}/api/runs/preflight`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ openapiUrl, environmentType, profiles: payloadProfiles }),
      });

      const data = await res.json();
      if (res.ok) {
        setPreflightResults(data);
        setStatusMsg(`Preflight Complete: ${data.authenticatedCount}/${data.totalIdentities} identities authenticated successfully.`);
      } else {
        setStatusMsg(`Preflight Failed: ${data.error || "Could not complete authentication check"}`);
      }
    } catch (err: any) {
      setStatusMsg(`Preflight Network Error: ${err.message || "Failed to contact preflight endpoint"}`);
    } finally {
      setIsPreflighting(false);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setIsSubmitting(true);
    setStatusMsg(null);

    const apiBase = getApiBaseUrl();
    try {
      const payloadProfiles = authMode === "NONE" ? [] : profiles.map(p => ({
        ...p,
        scopes: p.scopes ? p.scopes.split(",").map(s => s.trim()) : []
      }));

      const firstToken = profiles[0]?.token || profiles[0]?.secretOrPassword;
      const res = await authenticatedFetch(`${apiBase}/api/runs`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          openapiUrl,
          environmentType,
          authType: authMode === "NONE" ? "NONE" : (profiles[0]?.strategy || "BEARER"),
          authToken: firstToken,
          authCredentials: firstToken,
          profiles: payloadProfiles,
        }),
      });

      const data = await res.json();
      if (!res.ok) {
        setStatusMsg(`Error: ${data.error || data.message || "Failed to register test run"}`);
      } else {
        const runId = data.id || data.runId;
        setStatusMsg(`Success: TestRun registered (ID: ${runId}). Full execution engine running.`);
        if (runId) {
          window.location.href = `/runs/${runId}/live`;
        }
      }
    } catch (err: any) {
      setStatusMsg(`Network error contacting backend at ${apiBase}: ${err.message || "Connection refused"}. Please verify backend is running.`);
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="max-w-4xl mx-auto space-y-8 pb-12">
      <div>
        <h1 className="text-2xl font-bold text-white tracking-tight flex items-center space-x-2">
          <span>Configure Autonomous API Test Run</span>
        </h1>
        <p className="text-sm text-slate-400 mt-1">
          Target any deployed API OpenAPI/Swagger specification. Configures dynamic multi-identity profiles, preflight checks, and isolated executions.
        </p>
      </div>

      <form onSubmit={handleSubmit} className="space-y-8">
        {/* OpenAPI URL Section */}
        <div className="bg-slate-900/60 p-6 rounded-xl border border-slate-800 space-y-4">
          <label className="block text-sm font-semibold text-slate-200">
            OpenAPI / Swagger Specification URL <span className="text-rose-400">*</span>
          </label>
          <input
            type="url"
            required
            value={openapiUrl}
            onChange={(e) => setOpenapiUrl(e.target.value)}
            placeholder="https://api.example.com/v3/api-docs or swagger.json"
            className="w-full px-4 py-3 rounded-lg bg-slate-950 border border-slate-800 text-white placeholder-slate-500 focus:outline-none focus:border-emerald-500 text-sm font-mono"
          />
          <p className="text-xs text-slate-500">
            Supports OpenAPI 3.0, 3.1, and Swagger 2.0 specs (JSON or YAML).
          </p>
        </div>

        {/* Environment Profile */}
        <div className="bg-slate-900/60 p-6 rounded-xl border border-slate-800 space-y-4">
          <label className="block text-sm font-semibold text-slate-200">Target Environment Profile</label>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <button
              type="button"
              onClick={() => setEnvironmentType("DEVELOPMENT")}
              className={`p-4 rounded-xl border text-left transition-all ${
                environmentType === "DEVELOPMENT"
                  ? "border-blue-500 bg-blue-500/10 text-white"
                  : "border-slate-800 bg-slate-950/50 text-slate-400 hover:border-slate-700"
              }`}
            >
              <div className="font-semibold text-xs text-blue-400">Development / Local</div>
              <div className="text-[11px] text-slate-400 mt-1">Permits localhost & private IP targets.</div>
            </button>

            <button
              type="button"
              onClick={() => setEnvironmentType("STAGING")}
              className={`p-4 rounded-xl border text-left transition-all ${
                environmentType === "STAGING"
                  ? "border-emerald-500 bg-emerald-500/10 text-white"
                  : "border-slate-800 bg-slate-950/50 text-slate-400 hover:border-slate-700"
              }`}
            >
              <div className="font-semibold text-xs text-emerald-400">Staging / QA</div>
              <div className="text-[11px] text-slate-400 mt-1">Full CRUD, automated cleanup & fuzzing.</div>
            </button>

            <button
              type="button"
              onClick={() => setEnvironmentType("PRODUCTION")}
              className={`p-4 rounded-xl border text-left transition-all ${
                environmentType === "PRODUCTION"
                  ? "border-amber-500 bg-amber-500/10 text-white"
                  : "border-slate-800 bg-slate-950/50 text-slate-400 hover:border-slate-700"
              }`}
            >
              <div className="font-semibold text-xs text-amber-400 flex items-center space-x-1">
                <span>Production Safety</span>
                <ShieldCheck className="h-3.5 w-3.5" />
              </div>
              <div className="text-[11px] text-slate-400 mt-1">HTTP DELETE disabled by default.</div>
            </button>
          </div>
        </div>

        {/* Authentication & Multi-Identity Profiles */}
        <div className="bg-slate-900/60 p-6 rounded-xl border border-slate-800 space-y-6">
          <div className="flex items-center justify-between">
            <div>
              <h3 className="text-sm font-semibold text-white flex items-center space-x-2">
                <Key className="h-4 w-4 text-emerald-400" />
                <span>Authentication & Identity Profiles</span>
              </h3>
              <p className="text-xs text-slate-400 mt-0.5">
                Configure N dynamic identities for multi-role isolation testing ($N=0, 1, 5, 29, 100+$).
              </p>
            </div>

            <div className="flex space-x-2 bg-slate-950 p-1 rounded-lg border border-slate-800 text-xs font-medium">
              <button
                type="button"
                onClick={() => setAuthMode("AUTO")}
                className={`px-3 py-1.5 rounded-md transition-all ${authMode === "AUTO" ? "bg-emerald-600 text-white font-semibold" : "text-slate-400 hover:text-white"}`}
              >
                Auto Discover
              </button>
              <button
                type="button"
                onClick={() => setAuthMode("NONE")}
                className={`px-3 py-1.5 rounded-md transition-all ${authMode === "NONE" ? "bg-emerald-600 text-white font-semibold" : "text-slate-400 hover:text-white"}`}
              >
                No Auth (Public)
              </button>
              <button
                type="button"
                onClick={() => setAuthMode("MANUAL")}
                className={`px-3 py-1.5 rounded-md transition-all ${authMode === "MANUAL" ? "bg-emerald-600 text-white font-semibold" : "text-slate-400 hover:text-white"}`}
              >
                Identity Profiles ({profiles.length})
              </button>
            </div>
          </div>

          {authMode !== "NONE" && (
            <div className="space-y-4">
              {profiles.map((profile, idx) => (
                <div key={profile.id} className="p-4 rounded-xl bg-slate-950 border border-slate-800 space-y-4 relative">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center space-x-2">
                      <User className="h-4 w-4 text-slate-400" />
                      <input
                        type="text"
                        value={profile.name}
                        onChange={(e) => updateProfile(profile.id, "name", e.target.value)}
                        className="bg-transparent border-b border-slate-700 text-sm font-semibold text-white focus:outline-none focus:border-emerald-500 px-1 py-0.5"
                        placeholder="Profile Name (e.g. QA Admin)"
                      />
                    </div>
                    {profiles.length > 1 && (
                      <button
                        type="button"
                        onClick={() => removeProfile(profile.id)}
                        className="text-slate-500 hover:text-rose-400 p-1"
                      >
                        <Trash2 className="h-4 w-4" />
                      </button>
                    )}
                  </div>

                  <div className="grid grid-cols-1 md:grid-cols-2 gap-4 text-xs">
                    <div>
                      <label className="block text-slate-400 mb-1 font-medium">Authentication Strategy</label>
                      <select
                        value={profile.strategy}
                        onChange={(e) => updateProfile(profile.id, "strategy", e.target.value as any)}
                        className="w-full px-3 py-2 rounded-lg bg-slate-900 border border-slate-800 text-white focus:outline-none focus:border-emerald-500"
                      >
                        <option value="AUTO_DISCOVERED">Auto Discover from OpenAPI Contract</option>
                        <option value="BEARER_TOKEN">Bearer Token (JWT)</option>
                        <option value="API_KEY">API Key</option>
                        <option value="BASIC_AUTH">Basic Authentication</option>
                        <option value="COOKIE">Cookie / Session</option>
                        <option value="CUSTOM_HEADER">Custom Header</option>
                        <option value="OAUTH2_CLIENT_CREDENTIALS">OAuth2 Client Credentials</option>
                      </select>
                    </div>

                    {(profile.strategy === "BASIC_AUTH" || profile.strategy === "AUTO_DISCOVERED" || profile.strategy === "OAUTH2_CLIENT_CREDENTIALS") && (
                      <div>
                        <label className="block text-slate-400 mb-1 font-medium">
                          {profile.strategy === "OAUTH2_CLIENT_CREDENTIALS" ? "Client ID" : "Username / Email"}
                        </label>
                        <input
                          type="text"
                          value={profile.usernameOrEmail || ""}
                          onChange={(e) => updateProfile(profile.id, "usernameOrEmail", e.target.value)}
                          placeholder="user@example.com or client_id"
                          className="w-full px-3 py-2 rounded-lg bg-slate-900 border border-slate-800 text-white placeholder-slate-600 focus:outline-none focus:border-emerald-500 font-mono"
                        />
                      </div>
                    )}

                    {(profile.strategy === "BEARER_TOKEN" || profile.strategy === "BASIC_AUTH" || profile.strategy === "AUTO_DISCOVERED" || profile.strategy === "OAUTH2_CLIENT_CREDENTIALS" || profile.strategy === "API_KEY" || profile.strategy === "COOKIE") && (
                      <div>
                        <label className="block text-slate-400 mb-1 font-medium">
                          {profile.strategy === "OAUTH2_CLIENT_CREDENTIALS" ? "Client Secret" : (profile.strategy === "BEARER_TOKEN" ? "Bearer Token / Secret" : "Password / Secret")}
                        </label>
                        <input
                          type="password"
                          value={profile.secretOrPassword || profile.token || ""}
                          onChange={(e) => {
                            updateProfile(profile.id, "secretOrPassword", e.target.value);
                            updateProfile(profile.id, "token", e.target.value);
                          }}
                          placeholder="••••••••"
                          className="w-full px-3 py-2 rounded-lg bg-slate-900 border border-slate-800 text-white placeholder-slate-600 focus:outline-none focus:border-emerald-500 font-mono"
                        />
                      </div>
                    )}

                    {profile.strategy === "API_KEY" && (
                      <div>
                        <label className="block text-slate-400 mb-1 font-medium">Header Name</label>
                        <input
                          type="text"
                          value={profile.headerName || "X-API-Key"}
                          onChange={(e) => updateProfile(profile.id, "headerName", e.target.value)}
                          placeholder="X-API-Key"
                          className="w-full px-3 py-2 rounded-lg bg-slate-900 border border-slate-800 text-white placeholder-slate-600 focus:outline-none focus:border-emerald-500 font-mono"
                        />
                      </div>
                    )}

                    {profile.strategy === "COOKIE" && (
                      <div>
                        <label className="block text-slate-400 mb-1 font-medium">Cookie Name</label>
                        <input
                          type="text"
                          value={profile.cookieName || "session_id"}
                          onChange={(e) => updateProfile(profile.id, "cookieName", e.target.value)}
                          placeholder="session_id"
                          className="w-full px-3 py-2 rounded-lg bg-slate-900 border border-slate-800 text-white placeholder-slate-600 focus:outline-none focus:border-emerald-500 font-mono"
                        />
                      </div>
                    )}

                    <div>
                      <label className="block text-slate-400 mb-1 font-medium">Tenant Context (Optional)</label>
                      <input
                        type="text"
                        value={profile.tenantId || ""}
                        onChange={(e) => updateProfile(profile.id, "tenantId", e.target.value)}
                        placeholder="tenant_qa_01"
                        className="w-full px-3 py-2 rounded-lg bg-slate-900 border border-slate-800 text-white placeholder-slate-600 focus:outline-none focus:border-emerald-500 font-mono"
                      />
                    </div>
                  </div>
                </div>
              ))}

              <div className="flex items-center justify-between pt-2">
                <button
                  type="button"
                  onClick={addProfile}
                  className="flex items-center space-x-1 text-xs text-emerald-400 hover:text-emerald-300 font-medium py-1 px-3 rounded-lg border border-emerald-500/30 bg-emerald-500/10"
                >
                  <Plus className="h-3.5 w-3.5" />
                  <span>Add Identity Profile</span>
                </button>

                <button
                  type="button"
                  onClick={handlePreflight}
                  disabled={isPreflighting}
                  className="flex items-center space-x-1.5 text-xs text-blue-400 hover:text-blue-300 font-medium py-1.5 px-4 rounded-lg border border-blue-500/30 bg-blue-500/10 disabled:opacity-50"
                >
                  <RefreshCw className={`h-3.5 w-3.5 ${isPreflighting ? "animate-spin" : ""}`} />
                  <span>{isPreflighting ? "Verifying..." : "Test Preflight Connection"}</span>
                </button>
              </div>

              {/* Preflight Verification Badges */}
              {preflightResults && (
                <div className="p-4 rounded-xl bg-slate-950 border border-slate-800 space-y-3">
                  <div className="flex items-center justify-between text-xs font-semibold">
                    <span className="text-white flex items-center space-x-1.5">
                      <CheckCircle2 className="h-4 w-4 text-emerald-400" />
                      <span>Preflight Verification Report</span>
                    </span>
                    <span className="text-slate-400">
                      {preflightResults.authenticatedCount} / {preflightResults.totalIdentities} Authenticated
                    </span>
                  </div>

                  <div className="grid grid-cols-1 md:grid-cols-2 gap-2 text-xs">
                    {Object.entries(preflightResults.identityStates || {}).map(([id, state]: [string, any]) => (
                      <div key={id} className="flex items-center justify-between p-2 rounded-lg bg-slate-900 border border-slate-800 font-mono">
                        <span className="text-slate-300">{id}</span>
                        <span className={`px-2 py-0.5 rounded text-[10px] font-bold ${
                          state === "AUTHENTICATED" ? "bg-emerald-500/20 text-emerald-400" : "bg-rose-500/20 text-rose-400"
                        }`}>
                          {state}
                        </span>
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>
          )}
        </div>

        {statusMsg && (
          <div className="p-4 rounded-xl bg-slate-900 border border-slate-800 text-xs text-slate-300 flex items-start space-x-2">
            <Info className="h-4 w-4 text-emerald-400 shrink-0 mt-0.5" />
            <span>{statusMsg}</span>
          </div>
        )}

        <button
          type="submit"
          disabled={isSubmitting}
          className="w-full flex items-center justify-center space-x-2 py-3.5 rounded-xl bg-emerald-600 hover:bg-emerald-500 disabled:opacity-50 text-white font-semibold transition-all shadow-lg shadow-emerald-950/50"
        >
          <PlayCircle className="h-5 w-5" />
          <span>{isSubmitting ? "Initiating Autonomous Run..." : "Start Autonomous Test Run"}</span>
        </button>
      </form>
    </div>
  );
}
