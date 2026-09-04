"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import {
  PlayCircle,
  Activity,
  CheckCircle2,
  XCircle,
  AlertTriangle,
  Calendar,
  Terminal,
  FileText,
  RefreshCw,
  ArrowRight,
  ExternalLink,
  Layers,
  Radio,
  Cpu,
  Clock,
  ShieldCheck,
} from "lucide-react";
import { getApiBaseUrl, authenticatedFetch } from "@/lib/api";
import ApiNetworkBackground from "@/components/ApiNetworkBackground";

interface TestStepItem {
  id: string;
  runId: string;
  httpMethod: string;
  pathTemplate: string;
  status: string;
  latencyMs?: number;
  actualStatus?: number;
  expectedStatus?: number;
  failureReason?: string;
}

export default function DashboardPage() {
  const [runs, setRuns] = useState<any[]>([]);
  const [schedulesCount, setSchedulesCount] = useState<number | null>(null);
  const [healthStatus, setHealthStatus] = useState<string>("CONNECTING");
  const [recentSteps, setRecentSteps] = useState<TestStepItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);

  const fetchData = async () => {
    const apiBase = getApiBaseUrl();
    try {
      // 1. Fetch Health, Runs, and Schedules concurrently
      const [healthRes, runsRes, schedulesRes] = await Promise.all([
        fetch(`${apiBase}/api/health`).then((r) => (r.ok ? r.json() : null)).catch(() => null),
        authenticatedFetch(`${apiBase}/api/runs`).then((r) => (r.ok ? r.json() : [])).catch(() => []),
        authenticatedFetch(`${apiBase}/api/schedules`).then((r) => (r.ok ? r.json() : [])).catch(() => []),
      ]);

      setHealthStatus(healthRes && healthRes.status === "UP" ? "ONLINE" : "OFFLINE");
      const runsList = Array.isArray(runsRes) ? runsRes : [];
      setRuns(runsList);
      setSchedulesCount(Array.isArray(schedulesRes) ? schedulesRes.length : 0);

      // 2. Fetch real test execution steps from the most recent run (if available)
      if (runsList.length > 0) {
        const latestRun = runsList[0];
        try {
          const casesRes = await authenticatedFetch(`${apiBase}/api/runs/${latestRun.id}/cases`);
          if (casesRes.ok) {
            const casesData = await casesRes.json();
            const extractedSteps: TestStepItem[] = [];
            if (Array.isArray(casesData)) {
              for (const c of casesData) {
                if (c.steps && Array.isArray(c.steps)) {
                  for (const s of c.steps) {
                    extractedSteps.push({
                      id: s.id,
                      runId: latestRun.id,
                      httpMethod: s.httpMethod || "GET",
                      pathTemplate: s.pathTemplate || "/",
                      status: s.status || "PENDING",
                      latencyMs: s.latencyMs,
                      actualStatus: s.actualStatus || s.expectedStatus,
                      expectedStatus: s.expectedStatus,
                      failureReason: s.failureReason,
                    });
                  }
                }
              }
            }
            // Keep the latest 8 steps
            setRecentSteps(extractedSteps.slice(-8).reverse());
          }
        } catch {
          setRecentSteps([]);
        }
      } else {
        setRecentSteps([]);
      }
    } catch (e) {
      console.error("Failed to load dashboard data:", e);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  };

  useEffect(() => {
    fetchData();
  }, []);

  const handleRefresh = () => {
    setRefreshing(true);
    fetchData();
  };

  // Real Metric Aggregations
  const activeRuns = runs.filter(
    (r) => r.status && !["COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT"].includes(r.status)
  );
  const completedRuns = runs.filter((r) => r.status === "COMPLETED");
  const failedRuns = runs.filter((r) => r.status === "FAILED" || r.status === "TIMED_OUT");
  const cancelledRuns = runs.filter((r) => r.status === "CANCELLED");

  const distinctTargets = new Set(runs.map((r) => r.openapiUrl).filter(Boolean));
  const distinctTargetsCount = distinctTargets.size;

  const totalPassed = runs.reduce((acc, r) => acc + (r.passedTests || 0), 0);
  const totalFailed = runs.reduce((acc, r) => acc + (r.failedTests || 0), 0);
  const totalBlocked = runs.reduce((acc, r) => acc + (r.blockedTests || 0), 0);
  const totalExecutedSteps = totalPassed + totalFailed + totalBlocked;

  const passRate =
    totalExecutedSteps > 0 ? ((totalPassed / totalExecutedSteps) * 100).toFixed(1) : null;

  const latestRun = runs.length > 0 ? runs[0] : null;
  const latestCompletedRun = completedRuns.length > 0 ? completedRuns[0] : null;
  const latestCoverageScore =
    latestCompletedRun && latestCompletedRun.coverageScore != null
      ? latestCompletedRun.coverageScore
      : null;

  return (
    <div className="relative min-h-[calc(100vh-8rem)] pb-12">
      {/* Subtle animated execution graph backdrop */}
      <ApiNetworkBackground />

      <div className="space-y-8 relative z-10">
        {/* ============================================================
            1. HERO MAC TERMINAL (CONTROL CENTER)
        ============================================================ */}
        <section className="relative group">
          {/* Subtle Glow behind the terminal */}
          <div className="absolute -inset-0.5 bg-gradient-to-r from-emerald-500/10 via-indigo-500/10 to-emerald-500/5 rounded-2xl blur-lg opacity-60 group-hover:opacity-80 transition duration-500 pointer-events-none" />

          <div className="relative rounded-2xl border border-slate-800/80 bg-[#090d16]/95 backdrop-blur-xl shadow-2xl shadow-black/80 overflow-hidden">
            {/* macOS Chrome Title Bar */}
            <div className="h-10 px-4 bg-[#0d1320] border-b border-slate-800/90 flex items-center justify-between select-none">
              {/* Traffic Lights */}
              <div className="flex items-center space-x-2">
                <span className="w-3 h-3 rounded-full bg-[#ff5f56] border border-[#e0443e] inline-block shadow-sm" />
                <span className="w-3 h-3 rounded-full bg-[#ffbd2e] border border-[#dea123] inline-block shadow-sm" />
                <span className="w-3 h-3 rounded-full bg-[#27c93f] border border-[#1aab29] inline-block shadow-sm" />
                <span className="ml-3 text-[11px] font-mono text-slate-400 font-medium tracking-wide">
                  syed-api-qa-agent — control-plane
                </span>
              </div>

              {/* Status / Activity Indicator */}
              <div className="flex items-center space-x-3 text-[11px] font-mono">
                <div className="flex items-center space-x-1.5 px-2.5 py-0.5 rounded-full bg-slate-900 border border-slate-700/60">
                  <span
                    className={`w-2 h-2 rounded-full ${
                      healthStatus === "ONLINE"
                        ? "bg-emerald-400 shadow-sm shadow-emerald-500/50 animate-pulse"
                        : "bg-rose-500"
                    }`}
                  />
                  <span className="text-slate-300 font-semibold tracking-wider uppercase">
                    {healthStatus === "ONLINE" ? "SYSTEM ONLINE" : "CONNECTING"}
                  </span>
                </div>
                <button
                  onClick={handleRefresh}
                  disabled={refreshing}
                  aria-label="Refresh dashboard data"
                  className="p-1 rounded text-slate-400 hover:text-slate-200 hover:bg-slate-800 transition-colors"
                  title="Refresh control plane state"
                >
                  <RefreshCw className={`h-3.5 w-3.5 ${refreshing ? "animate-spin text-emerald-400" : ""}`} />
                </button>
              </div>
            </div>

            {/* Terminal Body */}
            <div className="p-5 sm:p-7 font-mono text-xs sm:text-[13px] leading-relaxed text-slate-300 space-y-4">
              <div className="text-emerald-400 font-semibold flex items-center space-x-2">
                <span className="text-slate-500">$</span>
                <span>syed-api-qa-agent status --cluster=production</span>
              </div>

              {/* Real Operational Diagnostics Grid */}
              <div className="grid grid-cols-1 md:grid-cols-2 gap-x-8 gap-y-2 text-slate-300 pt-1">
                <div className="flex justify-between border-b border-slate-800/40 pb-1">
                  <span className="text-slate-500">SYSTEM ARCHITECTURE:</span>
                  <span className="text-emerald-400 font-bold">
                    {healthStatus === "ONLINE" ? "ONLINE [ZERO-LLM DETERMINISTIC]" : "--"}
                  </span>
                </div>
                <div className="flex justify-between border-b border-slate-800/40 pb-1">
                  <span className="text-slate-500">TARGET APIS DISCOVERED:</span>
                  <span className="text-slate-200 font-semibold">
                    {distinctTargetsCount > 0
                      ? `${distinctTargetsCount.toString().padStart(2, "0")} DEPLOYED SERVICES`
                      : "--"}
                  </span>
                </div>
                <div className="flex justify-between border-b border-slate-800/40 pb-1">
                  <span className="text-slate-500">HISTORICAL TEST RUNS:</span>
                  <span className="text-slate-200 font-semibold">
                    {runs.length > 0 ? `${runs.length.toString().padStart(2, "0")} EXECUTIONS RECORDED` : "--"}
                  </span>
                </div>
                <div className="flex justify-between border-b border-slate-800/40 pb-1">
                  <span className="text-slate-500">LIFETIME TEST STEPS:</span>
                  <span className="text-slate-200 font-semibold">
                    {totalExecutedSteps > 0 ? `${totalExecutedSteps} AGGREGATE STEPS (${runs.length} RUNS)` : "--"}
                  </span>
                </div>
                <div className="flex justify-between border-b border-slate-800/40 pb-1">
                  <span className="text-slate-500">LATEST RUN ACCOUNTING:</span>
                  <span className="text-slate-200 font-semibold">
                    {latestRun ? `${latestRun.totalTests || 0} TESTS (${latestRun.passedTests || 0}P / ${latestRun.failedTests || 0}F / ${latestRun.blockedTests || 0}B)` : "--"}
                  </span>
                </div>
                <div className="flex justify-between border-b border-slate-800/40 pb-1">
                  <span className="text-slate-500">LATEST ENDPOINT COVERAGE:</span>
                  <span
                    className={
                      latestCoverageScore != null
                        ? "text-cyan-400 font-bold"
                        : "text-slate-500"
                    }
                  >
                    {latestCoverageScore != null
                      ? `${latestCoverageScore.toFixed(1)}% (DETERMINISTIC GRAPH)`
                      : "--"}
                  </span>
                </div>
                <div className="flex justify-between border-b border-slate-800/40 pb-1">
                  <span className="text-slate-500">LAST RUN STATUS:</span>
                  <span
                    className={`font-semibold ${
                      latestRun?.status === "COMPLETED"
                        ? "text-emerald-400"
                        : latestRun?.status === "FAILED" || latestRun?.status === "TIMED_OUT"
                        ? "text-rose-400"
                        : latestRun?.status
                        ? "text-indigo-400"
                        : "text-slate-500"
                    }`}
                  >
                    {latestRun
                      ? `${latestRun.status}${
                          latestRun.durationMs
                            ? ` (${(latestRun.durationMs / 1000).toFixed(1)}s)`
                            : ""
                        }`
                      : "--"}
                  </span>
                </div>
                <div className="flex justify-between border-b border-slate-800/40 pb-1">
                  <span className="text-slate-500">AUDIT / PDF GENERATION:</span>
                  <span className="text-emerald-400 font-semibold">
                    {latestCompletedRun ? "READY (IN-MEMORY SIGNED)" : "--"}
                  </span>
                </div>
              </div>

              {/* Dynamic Console Prompt & Cursor */}
              <div className="pt-2 text-slate-400 flex items-center space-x-2">
                <span className="text-emerald-500">&gt;</span>
                <span>
                  {activeRuns.length > 0
                    ? `executing ${activeRuns.length} concurrent test run(s) across active cluster...`
                    : "autonomous QA engine ready — awaiting target specification or scheduled trigger"}
                </span>
                <span className="inline-block w-2 h-4 bg-emerald-400 animate-pulse ml-1" />
              </div>
            </div>
          </div>
        </section>

        {/* ============================================================
            2. PRIMARY ACTION CONTROLS
        ============================================================ */}
        <div className="flex flex-col sm:flex-row items-stretch sm:items-center justify-between gap-4">
          <div className="flex items-center space-x-3">
            <Link
              href="/new-run"
              className="flex items-center justify-center space-x-2 px-5 py-2.5 rounded-xl bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-semibold tracking-wide transition-all shadow-lg shadow-emerald-950/60 hover:shadow-emerald-900/80 active:scale-[0.99] border border-emerald-500/40"
            >
              <PlayCircle className="h-4 w-4" />
              <span>Launch Autonomous Test Run</span>
              <ArrowRight className="h-3.5 w-3.5 ml-1 opacity-80" />
            </Link>

            {latestRun && (
              <Link
                href={`/runs/${latestRun.id}/live`}
                className="flex items-center justify-center space-x-2 px-4 py-2.5 rounded-xl bg-slate-900/90 hover:bg-slate-800 text-slate-200 text-xs font-semibold transition-all border border-slate-700/80 shadow-md"
              >
                <Terminal className="h-4 w-4 text-emerald-400" />
                <span>View Live Console</span>
                <ExternalLink className="h-3 w-3 text-slate-500" />
              </Link>
            )}
          </div>

          <div className="flex items-center space-x-3">
            <Link
              href="/schedules"
              className="flex items-center justify-center space-x-2 px-4 py-2 rounded-xl bg-slate-900/80 hover:bg-slate-800 text-slate-300 text-xs font-medium transition-all border border-slate-800"
            >
              <Calendar className="h-3.5 w-3.5 text-purple-400" />
              <span>
                Automated Schedules{" "}
                {schedulesCount !== null ? `(${schedulesCount})` : ""}
              </span>
            </Link>
          </div>
        </div>

        {/* ============================================================
            3. REAL OPERATIONAL QA METRICS (5 CARDS)
        ============================================================ */}
        <div className="grid grid-cols-2 sm:grid-cols-5 gap-3.5">
          {/* Active Runs */}
          <div className="p-4 rounded-xl bg-[#0b0f19]/80 border border-slate-800/80 shadow-sm transition-all hover:border-slate-700">
            <div className="flex items-center justify-between text-xs text-slate-400">
              <span className="font-mono">Active Runs</span>
              <Activity
                className={`h-4 w-4 ${
                  activeRuns.length > 0
                    ? "text-indigo-400 animate-pulse"
                    : "text-slate-600"
                }`}
              />
            </div>
            <p className="text-2xl font-bold font-mono text-slate-100 mt-1.5">
              {activeRuns.length}
            </p>
            <div className="text-[11px] text-slate-500 mt-1 font-mono">
              Slots: {activeRuns.length}/5 bound
            </div>
          </div>

          {/* Completed Runs */}
          <div className="p-4 rounded-xl bg-[#0b0f19]/80 border border-slate-800/80 shadow-sm transition-all hover:border-slate-700">
            <div className="flex items-center justify-between text-xs text-emerald-400">
              <span className="font-mono">Completed</span>
              <CheckCircle2 className="h-4 w-4" />
            </div>
            <p className="text-2xl font-bold font-mono text-emerald-400 mt-1.5">
              {completedRuns.length}
            </p>
            <div className="text-[11px] text-slate-500 mt-1 font-mono">
              {totalPassed} steps verified
            </div>
          </div>

          {/* Failed / Timeout */}
          <div className="p-4 rounded-xl bg-[#0b0f19]/80 border border-slate-800/80 shadow-sm transition-all hover:border-slate-700">
            <div className="flex items-center justify-between text-xs text-rose-400">
              <span className="font-mono">Failed / Blocked</span>
              <XCircle className="h-4 w-4" />
            </div>
            <p className="text-2xl font-bold font-mono text-rose-400 mt-1.5">
              {failedRuns.length}
            </p>
            <div className="text-[11px] text-slate-500 mt-1 font-mono">
              {totalFailed} regressions isolated
            </div>
          </div>

          {/* Deterministic Coverage */}
          <div className="p-4 rounded-xl bg-[#0b0f19]/80 border border-slate-800/80 shadow-sm transition-all hover:border-slate-700">
            <div className="flex items-center justify-between text-xs text-cyan-400">
              <span className="font-mono">Endpoint Coverage</span>
              <Layers className="h-4 w-4" />
            </div>
            <p className="text-2xl font-bold font-mono text-cyan-400 mt-1.5">
              {latestCoverageScore != null ? `${latestCoverageScore.toFixed(1)}%` : "--"}
            </p>
            <div className="text-[11px] text-slate-500 mt-1 font-mono">
              Deterministic graph
            </div>
          </div>

          {/* Automated Schedules */}
          <div className="p-4 rounded-xl bg-[#0b0f19]/80 border border-slate-800/80 shadow-sm transition-all hover:border-slate-700 col-span-2 sm:col-span-1">
            <div className="flex items-center justify-between text-xs text-purple-400">
              <span className="font-mono">Schedules</span>
              <Calendar className="h-4 w-4" />
            </div>
            <p className="text-2xl font-bold font-mono text-purple-400 mt-1.5">
              {schedulesCount !== null ? schedulesCount : "--"}
            </p>
            <div className="text-[11px] text-slate-500 mt-1 font-mono">
              Active cron jobs
            </div>
          </div>
        </div>

        {/* ============================================================
            4. RECENT EXECUTION STREAM
        ============================================================ */}
        <div className="rounded-xl border border-slate-800/80 bg-[#090d16]/90 overflow-hidden shadow-xl">
          <div className="px-5 py-3.5 border-b border-slate-800/80 flex items-center justify-between bg-[#0d1320]/60">
            <div className="flex items-center space-x-2.5">
              <Radio className="h-4 w-4 text-emerald-400 animate-pulse" />
              <h2 className="text-xs font-semibold text-slate-200 tracking-wider uppercase font-mono">
                Recent Execution Activity
              </h2>
            </div>
            <span className="text-[11px] font-mono text-slate-500">
              {latestRun
                ? `Run: ${latestRun.id.substring(0, 8)}... (${latestRun.openapiUrl})`
                : "No active executions"}
            </span>
          </div>

          <div className="p-4 font-mono text-xs divide-y divide-slate-800/40">
            {recentSteps.length === 0 ? (
              <div className="py-6 text-center text-slate-500">
                &gt; No recent step executions recorded yet. Launch a run to stream execution events.
              </div>
            ) : (
              recentSteps.map((step) => {
                const isPassed = step.status === "PASSED";
                const isFailed = step.status === "FAILED";
                const isBlocked = step.status === "BLOCKED";

                return (
                  <Link
                    key={step.id}
                    href={`/runs/${step.runId}/live`}
                    className="flex items-center justify-between py-2.5 px-3 -mx-2 rounded hover:bg-slate-900/60 transition-colors group"
                  >
                    <div className="flex items-center space-x-3 truncate">
                      {/* Status Icon */}
                      <span className="w-4 text-center font-bold">
                        {isPassed && <span className="text-emerald-400">✓</span>}
                        {isFailed && <span className="text-rose-400">✗</span>}
                        {isBlocked && <span className="text-amber-400">○</span>}
                        {!isPassed && !isFailed && !isBlocked && (
                          <span className="text-slate-500">●</span>
                        )}
                      </span>

                      {/* Method Badge */}
                      <span
                        className={`px-1.5 py-0.5 rounded text-[10px] font-bold tracking-wider ${
                          step.httpMethod === "GET"
                            ? "bg-sky-950 text-sky-300 border border-sky-800/60"
                            : step.httpMethod === "POST"
                            ? "bg-emerald-950 text-emerald-300 border border-emerald-800/60"
                            : step.httpMethod === "DELETE"
                            ? "bg-rose-950 text-rose-300 border border-rose-800/60"
                            : "bg-amber-950 text-amber-300 border border-amber-800/60"
                        }`}
                      >
                        {step.httpMethod}
                      </span>

                      {/* Path */}
                      <span className="text-slate-300 group-hover:text-white transition-colors truncate">
                        {step.pathTemplate}
                      </span>
                    </div>

                    <div className="flex items-center space-x-4 shrink-0 pl-3">
                      {step.actualStatus && (
                        <span
                          className={`text-[11px] font-bold ${
                            step.actualStatus >= 200 && step.actualStatus < 300
                              ? "text-emerald-400"
                              : step.actualStatus >= 400
                              ? "text-rose-400"
                              : "text-slate-400"
                          }`}
                        >
                          {step.actualStatus}
                        </span>
                      )}
                      {step.latencyMs != null && (
                        <span className="text-[11px] text-slate-500">
                          {step.latencyMs}ms
                        </span>
                      )}
                      <span
                        className={`text-[10px] px-1.5 py-0.5 rounded font-bold uppercase ${
                          isPassed
                            ? "text-emerald-400 bg-emerald-950/40"
                            : isFailed
                            ? "text-rose-400 bg-rose-950/40"
                            : isBlocked
                            ? "text-amber-400 bg-amber-950/40"
                            : "text-slate-400 bg-slate-800/40"
                        }`}
                      >
                        {step.status}
                      </span>
                      <ArrowRight className="h-3 w-3 text-slate-600 group-hover:text-slate-300 transition-colors" />
                    </div>
                  </Link>
                );
              })
            )}
          </div>
        </div>

        {/* ============================================================
            5. HISTORICAL TEST RUNS TABLE
        ============================================================ */}
        <div className="rounded-xl border border-slate-800/80 bg-[#090d16]/90 overflow-hidden shadow-xl">
          <div className="px-5 py-4 border-b border-slate-800/80 flex items-center justify-between bg-[#0d1320]/60">
            <h2 className="text-xs font-semibold text-slate-200 uppercase tracking-wider font-mono">
              Historical Test Runs
            </h2>
            <span className="text-[11px] font-mono text-slate-500">
              Total Runs: {runs.length}
            </span>
          </div>

          {loading ? (
            <div className="p-16 text-center text-xs font-mono text-slate-500">
              &gt; Loading test runs from repository...
            </div>
          ) : runs.length === 0 ? (
            <div className="p-16 text-center text-xs text-slate-500 font-mono">
              &gt; No test runs initiated yet. Click "Launch Autonomous Test Run" to start.
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs font-mono">
                <thead className="bg-[#0b0f19] text-slate-400 border-b border-slate-800/80 text-[11px]">
                  <tr>
                    <th className="py-3 px-4 font-medium">RUN ID</th>
                    <th className="py-3 px-4 font-medium">TARGET OPENAPI</th>
                    <th className="py-3 px-4 font-medium">ENV</th>
                    <th className="py-3 px-4 font-medium">STATUS</th>
                    <th className="py-3 px-4 font-medium">RESULTS</th>
                    <th className="py-3 px-4 font-medium">DURATION</th>
                    <th className="py-3 px-4 font-medium">CREATED</th>
                    <th className="py-3 px-4 text-right font-medium">QUICK ACTIONS</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-800/40">
                  {runs.map((r) => (
                    <tr key={r.id} className="hover:bg-slate-900/40 transition-colors">
                      <td className="py-3 px-4 font-semibold text-slate-200">
                        {r.id.substring(0, 8)}...
                      </td>
                      <td className="py-3 px-4 text-slate-400 max-w-[220px] truncate" title={r.openapiUrl}>
                        {r.openapiUrl}
                      </td>
                      <td className="py-3 px-4">
                        <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-slate-800 text-slate-300 border border-slate-700/60">
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
                        <span className="text-emerald-400 font-bold">{r.passedTests || 0}P</span>{" "}
                        / <span className="text-rose-400 font-bold">{r.failedTests || 0}F</span>{" "}
                        / <span className="text-amber-400 font-bold">{r.blockedTests || 0}B</span>
                      </td>
                      <td className="py-3 px-4 text-slate-400">
                        {r.durationMs ? `${(r.durationMs / 1000).toFixed(1)}s` : "--"}
                      </td>
                      <td className="py-3 px-4 text-slate-400">
                        {new Date(r.createdAt).toLocaleDateString()} {new Date(r.createdAt).toLocaleTimeString()}
                      </td>
                      <td className="py-3 px-4 text-right space-x-2 font-sans">
                        <Link
                          href={`/runs/${r.id}/live`}
                          className="px-2 py-1 bg-slate-800 hover:bg-slate-700 text-slate-200 rounded text-[11px] font-medium transition-colors"
                        >
                          Live
                        </Link>
                        <Link
                          href={`/runs/${r.id}/regression`}
                          className="px-2 py-1 bg-indigo-950 hover:bg-indigo-900 text-indigo-300 rounded text-[11px] font-medium border border-indigo-800/80 transition-colors"
                        >
                          Regression
                        </Link>
                        <Link
                          href={`/runs/${r.id}/report`}
                          className="px-2 py-1 bg-slate-800 hover:bg-slate-700 text-slate-200 rounded text-[11px] font-medium transition-colors"
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
    </div>
  );
}
