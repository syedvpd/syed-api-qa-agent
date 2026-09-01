"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import {
  LayoutDashboard,
  PlayCircle,
  Clock,
  CheckCircle2,
  XCircle,
  AlertTriangle,
  ShieldCheck,
  ArrowRight,
  Calendar,
  Activity,
  Cpu,
  RefreshCw
} from "lucide-react";
import { getApiBaseUrl } from "@/lib/api";

export default function DashboardPage() {
  const [runs, setRuns] = useState<any[]>([]);
  const [schedulesCount, setSchedulesCount] = useState(0);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const apiBase = getApiBaseUrl();
    Promise.all([
      fetch(`${apiBase}/api/runs`).then((res) => (res.ok ? res.json() : [])),
      fetch(`${apiBase}/api/schedules`).then((res) => (res.ok ? res.json() : []))
    ])
      .then(([runsData, schedulesData]) => {
        setRuns(runsData || []);
        setSchedulesCount(Array.isArray(schedulesData) ? schedulesData.length : 0);
        setLoading(false);
      })
      .catch(() => setLoading(false));
  }, []);

  const activeRuns = runs.filter((r) => r.status && !["COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT"].includes(r.status));
  const completedRuns = runs.filter((r) => r.status === "COMPLETED");
  const failedRuns = runs.filter((r) => r.status === "FAILED" || r.status === "TIMED_OUT");
  const cancelledRuns = runs.filter((r) => r.status === "CANCELLED");

  const totalPassed = runs.reduce((acc, r) => acc + (r.passedTests || 0), 0);
  const totalFailed = runs.reduce((acc, r) => acc + (r.failedTests || 0), 0);

  return (
    <div className="space-y-8">
      {/* Top Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-white tracking-tight flex items-center space-x-2.5">
            <LayoutDashboard className="h-6 w-6 text-indigo-400" />
            <span>Autonomous QA Operational Dashboard</span>
          </h1>
          <p className="text-sm text-slate-400">
            Real-time control plane, active concurrency, schedules, and regression monitoring across deployed APIs.
          </p>
        </div>
        <div className="flex items-center space-x-3">
          <Link
            href="/schedules"
            className="flex items-center space-x-2 px-3.5 py-2 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-semibold transition-all border border-slate-700"
          >
            <Calendar className="h-4 w-4 text-indigo-400" />
            <span>Schedules ({schedulesCount})</span>
          </Link>
          <Link
            href="/new-run"
            className="flex items-center space-x-2 px-4 py-2 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-semibold transition-all shadow-md shadow-emerald-950"
          >
            <PlayCircle className="h-4 w-4" />
            <span>New Test Run</span>
          </Link>
        </div>
      </div>

      {/* Operational Stats Grid */}
      <div className="grid grid-cols-2 sm:grid-cols-5 gap-4">
        <div className="p-4 rounded-xl bg-slate-900/60 border border-slate-800">
          <div className="flex items-center justify-between text-xs text-slate-400">
            <span>Active Runs</span>
            <Activity className="h-4 w-4 text-indigo-400 animate-pulse" />
          </div>
          <p className="text-2xl font-bold text-slate-100 mt-1">{activeRuns.length}</p>
          <div className="text-[10px] text-slate-500 mt-0.5">Concurrency: {activeRuns.length}/5 slots</div>
        </div>

        <div className="p-4 rounded-xl bg-slate-900/60 border border-slate-800">
          <div className="flex items-center justify-between text-xs text-emerald-400">
            <span>Completed</span>
            <CheckCircle2 className="h-4 w-4" />
          </div>
          <p className="text-2xl font-bold text-emerald-400 mt-1">{completedRuns.length}</p>
          <div className="text-[10px] text-slate-500 mt-0.5">{totalPassed} tests passed</div>
        </div>

        <div className="p-4 rounded-xl bg-slate-900/60 border border-slate-800">
          <div className="flex items-center justify-between text-xs text-rose-400">
            <span>Failed / Timeout</span>
            <XCircle className="h-4 w-4" />
          </div>
          <p className="text-2xl font-bold text-rose-400 mt-1">{failedRuns.length}</p>
          <div className="text-[10px] text-slate-500 mt-0.5">{totalFailed} failures isolated</div>
        </div>

        <div className="p-4 rounded-xl bg-slate-900/60 border border-slate-800">
          <div className="flex items-center justify-between text-xs text-amber-400">
            <span>Cancelled</span>
            <AlertTriangle className="h-4 w-4" />
          </div>
          <p className="text-2xl font-bold text-amber-400 mt-1">{cancelledRuns.length}</p>
          <div className="text-[10px] text-slate-500 mt-0.5">User/System stopped</div>
        </div>

        <div className="p-4 rounded-xl bg-slate-900/60 border border-slate-800">
          <div className="flex items-center justify-between text-xs text-purple-400">
            <span>Schedules</span>
            <Calendar className="h-4 w-4" />
          </div>
          <p className="text-2xl font-bold text-purple-400 mt-1">{schedulesCount}</p>
          <div className="text-[10px] text-slate-500 mt-0.5">Automated jobs</div>
        </div>
      </div>

      {/* Recent Runs Table */}
      <div className="rounded-xl border border-slate-800 bg-slate-950 overflow-hidden shadow-xl">
        <div className="p-4 border-b border-slate-800 flex items-center justify-between">
          <h2 className="text-xs font-semibold text-slate-300 uppercase tracking-wider">Historical Test Runs</h2>
          <span className="text-[11px] font-mono text-slate-500">Total Runs: {runs.length}</span>
        </div>

        {loading ? (
          <div className="p-16 text-center text-xs font-mono text-slate-500">Loading test runs...</div>
        ) : runs.length === 0 ? (
          <div className="p-16 text-center text-xs text-slate-500">
            No test runs initiated yet. Click "New Test Run" to start autonomous QA.
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs font-mono">
              <thead className="bg-slate-900/40 text-slate-400 border-b border-slate-800 text-[11px]">
                <tr>
                  <th className="py-2.5 px-4">Run ID</th>
                  <th className="py-2.5 px-4">Target OpenAPI</th>
                  <th className="py-2.5 px-4">Environment</th>
                  <th className="py-2.5 px-4">Status</th>
                  <th className="py-2.5 px-4">Results</th>
                  <th className="py-2.5 px-4">Created</th>
                  <th className="py-2.5 px-4 text-right">Links</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800/60">
                {runs.map((r) => (
                  <tr key={r.id} className="hover:bg-slate-900/30 transition-colors">
                    <td className="py-3 px-4 font-semibold text-slate-200">{r.id.substring(0, 8)}...</td>
                    <td className="py-3 px-4 text-slate-400 max-w-[220px] truncate">{r.openapiUrl}</td>
                    <td className="py-3 px-4">
                      <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-slate-800 text-slate-300">
                        {r.environmentType}
                      </span>
                    </td>
                    <td className="py-3 px-4">
                      <span
                        className={`px-2 py-0.5 rounded text-[10px] font-bold ${
                          r.status === "COMPLETED"
                            ? "bg-emerald-950 text-emerald-400 border border-emerald-800"
                            : r.status === "FAILED" || r.status === "TIMED_OUT"
                            ? "bg-rose-950 text-rose-400 border border-rose-800"
                            : r.status === "CANCELLED"
                            ? "bg-amber-950 text-amber-400 border border-amber-800"
                            : "bg-indigo-950 text-indigo-300 border border-indigo-800 animate-pulse"
                        }`}
                      >
                        {r.status}
                      </span>
                    </td>
                    <td className="py-3 px-4 text-slate-300">
                      <span className="text-emerald-400">{r.passedTests || 0}P</span> /{" "}
                      <span className="text-rose-400">{r.failedTests || 0}F</span> /{" "}
                      <span className="text-amber-400">{r.blockedTests || 0}B</span>
                    </td>
                    <td className="py-3 px-4 text-slate-400">
                      {new Date(r.createdAt).toLocaleDateString()} {new Date(r.createdAt).toLocaleTimeString()}
                    </td>
                    <td className="py-3 px-4 text-right space-x-2 font-sans">
                      <Link
                        href={`/runs/${r.id}/live`}
                        className="px-2 py-1 bg-slate-800 hover:bg-slate-700 text-slate-200 rounded text-[11px] transition-colors"
                      >
                        Live
                      </Link>
                      <Link
                        href={`/runs/${r.id}/regression`}
                        className="px-2 py-1 bg-indigo-950 hover:bg-indigo-900 text-indigo-300 rounded text-[11px] border border-indigo-800/80 transition-colors"
                      >
                        Regression
                      </Link>
                      <Link
                        href={`/runs/${r.id}/report`}
                        className="px-2 py-1 bg-slate-800 hover:bg-slate-700 text-slate-200 rounded text-[11px] transition-colors"
                      >
                        Report
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
