"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { ArrowLeft, CheckCircle2, XCircle, AlertTriangle, ShieldCheck, Activity, Terminal, FileText, ArrowRight } from "lucide-react";

interface LogEvent {
  id: string;
  type: string;
  data: any;
  time: string;
}

export default function LiveRunPage({ params }: { params: { id: string } }) {
  const [status, setStatus] = useState("CONNECTING");
  const [totalApis, setTotalApis] = useState(0);
  const [totalTests, setTotalTests] = useState(0);
  const [passed, setPassed] = useState(0);
  const [failed, setFailed] = useState(0);
  const [blocked, setBlocked] = useState(0);
  const [logs, setLogs] = useState<LogEvent[]>([]);
  const [currentStep, setCurrentStep] = useState<string>("Initializing execution engine...");

  useEffect(() => {
    // Connect to backend Server-Sent Events (SSE) stream
    const eventSource = new EventSource(`http://localhost:8080/api/runs/${params.id}/events`);

    eventSource.addEventListener("CONNECTED", (e: MessageEvent) => {
      setStatus("STREAMING");
      addLog("CONNECTED", "Subscribed to live backend execution feed.");
    });

    eventSource.addEventListener("DISCOVERY_STARTED", (e: MessageEvent) => {
      const data = JSON.parse(e.data);
      setStatus("DISCOVERING");
      setCurrentStep(`Fetching & parsing specification from ${data.openapiUrl}`);
      addLog("DISCOVERY", `Initiated OpenAPI discovery for ${data.openapiUrl}`);
    });

    eventSource.addEventListener("API_DISCOVERED", (e: MessageEvent) => {
      const data = JSON.parse(e.data);
      setTotalApis((prev) => prev + 1);
      addLog("DISCOVERY", `Discovered route: ${data.method} ${data.path}`);
    });

    eventSource.addEventListener("PLANNING_STARTED", (e: MessageEvent) => {
      const data = JSON.parse(e.data);
      setStatus("PLANNING");
      setCurrentStep(`Formulating dependency graph across ${data.endpointsCount} operations`);
      addLog("PLANNING", `Building dependency graph and topological test plan`);
    });

    eventSource.addEventListener("PLANNING_COMPLETED", (e: MessageEvent) => {
      const data = JSON.parse(e.data);
      setStatus("EXECUTING");
      setTotalTests(data.stepsCount);
      setCurrentStep(`Executing ${data.stepsCount} planned operations`);
      addLog("PLANNING", `Formulated ${data.casesCount} scenarios containing ${data.stepsCount} steps`);
    });

    eventSource.addEventListener("TEST_STARTED", (e: MessageEvent) => {
      const data = JSON.parse(e.data);
      setCurrentStep(`Executing: ${data.name} (${data.method})`);
      addLog("EXECUTION", `Dispatching ${data.name}`);
    });

    eventSource.addEventListener("TEST_COMPLETED", (e: MessageEvent) => {
      setPassed((prev) => prev + 1);
      addLog("PASS", `Step passed all contract assertions`);
    });

    eventSource.addEventListener("TEST_FAILED", (e: MessageEvent) => {
      const data = JSON.parse(e.data);
      setFailed((prev) => prev + 1);
      addLog("FAIL", `Step failed: ${data.status} ${data.reason || ""}`);
    });

    eventSource.addEventListener("TEST_BLOCKED", (e: MessageEvent) => {
      const data = JSON.parse(e.data);
      setBlocked((prev) => prev + 1);
      addLog("BLOCK", `Step isolated & blocked: ${data.reason}`);
    });

    eventSource.addEventListener("RUN_COMPLETED", (e: MessageEvent) => {
      const data = JSON.parse(e.data);
      setStatus("COMPLETED");
      setCurrentStep("Test execution completed successfully.");
      addLog("COMPLETED", `Run completed in ${data.durationMs} ms. Report generated.`);
    });

    eventSource.addEventListener("RUN_FAILED", (e: MessageEvent) => {
      const data = JSON.parse(e.data);
      setStatus("FAILED");
      setCurrentStep(`Run failed: ${data.error}`);
      addLog("ERROR", `Fatal error: ${data.error}`);
    });

    eventSource.onerror = () => {
      // Reconnect resilience: EventSource will auto-retry in the background
    };

    return () => {
      eventSource.close();
    };
  }, [params.id]);

  const addLog = (type: string, msg: string) => {
    setLogs((prev) => [
      {
        id: Math.random().toString(),
        type,
        data: msg,
        time: new Date().toLocaleTimeString(),
      },
      ...prev.slice(0, 99),
    ]);
  };

  return (
    <div className="space-y-6">
      {/* Top Bar */}
      <div className="flex items-center justify-between">
        <div className="flex items-center space-x-3">
          <Link
            href="/dashboard"
            className="inline-flex items-center space-x-1.5 text-xs text-slate-400 hover:text-slate-200"
          >
            <ArrowLeft className="h-3.5 w-3.5" />
            <span>Dashboard</span>
          </Link>
          <span className="text-slate-600">/</span>
          <span className="text-xs font-mono text-slate-300">Run {params.id}</span>
        </div>

        <div className="flex items-center space-x-3">
          <Link
            href={`/runs/${params.id}/results`}
            className="flex items-center space-x-1.5 px-3 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-xs font-semibold text-slate-200 border border-slate-700"
          >
            <Activity className="h-3.5 w-3.5 text-emerald-400" />
            <span>View Results Matrix</span>
          </Link>
          <Link
            href={`/runs/${params.id}/report`}
            className="flex items-center space-x-1.5 px-3 py-1.5 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-xs font-semibold text-white shadow-sm"
          >
            <FileText className="h-3.5 w-3.5" />
            <span>Audit Report</span>
          </Link>
        </div>
      </div>

      {/* Execution Status Header */}
      <div className="p-6 rounded-xl bg-slate-900/60 border border-slate-800 space-y-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center space-x-3">
            <div
              className={`h-3 w-3 rounded-full ${
                status === "COMPLETED"
                  ? "bg-emerald-400"
                  : status === "FAILED"
                  ? "bg-rose-500"
                  : "bg-emerald-400 animate-ping"
              }`}
            />
            <h1 className="text-lg font-bold text-white tracking-tight">Autonomous Execution Stream</h1>
          </div>
          <span className="text-xs font-semibold px-2.5 py-1 rounded bg-slate-800 text-slate-300 border border-slate-700 font-mono">
            {status}
          </span>
        </div>

        <div className="p-3 rounded-lg bg-slate-950/80 border border-slate-800 text-xs font-mono text-emerald-400 flex items-center space-x-2">
          <Terminal className="h-4 w-4 shrink-0 text-slate-500" />
          <span>{currentStep}</span>
        </div>
      </div>

      {/* Metrics Row */}
      <div className="grid grid-cols-2 sm:grid-cols-5 gap-3">
        <div className="p-4 rounded-lg bg-slate-900/50 border border-slate-800">
          <span className="text-xs text-slate-400">APIs Discovered</span>
          <p className="text-xl font-bold text-white mt-1">{totalApis}</p>
        </div>
        <div className="p-4 rounded-lg bg-slate-900/50 border border-slate-800">
          <span className="text-xs text-slate-400">Total Planned</span>
          <p className="text-xl font-bold text-white mt-1">{totalTests}</p>
        </div>
        <div className="p-4 rounded-lg bg-slate-900/50 border border-slate-800">
          <span className="text-xs text-emerald-400">Passed</span>
          <p className="text-xl font-bold text-emerald-400 mt-1">{passed}</p>
        </div>
        <div className="p-4 rounded-lg bg-slate-900/50 border border-slate-800">
          <span className="text-xs text-rose-400">Failed</span>
          <p className="text-xl font-bold text-rose-400 mt-1">{failed}</p>
        </div>
        <div className="p-4 rounded-lg bg-slate-900/50 border border-slate-800">
          <span className="text-xs text-amber-400">Blocked</span>
          <p className="text-xl font-bold text-amber-400 mt-1">{blocked}</p>
        </div>
      </div>

      {/* Live Event Terminal */}
      <div className="rounded-xl border border-slate-800 bg-slate-950 overflow-hidden font-mono text-xs">
        <div className="px-4 py-2.5 bg-slate-900/80 border-b border-slate-800 flex items-center justify-between text-slate-400">
          <span>REAL-TIME EXECUTION LOG</span>
          <span>DISCONNECT-TOLERANT SSE FEED</span>
        </div>
        <div className="p-4 space-y-2 max-h-[380px] overflow-y-auto">
          {logs.length === 0 ? (
            <div className="text-slate-600">Waiting for first execution event...</div>
          ) : (
            logs.map((l) => (
              <div key={l.id} className="flex items-start space-x-2">
                <span className="text-slate-500">[{l.time}]</span>
                <span
                  className={`font-semibold ${
                    l.type === "PASS"
                      ? "text-emerald-400"
                      : l.type === "FAIL" || l.type === "ERROR"
                      ? "text-rose-400"
                      : l.type === "BLOCK"
                      ? "text-amber-400"
                      : "text-blue-400"
                  }`}
                >
                  [{l.type}]
                </span>
                <span className="text-slate-300">{l.data}</span>
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );
}
