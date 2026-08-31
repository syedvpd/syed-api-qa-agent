"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { ArrowLeft, CheckCircle2, XCircle, AlertTriangle, ShieldAlert, Activity, FileText, ArrowRight } from "lucide-react";

export default function RunResultsPage({ params }: { params: { id: string } }) {
  const [cases, setCases] = useState<any[]>([]);
  const [coverage, setCoverage] = useState<any | null>(null);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState("ALL");
  const [selectedStep, setSelectedStep] = useState<any | null>(null);

  useEffect(() => {
    fetch(`http://localhost:8080/api/runs/${params.id}/cases`)
      .then((res) => (res.ok ? res.json() : []))
      .then((data) => {
        setCases(data);
        setLoading(false);
      })
      .catch(() => setLoading(false));

    fetch(`http://localhost:8080/api/runs/${params.id}/coverage`)
      .then((res) => (res.ok ? res.json() : null))
      .then((data) => setCoverage(data))
      .catch(() => {});
  }, [params.id]);

  const allSteps: any[] = [];
  cases.forEach((c) => {
    if (c.steps) {
      c.steps.forEach((s: any) => {
        allSteps.push({ ...s, caseName: c.case.name, scenarioType: c.case.scenarioType });
      });
    }
  });

  const filteredSteps = allSteps.filter((s) => {
    if (filter === "ALL") return true;
    if (filter === "CRUD_WORKFLOW" || filter === "NEGATIVE_ROBUSTNESS" || filter === "PAGINATION_AND_FILTERING") {
      return s.scenarioType === filter;
    }
    return s.status === filter;
  });

  return (
    <div className="space-y-6">
      {/* Navigation */}
      <div className="flex items-center justify-between">
        <div className="flex items-center space-x-3">
          <Link
            href={`/runs/${params.id}/live`}
            className="inline-flex items-center space-x-1.5 text-xs text-slate-400 hover:text-slate-200"
          >
            <ArrowLeft className="h-3.5 w-3.5" />
            <span>Live Stream</span>
          </Link>
          <span className="text-slate-600">/</span>
          <span className="text-xs font-mono text-slate-300">Execution Results</span>
        </div>

        <Link
          href={`/runs/${params.id}/report`}
          className="flex items-center space-x-1.5 px-3 py-1.5 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-xs font-semibold text-white"
        >
          <FileText className="h-3.5 w-3.5" />
          <span>Audit Report</span>
        </Link>
      </div>

      {coverage && coverage.score !== null && (
        <div className="p-4 rounded-xl border border-emerald-500/30 bg-emerald-950/10 flex flex-wrap items-center justify-between gap-4">
          <div className="flex items-center space-x-3">
            <div className="p-2 rounded-lg bg-emerald-500/20 text-emerald-400">
              <Activity className="h-5 w-5" />
            </div>
            <div>
              <div className="text-xs text-slate-400 uppercase tracking-wider font-semibold">API QA Coverage Score</div>
              <div className="text-2xl font-black text-emerald-400 font-mono">{coverage.score}%</div>
            </div>
          </div>
          <div className="flex items-center gap-6 text-xs font-mono text-slate-300">
            <div>Total: <span className="text-white font-bold">{coverage.endpoints?.length || 0}</span></div>
            <div>Full: <span className="text-emerald-400 font-bold">{coverage.endpoints?.filter((e: any) => e.classification === 'FULL').length || 0}</span></div>
            <div>Partial: <span className="text-sky-400 font-bold">{coverage.endpoints?.filter((e: any) => e.classification === 'PARTIAL').length || 0}</span></div>
            <div>Blocked: <span className="text-amber-400 font-bold">{coverage.endpoints?.filter((e: any) => e.classification === 'BLOCKED').length || 0}</span></div>
          </div>
        </div>
      )}

      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <h1 className="text-xl font-bold text-white tracking-tight">Endpoint Execution Matrix</h1>
          <p className="text-xs text-slate-400">Inspecting all planned steps, assertions, and responses.</p>
        </div>

        {/* Filter Badges */}
        <div className="flex items-center flex-wrap gap-2">
          {["ALL", "PASSED", "FAILED", "BLOCKED", "CRUD_WORKFLOW", "PAGINATION_AND_FILTERING", "NEGATIVE_ROBUSTNESS"].map((f) => (
            <button
              key={f}
              onClick={() => setFilter(f)}
              className={`px-2.5 py-1 rounded-md text-xs font-semibold transition-all ${
                filter === f
                  ? "bg-slate-800 text-white border border-slate-700"
                  : "text-slate-400 hover:text-slate-200"
              }`}
            >
              {f.replace("_", " ")}
            </button>
          ))}
        </div>
      </div>

      {loading ? (
        <div className="p-12 text-center text-slate-500 text-xs font-mono">Loading execution matrix...</div>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          {/* Table */}
          <div className="lg:col-span-2 rounded-xl border border-slate-800 bg-slate-900/40 overflow-hidden">
            <table className="w-full text-left text-xs border-collapse">
              <thead>
                <tr className="bg-slate-950/80 border-b border-slate-800 text-slate-400 font-mono">
                  <th className="py-3 px-4">Method</th>
                  <th className="py-3 px-4">Target Path</th>
                  <th className="py-3 px-4">Expected</th>
                  <th className="py-3 px-4">Status</th>
                  <th className="py-3 px-4">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800/60 font-mono">
                {filteredSteps.length === 0 ? (
                  <tr>
                    <td colSpan={5} className="py-8 text-center text-slate-500">
                      No steps found matching filter.
                    </td>
                  </tr>
                ) : (
                  filteredSteps.map((step) => (
                    <tr
                      key={step.id}
                      onClick={() => setSelectedStep(step)}
                      className="hover:bg-slate-800/40 cursor-pointer transition-colors"
                    >
                      <td className="py-3 px-4">
                        <span className="px-2 py-0.5 rounded text-xs font-bold bg-slate-800 text-sky-400 border border-slate-700">
                          {step.method}
                        </span>
                      </td>
                      <td className="py-3 px-4 text-slate-200 truncate max-w-xs">{step.pathTemplate}</td>
                      <td className="py-3 px-4 text-slate-400">{step.expectedStatus || "-"}</td>
                      <td className="py-3 px-4">
                        <span
                          className={`px-2 py-0.5 rounded text-xs font-semibold ${
                            step.status === "PASSED"
                              ? "bg-emerald-500/20 text-emerald-400 border border-emerald-500/30"
                              : step.status === "BLOCKED"
                              ? "bg-amber-500/20 text-amber-400 border border-amber-500/30"
                              : "bg-rose-500/20 text-rose-400 border border-rose-500/30"
                          }`}
                        >
                          {step.status}
                        </span>
                      </td>
                      <td className="py-3 px-4">
                        <button className="text-emerald-400 hover:text-emerald-300">Inspect &rarr;</button>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>

          {/* Evidence Inspector Drawer */}
          <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-5 space-y-4">
            <h3 className="text-sm font-semibold text-white border-b border-slate-800 pb-2">Step Evidence Inspector</h3>
            {selectedStep ? (
              <div className="space-y-3 text-xs font-mono">
                <div>
                  <span className="text-slate-500">Step Name:</span>
                  <p className="text-slate-200 font-semibold">{selectedStep.name}</p>
                </div>
                <div>
                  <span className="text-slate-500">Scenario:</span>
                  <p className="text-slate-300">{selectedStep.caseName}</p>
                </div>
                <div>
                  <span className="text-slate-500">Resolved Target URL:</span>
                  <p className="text-emerald-400 break-all">{selectedStep.resolvedUrl || selectedStep.pathTemplate}</p>
                </div>
                {selectedStep.failureReason && (
                  <div className="p-2.5 rounded bg-rose-950/40 border border-rose-800/50 text-rose-300">
                    <strong>Reason:</strong> {selectedStep.failureReason}
                  </div>
                )}
                {selectedStep.requestBody && (
                  <div>
                    <span className="text-slate-500">Synthesized Request Body:</span>
                    <pre className="mt-1 p-2 rounded bg-slate-950 border border-slate-800 text-slate-300 max-h-40 overflow-auto">
                      {selectedStep.requestBody}
                    </pre>
                  </div>
                )}
              </div>
            ) : (
              <div className="py-12 text-center text-slate-500 text-xs">
                Select any step from the table to inspect details.
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
