"use client";

import { useEffect, useState, useRef } from "react";
import Link from "next/link";
import {
  ArrowLeft,
  CheckCircle2,
  XCircle,
  AlertTriangle,
  Activity,
  Terminal as TerminalIcon,
  FileText,
  Download,
  ChevronDown,
  ChevronRight,
  ExternalLink,
  Clock,
  ArrowDown,
  Layers,
  Sparkles
} from "lucide-react";
import { getApiBaseUrl, authenticatedFetch } from "@/lib/api";

interface TerminalEntry {
  id: string;
  type: "INFO" | "SUCCESS" | "WARN" | "ERROR" | "STEP_START" | "STEP_PASS" | "STEP_FAIL" | "STEP_BLOCK" | "VAR";
  text: string;
  time: string;
  method?: string;
  path?: string;
  status?: number;
  latencyMs?: number;
  assertionsPass?: number;
  assertionsTotal?: number;
  reason?: string;
  details?: {
    requestHeaders?: string;
    requestBody?: string;
    responseBody?: string;
  };
}

export default function LiveRunPage({ params }: { params: { id: string } }) {
  const [status, setStatus] = useState("INITIALIZING");
  const [openapiUrl, setOpenapiUrl] = useState("");
  const [targetBaseUrl, setTargetBaseUrl] = useState("");
  const [totalApis, setTotalApis] = useState(0);
  const [totalTests, setTotalTests] = useState(0);
  const [passed, setPassed] = useState(0);
  const [failed, setFailed] = useState(0);
  const [blocked, setBlocked] = useState(0);
  const [coverageScore, setCoverageScore] = useState<number | null>(null);
  const [durationMs, setDurationMs] = useState(0);
  const [elapsedSec, setElapsedSec] = useState(0);

  const [entries, setEntries] = useState<TerminalEntry[]>([]);
  const [expandedId, setExpandedId] = useState<string | null>(null);

  // Auto-scroll control
  const scrollRef = useRef<HTMLDivElement>(null);
  const [isScrolledToBottom, setIsScrolledToBottom] = useState(true);
  const [unreadCount, setUnreadCount] = useState(0);

  // Timer for active elapsed time
  useEffect(() => {
    if (status === "COMPLETED" || status === "FAILED") return;
    const interval = setInterval(() => {
      setElapsedSec((prev) => prev + 1);
    }, 1000);
    return () => clearInterval(interval);
  }, [status]);

  const addEntry = (entry: Omit<TerminalEntry, "id" | "time">) => {
    const newEntry: TerminalEntry = {
      ...entry,
      id: Math.random().toString(36).substring(2, 9),
      time: new Date().toLocaleTimeString(),
    };

    setEntries((prev) => [...prev, newEntry]);

    if (!isScrolledToBottom) {
      setUnreadCount((prev) => prev + 1);
    }
  };

  // Scroll to bottom helper
  const scrollToBottom = () => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
      setIsScrolledToBottom(true);
      setUnreadCount(0);
    }
  };

  useEffect(() => {
    if (isScrolledToBottom && scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [entries, isScrolledToBottom]);

  const handleScroll = () => {
    if (!scrollRef.current) return;
    const { scrollTop, scrollHeight, clientHeight } = scrollRef.current;
    const atBottom = scrollHeight - scrollTop - clientHeight < 50;
    setIsScrolledToBottom(atBottom);
    if (atBottom) setUnreadCount(0);
  };

  useEffect(() => {
    const apiBase = getApiBaseUrl();
    const token = typeof window !== "undefined"
      ? (new URLSearchParams(window.location.search).get("token") || localStorage.getItem("syed_auth_token") || "")
      : "";
    const eventsUrl = token
      ? `${apiBase}/api/runs/${params.id}/events?token=${encodeURIComponent(token)}`
      : `${apiBase}/api/runs/${params.id}/events`;

    addEntry({ type: "INFO", text: `Connecting to autonomous testing engine for run ${params.id}...` });

    const eventSource = new EventSource(eventsUrl);

    eventSource.addEventListener("CONNECTED", () => {
      setStatus("STREAMING");
      addEntry({ type: "SUCCESS", text: `✓ Connected to live test execution stream` });
    });

    eventSource.addEventListener("DISCOVERY_STARTED", (e: MessageEvent) => {
      const data = JSON.parse(e.data);
      setStatus("DISCOVERING");
      setOpenapiUrl(data.openapiUrl || "");
      addEntry({ type: "INFO", text: `> Validating target & fetching specification: ${data.openapiUrl}` });
    });

    eventSource.addEventListener("API_DISCOVERED", (e: MessageEvent) => {
      const data = JSON.parse(e.data);
      setTotalApis((prev) => prev + 1);
      addEntry({
        type: "INFO",
        text: `  Discovered route: ${data.method} ${data.path}`,
        method: data.method,
        path: data.path
      });
    });

    eventSource.addEventListener("PLANNING_STARTED", (e: MessageEvent) => {
      const data = JSON.parse(e.data);
      setStatus("PLANNING");
      addEntry({ type: "INFO", text: `> Formulating dependency graph across ${data.endpointsCount} operations...` });
    });

    eventSource.addEventListener("PLANNING_COMPLETED", (e: MessageEvent) => {
      const data = JSON.parse(e.data);
      setStatus("EXECUTING");
      setTotalTests(data.stepsCount);
      addEntry({
        type: "SUCCESS",
        text: `✓ Dependency graph & topological test plan generated (${data.casesCount} scenarios, ${data.stepsCount} operations)`
      });
    });

    eventSource.addEventListener("TEST_STARTED", (e: MessageEvent) => {
      const data = JSON.parse(e.data);
      addEntry({
        type: "STEP_START",
        text: `● ${data.method || "REQ"} ${data.name || ""}`,
        method: data.method,
        path: data.name
      });
    });

    eventSource.addEventListener("TEST_COMPLETED", (e: MessageEvent) => {
      const data = JSON.parse(e.data);
      if (typeof data.passed === "number") setPassed(data.passed);
      if (typeof data.failed === "number") setFailed(data.failed);
      if (typeof data.blocked === "number") setBlocked(data.blocked);
      addEntry({
        type: "STEP_PASS",
        text: `✓ ${data.method || "HTTP"} ${data.name || "Step"}`,
        method: data.method,
        path: data.name,
        status: 200,
        assertionsPass: 1,
        assertionsTotal: 1
      });
    });

    eventSource.addEventListener("TEST_FAILED", (e: MessageEvent) => {
      const data = JSON.parse(e.data);
      if (typeof data.passed === "number") setPassed(data.passed);
      if (typeof data.failed === "number") setFailed(data.failed);
      if (typeof data.blocked === "number") setBlocked(data.blocked);
      addEntry({
        type: "STEP_FAIL",
        text: `✗ ${data.name || "Operation failed"}`,
        method: data.method,
        path: data.name,
        reason: data.reason || "Assertion or contract violation"
      });
    });

    eventSource.addEventListener("TEST_BLOCKED", (e: MessageEvent) => {
      const data = JSON.parse(e.data);
      if (typeof data.passed === "number") setPassed(data.passed);
      if (typeof data.failed === "number") setFailed(data.failed);
      if (typeof data.blocked === "number") setBlocked(data.blocked);
      addEntry({
        type: "STEP_BLOCK",
        text: `○ BLOCKED: ${data.name || "Dependent operation"} (${data.reason || "Upstream dependency failed"})`,
        path: data.name,
        reason: data.reason
      });
    });

    eventSource.addEventListener("COVERAGE_CALCULATED", (e: MessageEvent) => {
      const data = JSON.parse(e.data);
      if (data.qaCoverageScore) setCoverageScore(data.qaCoverageScore);
      addEntry({
        type: "SUCCESS",
        text: `✓ Coverage score calculated: ${data.qaCoverageScore}% (${data.fullyTested} fully tested, ${data.partiallyTested} partially tested)`
      });
    });

    eventSource.addEventListener("REPORTING_STARTED", () => {
      setStatus("REPORTING");
      addEntry({ type: "INFO", text: `> Compiling executive HTML audit report & vector PDF documentation...` });
    });

    eventSource.addEventListener("RUN_COMPLETED", (e: MessageEvent) => {
      const data = JSON.parse(e.data);
      setStatus("COMPLETED");
      if (data.durationMs) setDurationMs(data.durationMs);
      addEntry({
        type: "SUCCESS",
        text: `━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n✓ RUN COMPLETED in ${data.durationMs || 0} ms\n  HTML Report: Ready | PDF Report: Verified\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━`
      });
    });

    eventSource.addEventListener("RUN_FAILED", (e: MessageEvent) => {
      const data = JSON.parse(e.data);
      setStatus("FAILED");
      addEntry({
        type: "ERROR",
        text: `✗ RUN TERMINATED: ${data.error || "Execution halted due to fatal pipeline error"}`
      });
    });

    // Fallback sync polling
    const syncRunState = async () => {
      try {
        const res = await authenticatedFetch(`${apiBase}/api/runs/${params.id}`);
        if (res.ok) {
          const run = await res.json();
          if (run.status) setStatus(run.status);
          if (run.openapiUrl) setOpenapiUrl(run.openapiUrl);
          if (run.targetBaseUrl) setTargetBaseUrl(run.targetBaseUrl);
          if (run.totalEndpoints) setTotalApis(run.totalEndpoints);
          if (run.totalTests) setTotalTests(run.totalTests);
          if (run.passedTests !== undefined) setPassed(run.passedTests);
          if (run.failedTests !== undefined) setFailed(run.failedTests);
          if (run.blockedTests !== undefined) setBlocked(run.blockedTests);
          if (run.durationMs) setDurationMs(run.durationMs);
        }
      } catch (ignored) {}
    };

    syncRunState();
    const pollInterval = setInterval(syncRunState, 3000);

    return () => {
      eventSource.close();
      clearInterval(pollInterval);
    };
  }, [params.id]);

  const executedCount = passed + failed + blocked;
  const progressPct = totalTests > 0 ? Math.min(100, Math.round((executedCount / totalTests) * 100)) : 0;
  const formatTime = (sec: number) => {
    const mins = Math.floor(sec / 60).toString().padStart(2, "0");
    const secs = (sec % 60).toString().padStart(2, "0");
    return `${mins}:${secs}`;
  };

  return (
    <div className="min-h-screen bg-[#07090e] text-slate-200 py-6 px-3 sm:px-6 flex flex-col items-center">
      {/* Top Breadcrumb & Actions Bar */}
      <div className="w-full max-w-6xl flex items-center justify-between mb-4">
        <div className="flex items-center space-x-2 text-xs">
          <Link
            href="/dashboard"
            className="inline-flex items-center space-x-1.5 text-slate-400 hover:text-slate-200 transition-colors"
          >
            <ArrowLeft className="h-3.5 w-3.5" />
            <span>Dashboard</span>
          </Link>
          <span className="text-slate-600">/</span>
          <span className="font-mono text-slate-300">Run {params.id.substring(0, 8)}...</span>
        </div>

        <div className="flex items-center space-x-3">
          <Link
            href={`/runs/${params.id}/results`}
            className="flex items-center space-x-1.5 px-3 py-1.5 rounded-lg bg-slate-900 hover:bg-slate-800 text-xs font-semibold text-slate-300 border border-slate-800 transition-colors"
          >
            <Activity className="h-3.5 w-3.5 text-indigo-400" />
            <span>Results Matrix</span>
          </Link>
          <Link
            href={`/runs/${params.id}/report`}
            className="flex items-center space-x-1.5 px-3 py-1.5 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-xs font-semibold text-white shadow-sm transition-colors"
          >
            <FileText className="h-3.5 w-3.5" />
            <span>Audit Report</span>
          </Link>
          <a
            href={`${getApiBaseUrl()}/api/runs/${params.id}/report/pdf`}
            target="_blank"
            rel="noopener noreferrer"
            className="flex items-center space-x-1.5 px-3 py-1.5 rounded-lg bg-slate-900 hover:bg-slate-800 text-xs font-semibold text-slate-300 border border-slate-800 transition-colors"
          >
            <Download className="h-3.5 w-3.5 text-emerald-400" />
            <span>PDF</span>
          </a>
        </div>
      </div>

      {/* Hero MacBook-Style Terminal Console */}
      <div className="w-full max-w-6xl rounded-2xl bg-[#0d1117] border border-slate-800/80 shadow-[0_20px_60px_-15px_rgba(0,0,0,0.8)] overflow-hidden flex flex-col font-mono text-xs">
        {/* macOS Window Title Bar */}
        <div className="h-11 px-4 bg-[#161b22] border-b border-slate-800/80 flex items-center justify-between select-none">
          {/* Traffic Light Controls */}
          <div className="flex items-center space-x-2">
            <div className="w-3 h-3 rounded-full bg-[#ff5f56] border border-[#e0443e] flex items-center justify-center cursor-pointer group">
              <span className="opacity-0 group-hover:opacity-100 text-[8px] text-red-950 font-bold leading-none">×</span>
            </div>
            <div className="w-3 h-3 rounded-full bg-[#ffbd2e] border border-[#dea123] flex items-center justify-center cursor-pointer group">
              <span className="opacity-0 group-hover:opacity-100 text-[8px] text-amber-950 font-bold leading-none">−</span>
            </div>
            <div className="w-3 h-3 rounded-full bg-[#27c93f] border border-[#1aab29] flex items-center justify-center cursor-pointer group">
              <span className="opacity-0 group-hover:opacity-100 text-[8px] text-green-950 font-bold leading-none">+</span>
            </div>
          </div>

          {/* Centered Window Title */}
          <div className="flex items-center space-x-2 text-slate-400 text-xs font-medium">
            <TerminalIcon className="h-3.5 w-3.5 text-emerald-400" />
            <span className="text-slate-300 font-semibold tracking-wide">syed-api-qa-agent</span>
            <span className="text-slate-600">—</span>
            <span className="text-slate-400">autonomous live terminal</span>
          </div>

          {/* Status Badge & Clock */}
          <div className="flex items-center space-x-3 text-[11px]">
            <span className="text-slate-500 font-mono flex items-center space-x-1">
              <Clock className="h-3 w-3" />
              <span>{formatTime(elapsedSec)}</span>
            </span>
            <span
              className={`px-2 py-0.5 rounded font-semibold tracking-wider ${
                status === "COMPLETED"
                  ? "bg-emerald-500/20 text-emerald-400 border border-emerald-500/30"
                  : status === "FAILED"
                  ? "bg-rose-500/20 text-rose-400 border border-rose-500/30"
                  : "bg-blue-500/20 text-blue-400 border border-blue-500/30 animate-pulse"
              }`}
            >
              {status}
            </span>
          </div>
        </div>

        {/* Metadata Strip */}
        <div className="px-5 py-2.5 bg-[#0e131b] border-b border-slate-800/60 flex flex-wrap items-center justify-between gap-y-1.5 text-[11px] text-slate-400">
          <div className="flex items-center space-x-4">
            <div>
              <span className="text-slate-500">TARGET: </span>
              <span className="text-slate-200 font-medium">{openapiUrl || "Resolving..."}</span>
            </div>
            {targetBaseUrl && (
              <div>
                <span className="text-slate-500">BASE: </span>
                <span className="text-emerald-300">{targetBaseUrl}</span>
              </div>
            )}
          </div>
          <div className="flex items-center space-x-4">
            <div>
              <span className="text-slate-500">PROGRESS: </span>
              <span className="text-slate-200 font-bold">{progressPct}%</span>
            </div>
            <div>
              <span className="text-slate-500">TOTAL: </span>
              <span className="text-slate-200 font-bold">{totalTests}</span>
            </div>
          </div>
        </div>

        {/* Real-time Progress Bar */}
        <div className="w-full bg-[#161b22] h-1 overflow-hidden">
          <div
            className="bg-gradient-to-r from-indigo-500 via-emerald-400 to-teal-300 h-1 transition-all duration-300"
            style={{ width: `${progressPct}%` }}
          />
        </div>

        {/* Terminal Execution Body */}
        <div
          ref={scrollRef}
          onScroll={handleScroll}
          className="relative flex-1 p-5 h-[520px] overflow-y-auto space-y-1.5 bg-[#0d1117] text-[12px] font-mono leading-relaxed select-text"
        >
          {entries.length === 0 ? (
            <div className="h-full flex flex-col items-center justify-center text-slate-500 space-y-2">
              <TerminalIcon className="h-8 w-8 text-slate-700 animate-pulse" />
              <p>Waiting for live execution events from backend engine...</p>
            </div>
          ) : (
            entries.map((entry) => {
              const isExpanded = expandedId === entry.id;
              return (
                <div key={entry.id} className="group transition-colors hover:bg-slate-900/40 rounded px-1.5 py-0.5">
                  <div
                    className="flex items-start justify-between cursor-pointer"
                    onClick={() => entry.reason && setExpandedId(isExpanded ? null : entry.id)}
                  >
                    <div className="flex items-start space-x-2.5">
                      <span className="text-slate-600 text-[10px] select-none pt-0.5">[{entry.time}]</span>

                      {entry.type === "STEP_PASS" ? (
                        <span className="text-emerald-400 font-bold select-none">✓</span>
                      ) : entry.type === "STEP_FAIL" ? (
                        <span className="text-rose-400 font-bold select-none">✗</span>
                      ) : entry.type === "STEP_BLOCK" ? (
                        <span className="text-amber-400 font-bold select-none">○</span>
                      ) : entry.type === "STEP_START" ? (
                        <span className="text-blue-400 animate-pulse select-none">●</span>
                      ) : entry.type === "SUCCESS" ? (
                        <span className="text-emerald-400 font-bold select-none">✓</span>
                      ) : entry.type === "ERROR" ? (
                        <span className="text-rose-400 font-bold select-none">✗</span>
                      ) : (
                        <span className="text-slate-500 select-none">&gt;</span>
                      )}

                      <span
                        className={`whitespace-pre-wrap ${
                          entry.type === "STEP_PASS"
                            ? "text-emerald-300"
                            : entry.type === "STEP_FAIL"
                            ? "text-rose-300 font-semibold"
                            : entry.type === "STEP_BLOCK"
                            ? "text-amber-300/80"
                            : entry.type === "STEP_START"
                            ? "text-blue-300"
                            : entry.type === "SUCCESS"
                            ? "text-emerald-400 font-semibold"
                            : entry.type === "ERROR"
                            ? "text-rose-400 font-semibold"
                            : "text-slate-300"
                        }`}
                      >
                        {entry.text}
                      </span>
                    </div>

                    {entry.reason && (
                      <span className="text-[10px] text-slate-500 group-hover:text-slate-300 flex items-center space-x-1 shrink-0 ml-2">
                        <span>Details</span>
                        {isExpanded ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
                      </span>
                    )}
                  </div>

                  {/* Expandable Reason / Diagnostic Panel */}
                  {isExpanded && entry.reason && (
                    <div className="mt-2 ml-14 p-3 rounded bg-slate-950/80 border border-slate-800 text-[11px] text-slate-300 space-y-1">
                      <div className="text-rose-400 font-semibold flex items-center space-x-1.5">
                        <XCircle className="h-3.5 w-3.5" />
                        <span>Failure Diagnosis &amp; Root Cause:</span>
                      </div>
                      <p className="font-mono text-rose-200">{entry.reason}</p>
                    </div>
                  )}
                </div>
              );
            })
          )}

          {/* Stick-to-bottom Floating Button */}
          {!isScrolledToBottom && unreadCount > 0 && (
            <button
              onClick={scrollToBottom}
              className="absolute bottom-4 right-6 px-3 py-1.5 rounded-full bg-indigo-600 hover:bg-indigo-500 text-white font-sans text-xs font-semibold shadow-lg flex items-center space-x-1.5 transition-transform transform active:scale-95"
            >
              <ArrowDown className="h-3.5 w-3.5" />
              <span>↓ {unreadCount} new events</span>
            </button>
          )}
        </div>

        {/* Live Terminal Summary Bar */}
        <div className="px-5 py-3 bg-[#161b22] border-t border-slate-800/80 flex flex-wrap items-center justify-between gap-3 text-xs">
          <div className="flex items-center space-x-4">
            <div>
              <span className="text-slate-500">DISCOVERED: </span>
              <span className="text-white font-bold">{totalApis}</span>
            </div>
            <div>
              <span className="text-slate-500">PLANNED: </span>
              <span className="text-white font-bold">{totalTests}</span>
            </div>
            <div>
              <span className="text-slate-500">EXECUTED: </span>
              <span className="text-white font-bold">{executedCount}</span>
            </div>
          </div>

          <div className="flex items-center space-x-4">
            <div className="flex items-center space-x-1.5">
              <span className="h-2 w-2 rounded-full bg-emerald-400" />
              <span className="text-slate-400">PASS: </span>
              <span className="text-emerald-400 font-bold">{passed}</span>
            </div>
            <div className="flex items-center space-x-1.5">
              <span className="h-2 w-2 rounded-full bg-rose-500" />
              <span className="text-slate-400">FAIL: </span>
              <span className="text-rose-400 font-bold">{failed}</span>
            </div>
            <div className="flex items-center space-x-1.5">
              <span className="h-2 w-2 rounded-full bg-amber-400" />
              <span className="text-slate-400">BLOCKED: </span>
              <span className="text-amber-400 font-bold">{blocked}</span>
            </div>
          </div>

          {coverageScore !== null && (
            <div className="flex items-center space-x-1.5">
              <Sparkles className="h-3.5 w-3.5 text-indigo-400" />
              <span className="text-slate-400">COVERAGE: </span>
              <span className="text-indigo-300 font-bold">{coverageScore}%</span>
            </div>
          )}
        </div>
      </div>

      {/* Completion Banner */}
      {status === "COMPLETED" && (
        <div className="w-full max-w-6xl mt-4 p-5 rounded-xl bg-emerald-950/40 border border-emerald-500/30 flex flex-wrap items-center justify-between gap-4">
          <div className="flex items-center space-x-3">
            <CheckCircle2 className="h-6 w-6 text-emerald-400 shrink-0" />
            <div>
              <h3 className="text-sm font-bold text-white tracking-tight">Autonomous API QA Run Completed</h3>
              <p className="text-xs text-emerald-300/80">
                Executed {executedCount} tests in {durationMs} ms. Both interactive HTML audit report and vector PDF are verified and ready.
              </p>
            </div>
          </div>
          <div className="flex items-center space-x-3">
            <Link
              href={`/runs/${params.id}/report`}
              className="px-4 py-2 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-bold shadow transition-colors"
            >
              View Full Audit Report
            </Link>
            <a
              href={`${getApiBaseUrl()}/api/runs/${params.id}/report/pdf`}
              target="_blank"
              rel="noopener noreferrer"
              className="px-4 py-2 rounded-lg bg-slate-900 hover:bg-slate-800 text-emerald-300 border border-emerald-500/40 text-xs font-bold transition-colors flex items-center space-x-1.5"
            >
              <Download className="h-3.5 w-3.5" />
              <span>Download Official PDF</span>
            </a>
          </div>
        </div>
      )}
    </div>
  );
}
