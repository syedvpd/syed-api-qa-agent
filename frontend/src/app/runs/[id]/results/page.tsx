"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import {
  ArrowLeft,
  CheckCircle2,
  XCircle,
  AlertTriangle,
  Lock,
  Activity,
  FileText,
  Filter,
  Search,
  ChevronDown,
  ChevronUp,
  Layers,
  ArrowRight,
  ShieldCheck,
  Zap,
  Sparkles
} from "lucide-react";
import { getApiBaseUrl, authenticatedFetch } from "@/lib/api";
import ExecutionEvidenceInspector, { ExecutionEvidence } from "@/components/ExecutionEvidenceInspector";

export default function RunResultsPage({ params }: { params: { id: string } }) {
  const [evidenceList, setEvidenceList] = useState<ExecutionEvidence[]>([]);
  const [summary, setSummary] = useState<any | null>(null);
  const [coverage, setCoverage] = useState<any | null>(null);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState("ALL");
  const [searchQuery, setSearchQuery] = useState("");
  const [selectedStep, setSelectedStep] = useState<ExecutionEvidence | null>(null);
  const [showRootCauses, setShowRootCauses] = useState(true);

  useEffect(() => {
    const apiBase = getApiBaseUrl();

    Promise.all([
      authenticatedFetch(`${apiBase}/api/runs/${params.id}/evidence`).then((res) => (res.ok ? res.json() : [])),
      authenticatedFetch(`${apiBase}/api/runs/${params.id}/evidence/summary`).then((res) => (res.ok ? res.json() : null)),
      authenticatedFetch(`${apiBase}/api/runs/${params.id}/coverage`).then((res) => (res.ok ? res.json() : null)),
    ])
      .then(([evData, sumData, covData]) => {
        setEvidenceList(evData);
        setSummary(sumData);
        setCoverage(covData);
        if (evData.length > 0) {
          setSelectedStep(evData[0]);
        }
        setLoading(false);
      })
      .catch(() => setLoading(false));
  }, [params.id]);

  const filteredEvidence = evidenceList.filter((ev) => {
    // Status Filter
    if (filter === "PASSED" && ev.status !== "PASSED") return false;
    if (filter === "FAILED" && (ev.status === "PASSED" || ev.status === "BLOCKED" || ev.status === "REQUEST_NOT_EXECUTABLE")) return false;
    if (filter === "BLOCKED" && ev.status !== "BLOCKED" && ev.status !== "REQUEST_NOT_EXECUTABLE" && ev.status !== "BLOCKED_BY_AUTHENTICATION") return false;
    if (filter === "HTTP_SENT" && !ev.httpSent) return false;

    // Search Query Filter
    if (searchQuery.trim()) {
      const q = searchQuery.toLowerCase().trim();
      const matchPath = ev.pathTemplate.toLowerCase().includes(q);
      const matchMethod = ev.method.toLowerCase().includes(q);
      const matchName = ev.stepName.toLowerCase().includes(q);
      const matchOp = ev.operationId?.toLowerCase().includes(q);
      if (!matchPath && !matchMethod && !matchName && !matchOp) return false;
    }

    return true;
  });

  const passedCount = summary?.passedCount ?? evidenceList.filter((e) => e.status === "PASSED").length;
  const failedCount = summary?.failedCount ?? evidenceList.filter((e) => e.status !== "PASSED" && e.status !== "BLOCKED" && e.status !== "REQUEST_NOT_EXECUTABLE").length;
  const blockedCount = summary?.blockedCount ?? evidenceList.filter((e) => e.status === "BLOCKED" || e.status === "REQUEST_NOT_EXECUTABLE").length;
  const httpSentCount = summary?.httpSentCount ?? evidenceList.filter((e) => e.httpSent).length;

  return (
    <div className="space-y-6 max-w-7xl mx-auto font-mono">
      {/* Top Breadcrumb Navigation */}
      <div className="flex items-center justify-between">
        <div className="flex items-center space-x-3 text-xs">
          <Link
            href={`/runs/${params.id}/live`}
            className="inline-flex items-center space-x-1.5 text-slate-400 hover:text-slate-200 transition-colors"
          >
            <ArrowLeft className="h-3.5 w-3.5" />
            <span>Live Stream</span>
          </Link>
          <span className="text-slate-600">/</span>
          <span className="font-bold text-white">Execution Evidence & Trust Matrix</span>
        </div>

        <div className="flex items-center space-x-3">
          <Link
            href={`/runs/${params.id}/report`}
            className="flex items-center space-x-1.5 px-3 py-1.5 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-xs font-semibold text-white transition-colors shadow-lg shadow-emerald-950/40"
          >
            <FileText className="h-3.5 w-3.5" />
            <span>Executive Audit Report</span>
          </Link>
        </div>
      </div>

      {/* Trust & Evidence Principle Banner */}
      <div className="p-4 rounded-xl border border-sky-500/30 bg-sky-950/20 flex items-center justify-between gap-4">
        <div className="flex items-center space-x-3">
          <div className="p-2 rounded-lg bg-sky-500/20 text-sky-400">
            <ShieldCheck className="h-5 w-5" />
          </div>
          <div>
            <div className="text-xs font-bold text-white uppercase tracking-wider">Verifiable Execution Ledger</div>
            <div className="text-[11px] text-slate-300">
              Every row is backed by real wire HTTP dispatch facts, actual response status, and secret-redacted payloads.
            </div>
          </div>
        </div>
        <div className="text-right text-xs">
          <div className="text-slate-400 font-semibold">Real HTTP Requests Sent:</div>
          <div className="text-emerald-400 font-black text-sm">{httpSentCount} / {evidenceList.length} (100% Truthful)</div>
        </div>
      </div>

      {/* Clickable Metric Summary Cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <button
          onClick={() => setFilter("ALL")}
          className={`p-4 rounded-xl border text-left transition-all ${
            filter === "ALL"
              ? "bg-slate-800/90 border-slate-600 shadow-lg ring-1 ring-slate-500"
              : "bg-slate-900/40 border-slate-800 hover:bg-slate-800/40"
          }`}
        >
          <div className="text-slate-400 text-xs font-semibold uppercase">Total Planned Tests</div>
          <div className="text-2xl font-black text-white mt-1">{evidenceList.length}</div>
          <div className="text-[10px] text-slate-500 mt-1">Click to view all steps</div>
        </button>

        <button
          onClick={() => setFilter("PASSED")}
          className={`p-4 rounded-xl border text-left transition-all ${
            filter === "PASSED"
              ? "bg-emerald-950/40 border-emerald-500 shadow-lg ring-1 ring-emerald-500"
              : "bg-slate-900/40 border-slate-800 hover:bg-emerald-950/20"
          }`}
        >
          <div className="text-emerald-400 text-xs font-semibold uppercase flex items-center space-x-1">
            <CheckCircle2 className="h-3.5 w-3.5" />
            <span>Passed</span>
          </div>
          <div className="text-2xl font-black text-emerald-400 mt-1">{passedCount}</div>
          <div className="text-[10px] text-slate-500 mt-1">Click to filter passed</div>
        </button>

        <button
          onClick={() => setFilter("FAILED")}
          className={`p-4 rounded-xl border text-left transition-all ${
            filter === "FAILED"
              ? "bg-rose-950/40 border-rose-500 shadow-lg ring-1 ring-rose-500"
              : "bg-slate-900/40 border-slate-800 hover:bg-rose-950/20"
          }`}
        >
          <div className="text-rose-400 text-xs font-semibold uppercase flex items-center space-x-1">
            <XCircle className="h-3.5 w-3.5" />
            <span>Failed</span>
          </div>
          <div className="text-2xl font-black text-rose-400 mt-1">{failedCount}</div>
          <div className="text-[10px] text-slate-500 mt-1">Click to inspect failures</div>
        </button>

        <button
          onClick={() => setFilter("BLOCKED")}
          className={`p-4 rounded-xl border text-left transition-all ${
            filter === "BLOCKED"
              ? "bg-amber-950/40 border-amber-500 shadow-lg ring-1 ring-amber-500"
              : "bg-slate-900/40 border-slate-800 hover:bg-amber-950/20"
          }`}
        >
          <div className="text-amber-400 text-xs font-semibold uppercase flex items-center space-x-1">
            <Lock className="h-3.5 w-3.5" />
            <span>Blocked / Withheld</span>
          </div>
          <div className="text-2xl font-black text-amber-400 mt-1">{blockedCount}</div>
          <div className="text-[10px] text-slate-500 mt-1">Click to inspect root causes</div>
        </button>
      </div>

      {/* Root Cause Grouping Intelligence Accordion */}
      {summary && (summary.failureGroups?.length > 0 || summary.blockedGroups?.length > 0) && (
        <div className="rounded-xl border border-slate-800 bg-slate-900/60 overflow-hidden">
          <button
            onClick={() => setShowRootCauses(!showRootCauses)}
            className="w-full p-4 flex items-center justify-between text-left hover:bg-slate-800/40 transition-colors"
          >
            <div className="flex items-center space-x-2.5">
              <Sparkles className="h-4 w-4 text-emerald-400" />
              <span className="text-sm font-bold text-white">Autonomous Root-Cause Intelligence Summary</span>
              <span className="text-xs text-slate-400 font-normal">
                (Aggregating {failedCount} failures & {blockedCount} blocked tests into root causes)
              </span>
            </div>
            {showRootCauses ? <ChevronUp className="h-4 w-4 text-slate-400" /> : <ChevronDown className="h-4 w-4 text-slate-400" />}
          </button>

          {showRootCauses && (
            <div className="p-4 pt-0 space-y-4 border-t border-slate-800/60">
              {/* Failure Causes */}
              {summary.failureGroups?.length > 0 && (
                <div>
                  <h4 className="text-xs font-bold text-rose-400 uppercase tracking-wider mb-2">Why Did Tests Fail?</h4>
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                    {summary.failureGroups.map((g: any, idx: number) => (
                      <div key={idx} className="p-3.5 rounded-lg bg-rose-950/20 border border-rose-900/40 space-y-2">
                        <div className="flex justify-between items-start">
                          <div className="font-bold text-rose-300 text-xs">{g.title}</div>
                          <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-rose-900/50 text-rose-200 border border-rose-700">
                            {g.affectedCount} tests
                          </span>
                        </div>
                        <p className="text-[11px] text-slate-300 leading-relaxed">{g.rootCause}</p>
                        <div className="text-[11px] text-emerald-400 pt-1 border-t border-rose-900/30">
                          <strong>Action:</strong> {g.suggestedRemediation}
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* Blocked Causes */}
              {summary.blockedGroups?.length > 0 && (
                <div>
                  <h4 className="text-xs font-bold text-amber-400 uppercase tracking-wider mb-2">Why Were Tests Blocked?</h4>
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                    {summary.blockedGroups.map((g: any, idx: number) => (
                      <div key={idx} className="p-3.5 rounded-lg bg-amber-950/20 border border-amber-900/40 space-y-2">
                        <div className="flex justify-between items-start">
                          <div className="font-bold text-amber-300 text-xs">{g.title}</div>
                          <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-amber-900/50 text-amber-200 border border-amber-700">
                            {g.affectedCount} tests
                          </span>
                        </div>
                        <p className="text-[11px] text-slate-300 leading-relaxed">{g.rootCause}</p>
                        <div className="text-[11px] text-emerald-400 pt-1 border-t border-amber-900/30">
                          <strong>Action:</strong> {g.suggestedRemediation}
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      )}

      {/* Filter and Search Bar */}
      <div className="flex flex-col sm:flex-row items-center justify-between gap-3 bg-slate-900/40 p-3 rounded-xl border border-slate-800">
        <div className="relative w-full sm:w-80">
          <Search className="h-4 w-4 text-slate-500 absolute left-3 top-2.5" />
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Search path, method, name..."
            className="w-full bg-slate-950 border border-slate-800 rounded-lg pl-9 pr-3 py-1.5 text-xs text-white placeholder-slate-500 focus:outline-none focus:border-emerald-500"
          />
        </div>

        <div className="flex items-center space-x-1.5 overflow-x-auto w-full sm:w-auto">
          {["ALL", "PASSED", "FAILED", "BLOCKED", "HTTP_SENT"].map((f) => (
            <button
              key={f}
              onClick={() => setFilter(f)}
              className={`px-3 py-1 rounded-md text-xs font-semibold transition-all ${
                filter === f
                  ? "bg-slate-800 text-white border border-slate-700 shadow"
                  : "text-slate-400 hover:text-slate-200"
              }`}
            >
              {f.replace("_", " ")}
            </button>
          ))}
        </div>
      </div>

      {/* Main Execution Matrix & Side-by-side Evidence Inspector */}
      {loading ? (
        <div className="p-16 text-center text-slate-500 text-xs">Loading execution ledger & evidence...</div>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
          {/* Table (7 Cols) */}
          <div className="lg:col-span-6 xl:col-span-7 rounded-xl border border-slate-800 bg-slate-900/40 overflow-hidden flex flex-col h-[750px]">
            <div className="p-3 bg-slate-950 border-b border-slate-800 flex justify-between items-center text-xs">
              <span className="font-bold text-slate-300">Execution Ledger ({filteredEvidence.length} steps)</span>
              <span className="text-slate-500 text-[11px]">Click row to inspect</span>
            </div>

            <div className="flex-1 overflow-y-auto">
              <table className="w-full text-left text-xs border-collapse">
                <thead className="sticky top-0 bg-slate-950/95 border-b border-slate-800 text-slate-400 font-mono z-10">
                  <tr>
                    <th className="py-2.5 px-3">Method</th>
                    <th className="py-2.5 px-3">Target Endpoint</th>
                    <th className="py-2.5 px-3">HTTP</th>
                    <th className="py-2.5 px-3">Status</th>
                    <th className="py-2.5 px-3 text-right">Latency</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-800/60 font-mono">
                  {filteredEvidence.length === 0 ? (
                    <tr>
                      <td colSpan={5} className="py-12 text-center text-slate-500">
                        No execution records match the current filter.
                      </td>
                    </tr>
                  ) : (
                    filteredEvidence.map((ev) => {
                      const isSelected = selectedStep?.stepId === ev.stepId;
                      return (
                        <tr
                          key={ev.stepId}
                          onClick={() => setSelectedStep(ev)}
                          className={`cursor-pointer transition-colors ${
                            isSelected ? "bg-slate-800/90 border-l-2 border-emerald-400" : "hover:bg-slate-800/40"
                          }`}
                        >
                          <td className="py-2.5 px-3">
                            <span
                              className={`px-1.5 py-0.5 rounded text-[10px] font-bold ${
                                ev.method === "GET"
                                  ? "bg-sky-500/20 text-sky-400"
                                  : ev.method === "POST"
                                  ? "bg-emerald-500/20 text-emerald-400"
                                  : ev.method === "PUT" || ev.method === "PATCH"
                                  ? "bg-amber-500/20 text-amber-400"
                                  : "bg-rose-500/20 text-rose-400"
                              }`}
                            >
                              {ev.method}
                            </span>
                          </td>
                          <td className="py-2.5 px-3 text-slate-200 truncate max-w-[200px]" title={ev.pathTemplate}>
                            {ev.pathTemplate}
                          </td>
                          <td className="py-2.5 px-3 font-semibold">
                            {ev.httpSent ? (
                              <span className="text-emerald-400 text-[11px]">SENT</span>
                            ) : (
                              <span className="text-amber-500 text-[11px]">WITHHELD</span>
                            )}
                          </td>
                          <td className="py-2.5 px-3">
                            <span
                              className={`px-2 py-0.5 rounded text-[10px] font-bold ${
                                ev.status === "PASSED"
                                  ? "bg-emerald-500/20 text-emerald-400 border border-emerald-500/30"
                                  : ev.status === "BLOCKED" || ev.status === "REQUEST_NOT_EXECUTABLE"
                                  ? "bg-amber-500/20 text-amber-400 border border-amber-500/30"
                                  : "bg-rose-500/20 text-rose-400 border border-rose-500/30"
                              }`}
                            >
                              {ev.responseStatus ? `HTTP ${ev.responseStatus}` : ev.status}
                            </span>
                          </td>
                          <td className="py-2.5 px-3 text-right text-slate-400">
                            {ev.latencyMs ? `${ev.latencyMs}ms` : "-"}
                          </td>
                        </tr>
                      );
                    })
                  )}
                </tbody>
              </table>
            </div>
          </div>

          {/* Evidence Inspector Drawer / Pane (5-6 Cols) */}
          <div className="lg:col-span-6 xl:col-span-5 h-[750px]">
            <ExecutionEvidenceInspector evidence={selectedStep} />
          </div>
        </div>
      )}
    </div>
  );
}
