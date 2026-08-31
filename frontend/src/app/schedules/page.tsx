"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import {
  Calendar,
  Clock,
  Play,
  Trash2,
  CheckCircle2,
  XCircle,
  Plus,
  Loader2,
  AlertCircle,
  RefreshCw,
  ArrowRight
} from "lucide-react";

interface ScheduleItem {
  id: string;
  name: string;
  openapiUrl: string;
  environment: string;
  scheduleType: "DAILY" | "WEEKLY" | "CUSTOM_CRON";
  cronExpression?: string;
  enabled: boolean;
  lastRunAt?: string;
  nextRunAt?: string;
  createdAt: string;
}

export default function SchedulesPage() {
  const [schedules, setSchedules] = useState<ScheduleItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  // Form State
  const [showModal, setShowModal] = useState(false);
  const [formName, setFormName] = useState("");
  const [formUrl, setFormUrl] = useState("");
  const [formEnv, setFormEnv] = useState("STAGING");
  const [formType, setFormType] = useState<"DAILY" | "WEEKLY" | "CUSTOM_CRON">("DAILY");
  const [formCron, setFormCron] = useState("");

  const getApiBase = () => {
    if (process.env.NEXT_PUBLIC_API_URL) {
      return process.env.NEXT_PUBLIC_API_URL.replace(/\/$/, "");
    }
    return "http://localhost:8080";
  };

  const loadSchedules = async () => {
    setLoading(true);
    setErrorMessage(null);
    try {
      const res = await fetch(`${getApiBase()}/api/schedules`);
      if (!res.ok) throw new Error(`Failed to load schedules (HTTP ${res.status})`);
      const data = await res.json();
      setSchedules(data || []);
    } catch (err: any) {
      setErrorMessage(err.message || "Unable to reach backend server.");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadSchedules();
  }, []);

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!formName || !formUrl) return;

    setActionLoading("create");
    setErrorMessage(null);
    setSuccessMessage(null);

    try {
      const res = await fetch(`${getApiBase()}/api/schedules`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          name: formName,
          openapiUrl: formUrl,
          environment: formEnv,
          scheduleType: formType,
          cronExpression: formCron
        })
      });

      if (!res.ok) {
        const errData = await res.json().catch(() => ({}));
        throw new Error(errData.error || `Failed to create schedule (HTTP ${res.status})`);
      }

      setShowModal(false);
      setFormName("");
      setFormUrl("");
      setSuccessMessage("Automated schedule created successfully.");
      loadSchedules();
    } catch (err: any) {
      setErrorMessage(err.message || "Failed to create schedule.");
    } finally {
      setActionLoading(null);
    }
  };

  const handleToggle = async (id: string) => {
    setActionLoading(`toggle-${id}`);
    try {
      const res = await fetch(`${getApiBase()}/api/schedules/${id}/toggle`, { method: "PATCH" });
      if (!res.ok) throw new Error("Failed to toggle schedule");
      loadSchedules();
    } catch (err: any) {
      setErrorMessage(err.message || "Toggle action failed.");
    } finally {
      setActionLoading(null);
    }
  };

  const handleDelete = async (id: string) => {
    if (!confirm("Are you sure you want to delete this schedule?")) return;
    setActionLoading(`delete-${id}`);
    try {
      const res = await fetch(`${getApiBase()}/api/schedules/${id}`, { method: "DELETE" });
      if (!res.ok) throw new Error("Failed to delete schedule");
      loadSchedules();
    } catch (err: any) {
      setErrorMessage(err.message || "Delete action failed.");
    } finally {
      setActionLoading(null);
    }
  };

  const handleRunNow = async (id: string) => {
    setActionLoading(`run-${id}`);
    setSuccessMessage(null);
    try {
      const res = await fetch(`${getApiBase()}/api/schedules/${id}/run-now`, { method: "POST" });
      if (!res.ok) throw new Error("Failed to dispatch scheduled run");
      const data = await res.json();
      setSuccessMessage(`Test Run dispatched immediately: ${data.runId.substring(0, 8)}...`);
      loadSchedules();
    } catch (err: any) {
      setErrorMessage(err.message || "Run dispatch failed.");
    } finally {
      setActionLoading(null);
    }
  };

  return (
    <div className="space-y-6">
      {/* Top Bar */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-slate-100 flex items-center space-x-2">
            <Calendar className="h-5 w-5 text-indigo-400" />
            <span>Autonomous Test Schedules</span>
          </h1>
          <p className="text-xs text-slate-400 mt-1">
            Configure automated recurring runs against live deployed APIs with concurrency control and safety guards.
          </p>
        </div>

        <button
          onClick={() => setShowModal(true)}
          className="flex items-center space-x-1.5 px-3.5 py-1.5 rounded-lg bg-indigo-600 hover:bg-indigo-500 text-xs font-semibold text-white shadow-sm transition-colors"
        >
          <Plus className="h-4 w-4" />
          <span>New Schedule</span>
        </button>
      </div>

      {errorMessage && (
        <div className="p-3.5 rounded-lg bg-rose-950/40 border border-rose-800/60 text-rose-300 text-xs flex items-center space-x-2">
          <AlertCircle className="h-4 w-4 shrink-0 text-rose-400" />
          <span>{errorMessage}</span>
        </div>
      )}

      {successMessage && (
        <div className="p-3.5 rounded-lg bg-emerald-950/40 border border-emerald-800/60 text-emerald-300 text-xs flex items-center space-x-2">
          <CheckCircle2 className="h-4 w-4 shrink-0 text-emerald-400" />
          <span>{successMessage}</span>
        </div>
      )}

      {/* Schedules Table */}
      {loading ? (
        <div className="p-16 text-center space-y-3 bg-slate-900/20 rounded-xl border border-slate-800">
          <Loader2 className="h-6 w-6 text-indigo-400 animate-spin mx-auto" />
          <div className="text-slate-400 text-xs font-mono">Loading active test schedules...</div>
        </div>
      ) : schedules.length === 0 ? (
        <div className="p-16 text-center space-y-3 bg-slate-900/40 rounded-xl border border-slate-800">
          <Clock className="h-10 w-10 text-slate-600 mx-auto" />
          <p className="text-slate-300 font-medium text-sm">No Schedules Configured</p>
          <p className="text-slate-500 text-xs max-w-sm mx-auto">
            Schedule recurring daily or weekly regression test sweeps against your deployed environments.
          </p>
        </div>
      ) : (
        <div className="rounded-xl border border-slate-800 bg-slate-950 overflow-hidden shadow-xl">
          <table className="w-full text-left text-xs font-mono">
            <thead className="bg-slate-900/60 text-slate-400 border-b border-slate-800 text-[11px]">
              <tr>
                <th className="py-3 px-4">Schedule Name</th>
                <th className="py-3 px-4">Target Specification</th>
                <th className="py-3 px-4">Environment</th>
                <th className="py-3 px-4">Cadence</th>
                <th className="py-3 px-4">Status</th>
                <th className="py-3 px-4">Next Due</th>
                <th className="py-3 px-4 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-800/60">
              {schedules.map((s) => (
                <tr key={s.id} className="hover:bg-slate-900/30 transition-colors">
                  <td className="py-3 px-4 font-sans font-semibold text-slate-200">{s.name}</td>
                  <td className="py-3 px-4 text-slate-400 max-w-[200px] truncate">{s.openapiUrl}</td>
                  <td className="py-3 px-4">
                    <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-slate-800 text-slate-300">
                      {s.environment}
                    </span>
                  </td>
                  <td className="py-3 px-4 text-slate-300">{s.scheduleType}</td>
                  <td className="py-3 px-4">
                    {s.enabled ? (
                      <span className="inline-flex items-center space-x-1 text-emerald-400 text-[11px]">
                        <CheckCircle2 className="h-3.5 w-3.5" />
                        <span>Enabled</span>
                      </span>
                    ) : (
                      <span className="inline-flex items-center space-x-1 text-slate-500 text-[11px]">
                        <XCircle className="h-3.5 w-3.5" />
                        <span>Disabled</span>
                      </span>
                    )}
                  </td>
                  <td className="py-3 px-4 text-slate-400">
                    {s.nextRunAt ? new Date(s.nextRunAt).toLocaleString() : "Pending"}
                  </td>
                  <td className="py-3 px-4 text-right space-x-2">
                    <button
                      onClick={() => handleRunNow(s.id)}
                      disabled={actionLoading === `run-${s.id}`}
                      className="px-2 py-1 bg-indigo-600/80 hover:bg-indigo-500 text-white rounded text-[11px] font-semibold transition-colors"
                      title="Run immediately"
                    >
                      {actionLoading === `run-${s.id}` ? (
                        <Loader2 className="h-3 w-3 animate-spin inline" />
                      ) : (
                        <Play className="h-3 w-3 inline" />
                      )}
                      <span className="ml-1">Run</span>
                    </button>

                    <button
                      onClick={() => handleToggle(s.id)}
                      disabled={actionLoading === `toggle-${s.id}`}
                      className="px-2 py-1 bg-slate-800 hover:bg-slate-700 text-slate-300 rounded text-[11px] transition-colors"
                    >
                      {s.enabled ? "Disable" : "Enable"}
                    </button>

                    <button
                      onClick={() => handleDelete(s.id)}
                      disabled={actionLoading === `delete-${s.id}`}
                      className="p-1 hover:text-rose-400 text-slate-500 transition-colors"
                      title="Delete Schedule"
                    >
                      <Trash2 className="h-3.5 w-3.5 inline" />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Creation Modal */}
      {showModal && (
        <div className="fixed inset-0 z-50 bg-black/70 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="bg-slate-950 border border-slate-800 rounded-xl max-w-md w-full p-6 space-y-4 shadow-2xl">
            <h2 className="text-base font-bold text-slate-100">Create Automated Test Schedule</h2>
            <form onSubmit={handleCreate} className="space-y-3 text-xs">
              <div>
                <label className="block text-slate-400 mb-1">Schedule Name</label>
                <input
                  type="text"
                  required
                  placeholder="e.g. Daily Staging Health Check"
                  value={formName}
                  onChange={(e) => setFormName(e.target.value)}
                  className="w-full bg-slate-900 border border-slate-800 rounded-lg px-3 py-2 text-slate-200 focus:outline-none focus:ring-1 focus:ring-indigo-500"
                />
              </div>

              <div>
                <label className="block text-slate-400 mb-1">OpenAPI / Swagger URL</label>
                <input
                  type="url"
                  required
                  placeholder="https://api.example.com/v3/api-docs"
                  value={formUrl}
                  onChange={(e) => setFormUrl(e.target.value)}
                  className="w-full bg-slate-900 border border-slate-800 rounded-lg px-3 py-2 text-slate-200 focus:outline-none focus:ring-1 focus:ring-indigo-500 font-mono text-[11px]"
                />
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-slate-400 mb-1">Environment</label>
                  <select
                    value={formEnv}
                    onChange={(e) => setFormEnv(e.target.value)}
                    className="w-full bg-slate-900 border border-slate-800 rounded-lg px-2.5 py-2 text-slate-200 focus:outline-none focus:ring-1 focus:ring-indigo-500"
                  >
                    <option value="STAGING">STAGING</option>
                    <option value="DEVELOPMENT">DEVELOPMENT</option>
                    <option value="PRODUCTION">PRODUCTION</option>
                  </select>
                </div>

                <div>
                  <label className="block text-slate-400 mb-1">Frequency</label>
                  <select
                    value={formType}
                    onChange={(e) => setFormType(e.target.value as any)}
                    className="w-full bg-slate-900 border border-slate-800 rounded-lg px-2.5 py-2 text-slate-200 focus:outline-none focus:ring-1 focus:ring-indigo-500"
                  >
                    <option value="DAILY">Daily (24h)</option>
                    <option value="WEEKLY">Weekly (7d)</option>
                    <option value="CUSTOM_CRON">Custom Cron</option>
                  </select>
                </div>
              </div>

              {formType === "CUSTOM_CRON" && (
                <div>
                  <label className="block text-slate-400 mb-1">Cron Expression</label>
                  <input
                    type="text"
                    placeholder="0 0 * * *"
                    value={formCron}
                    onChange={(e) => setFormCron(e.target.value)}
                    className="w-full bg-slate-900 border border-slate-800 rounded-lg px-3 py-2 text-slate-200 focus:outline-none focus:ring-1 focus:ring-indigo-500 font-mono"
                  />
                </div>
              )}

              <div className="pt-3 flex items-center justify-end space-x-2">
                <button
                  type="button"
                  onClick={() => setShowModal(false)}
                  className="px-3 py-1.5 rounded-lg text-slate-400 hover:text-slate-200 transition-colors"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={actionLoading === "create"}
                  className="px-4 py-1.5 bg-indigo-600 hover:bg-indigo-500 text-white font-semibold rounded-lg shadow-sm transition-colors flex items-center space-x-1.5"
                >
                  {actionLoading === "create" && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
                  <span>Save Schedule</span>
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
