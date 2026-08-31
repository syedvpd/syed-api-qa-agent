"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import {
  ArrowLeft,
  TrendingDown,
  TrendingUp,
  AlertTriangle,
  CheckCircle2,
  XCircle,
  PlusCircle,
  MinusCircle,
  Clock,
  Shuffle,
  Loader2,
  FileText
} from "lucide-react";

interface RegressionFinding {
  id: string;
  findingType: string;
  severity: "CRITICAL" | "HIGH" | "MEDIUM" | "LOW" | "INFO";
  httpMethod: string;
  endpointPath: string;
  baselineValue: string;
  currentValue: string;
  description: string;
  createdAt: string;
}

interface RegressionReport {
  currentRunId: string;
  baselineRunId: string;
  status: string;
  p50DeltaPercent: number;
  p90DeltaPercent: number;
  p95DeltaPercent: number;
  p99DeltaPercent: number;
  currentP50Ms: number;
  currentP90Ms: number;
  currentP95Ms: number;
  currentP99Ms: number;
  baselineP50Ms: number;
  baselineP90Ms: number;
  baselineP95Ms: number;
  baselineP99Ms: number;
  newFailuresCount: number;
  fixedFailuresCount: number;
  addedApisCount: number;
  removedApisCount: number;
  summary: string;
  findings: RegressionFinding[];
}

interface BaselineCandidate {
  id: string;
  openapiUrl: string;
  status: string;
  createdAt: string;
}

export default function RunRegressionPage({ params }: { params: { id: string } }) {
  const [report, setReport] = useState<RegressionReport | null>(null);
  const [findings, setFindings] = useState<RegressionFinding[]>([]);
  const [baselines, setBaselines] = useState<BaselineCandidate[]>([]);
  const [selectedBaseline, setSelectedBaseline] = useState<string>("");
  const [loading, setLoading] = useState(true);
  const [comparing, setComparing] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const getApiBase = () => {
    if (process.env.NEXT_PUBLIC_API_URL) {
      return process.env.NEXT_PUBLIC_API_URL.replace(/\/$/, "");
    }
    return "http://localhost:8080";
  };

  useEffect(() => {
    const apiBase = getApiBase();
    setLoading(true);
    setErrorMessage(null);

    Promise.all([
      fetch(`${apiBase}/api/runs/${params.id}/regression`).then((r) => r.json()),
      fetch(`${apiBase}/api/runs/${params.id}/baselines`).then((r) => r.ok ? r.json() : [])
    ])
      .then(([regData, candidateList]) => {
        if (regData.regressionSummary && regData.regressionSummary !== "{}") {
          try {
            const parsed = typeof regData.regressionSummary === "string"
              ? JSON.parse(regData.regressionSummary)
              : regData.regressionSummary;
            setReport(parsed);
            if (regData.baselineRunId) {
              setSelectedBaseline(regData.baselineRunId);
            }
          } catch (e) {
            console.error("Failed to parse regressionSummaryJson", e);
          }
        }
        if (regData.findings) {
          setFindings(regData.findings);
        }
        setBaselines(candidateList || []);
        setLoading(false);
      })
      .catch((err) => {
        setErrorMessage(err.message || "Failed to load regression analytics.");
        setLoading(false);
      });
  }, [params.id]);

  const handleCompare = async () => {
    if (!selectedBaseline) return;
    const apiBase = getApiBase();
    setComparing(true);
    setErrorMessage(null);

    try {
      const res = await fetch(`${apiBase}/api/runs/${params.id}/regression/compare?baselineId=${selectedBaseline}`, {
        method: "POST"
      });
      if (!res.ok) {
        throw new Error(`Comparison failed with HTTP ${res.status}`);
      }
      const data = await res.json();
      setReport(data.report);
      setFindings(data.findings || []);
    } catch (err: any) {
      setErrorMessage(err.message || "Failed to execute custom comparison.");
    } finally {
      setComparing(false);
    }
  };

  const getSeverityBadge = (severity: string) => {
    switch (severity) {
      case "CRITICAL":
        return <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-rose-950/80 text-rose-300 border border-rose-800/80">CRITICAL</span>;
      case "HIGH":
        return <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-orange-950/80 text-orange-300 border border-orange-800/80">HIGH</span>;
      case "MEDIUM":
        return <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-amber-950/80 text-amber-300 border border-amber-800/80">MEDIUM</span>;
      case "LOW":
        return <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-blue-950/80 text-blue-300 border border-blue-800/80">LOW</span>;
      default:
        return <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-slate-800 text-slate-300 border border-slate-700">INFO</span>;
    }
  };

  return (
    <div className="space-y-6">
      {/* Header */}
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
          <span className="text-xs font-mono text-slate-300">Historical Regression Intelligence</span>
        </div>

        {/* Baseline Selector */}
        <div className="flex items-center space-x-2">
          <span className="text-xs text-slate-400">Baseline:</span>
          <select
            value={selectedBaseline}
            onChange={(e) => setSelectedBaseline(e.target.value)}
            className="bg-slate-900 border border-slate-800 rounded-lg px-2.5 py-1 text-xs text-slate-200 focus:outline-none focus:ring-1 focus:ring-indigo-500"
          >
            <option value="">Auto-detected Baseline</option>
            {baselines.map((b) => (
              <option key={b.id} value={b.id}>
                {b.id.substring(0, 8)}... ({new Date(b.createdAt).toLocaleDateString()})
              </option>
            ))}
          </select>
          <button
            onClick={handleCompare}
            disabled={comparing || !selectedBaseline}
            className="flex items-center space-x-1.5 px-3 py-1 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-40 text-xs font-semibold text-white rounded-lg transition-colors"
          >
            {comparing ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Shuffle className="h-3.5 w-3.5" />}
            <span>Compare</span>
          </button>
        </div>
      </div>

      {errorMessage && (
        <div className="p-3.5 rounded-lg bg-rose-950/40 border border-rose-800/60 text-rose-300 text-xs flex items-center space-x-2">
          <AlertTriangle className="h-4 w-4 shrink-0 text-rose-400" />
          <span>{errorMessage}</span>
        </div>
      )}

      {loading ? (
        <div className="p-16 text-center space-y-3 bg-slate-900/20 rounded-xl border border-slate-800">
          <Loader2 className="h-6 w-6 text-indigo-400 animate-spin mx-auto" />
          <div className="text-slate-400 text-xs font-mono">Analyzing historical runs & contract drift...</div>
        </div>
      ) : report ? (
        <div className="space-y-6">
          {/* Summary Box */}
          <div className="p-4 rounded-xl bg-slate-900/60 border border-slate-800/80 space-y-2">
            <div className="flex items-center justify-between">
              <span className="text-xs font-bold text-slate-300 uppercase tracking-wider">
                Overall Regression Status:{" "}
                <span
                  className={
                    report.status.includes("CRITICAL")
                      ? "text-rose-400"
                      : report.status.includes("HIGH")
                      ? "text-orange-400"
                      : report.status.includes("NO_REGRESSION") || report.status.includes("ESTABLISHED")
                      ? "text-emerald-400"
                      : "text-amber-400"
                  }
                >
                  {report.status}
                </span>
              </span>
              <span className="text-[11px] font-mono text-slate-500">
                Compared against Run ID: {report.baselineRunId ? report.baselineRunId.substring(0, 8) + "..." : "Initial Baseline"}
              </span>
            </div>
            <p className="text-xs text-slate-400">{report.summary}</p>
          </div>

          {/* KPI Delta Cards */}
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
            <div className="p-4 rounded-xl bg-slate-900/40 border border-slate-800">
              <div className="flex items-center justify-between text-slate-400 text-xs">
                <span>New Failures</span>
                <XCircle className="h-4 w-4 text-rose-400" />
              </div>
              <div className="mt-2 text-2xl font-bold text-slate-100">{report.newFailuresCount || 0}</div>
              <div className="text-[11px] text-slate-500 mt-1">Endpoints degraded from 2xx to fail</div>
            </div>

            <div className="p-4 rounded-xl bg-slate-900/40 border border-slate-800">
              <div className="flex items-center justify-between text-slate-400 text-xs">
                <span>Fixed Failures</span>
                <CheckCircle2 className="h-4 w-4 text-emerald-400" />
              </div>
              <div className="mt-2 text-2xl font-bold text-emerald-400">{report.fixedFailuresCount || 0}</div>
              <div className="text-[11px] text-slate-500 mt-1">Prior broken tests now passing</div>
            </div>

            <div className="p-4 rounded-xl bg-slate-900/40 border border-slate-800">
              <div className="flex items-center justify-between text-slate-400 text-xs">
                <span>P95 SLA Delta</span>
                <Clock className="h-4 w-4 text-indigo-400" />
              </div>
              <div className={`mt-2 text-2xl font-bold ${report.p95DeltaPercent > 20 ? "text-rose-400" : "text-slate-100"}`}>
                {report.p95DeltaPercent > 0 ? `+${report.p95DeltaPercent}%` : `${report.p95DeltaPercent}%`}
              </div>
              <div className="text-[11px] text-slate-500 mt-1">
                {report.currentP95Ms}ms vs {report.baselineP95Ms}ms
              </div>
            </div>

            <div className="p-4 rounded-xl bg-slate-900/40 border border-slate-800">
              <div className="flex items-center justify-between text-slate-400 text-xs">
                <span>API Inventory Diff</span>
                <PlusCircle className="h-4 w-4 text-amber-400" />
              </div>
              <div className="mt-2 text-2xl font-bold text-slate-100">
                +{report.addedApisCount || 0} / -{report.removedApisCount || 0}
              </div>
              <div className="text-[11px] text-slate-500 mt-1">Endpoints added / removed</div>
            </div>
          </div>

          {/* Latency Distribution Percentiles Comparison */}
          <div className="rounded-xl border border-slate-800 bg-slate-950 p-5 space-y-4 shadow-xl">
            <h3 className="text-xs font-semibold text-slate-300 uppercase tracking-wider">Latency SLA Distribution Comparison</h3>
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
              <div className="p-3 bg-slate-900/60 rounded-lg border border-slate-800/80">
                <div className="text-[11px] text-slate-400">P50 (Median)</div>
                <div className="text-base font-bold text-slate-200 mt-1">{report.currentP50Ms} ms</div>
                <div className="text-[10px] text-slate-500">Baseline: {report.baselineP50Ms} ms ({report.p50DeltaPercent > 0 ? `+${report.p50DeltaPercent}%` : `${report.p50DeltaPercent}%`})</div>
              </div>
              <div className="p-3 bg-slate-900/60 rounded-lg border border-slate-800/80">
                <div className="text-[11px] text-slate-400">P90 Percentile</div>
                <div className="text-base font-bold text-slate-200 mt-1">{report.currentP90Ms} ms</div>
                <div className="text-[10px] text-slate-500">Baseline: {report.baselineP90Ms} ms ({report.p90DeltaPercent > 0 ? `+${report.p90DeltaPercent}%` : `${report.p90DeltaPercent}%`})</div>
              </div>
              <div className="p-3 bg-slate-900/60 rounded-lg border border-slate-800/80">
                <div className="text-[11px] text-slate-400">P95 (Production Target)</div>
                <div className="text-base font-bold text-slate-200 mt-1">{report.currentP95Ms} ms</div>
                <div className="text-[10px] text-slate-500">Baseline: {report.baselineP95Ms} ms ({report.p95DeltaPercent > 0 ? `+${report.p95DeltaPercent}%` : `${report.p95DeltaPercent}%`})</div>
              </div>
              <div className="p-3 bg-slate-900/60 rounded-lg border border-slate-800/80">
                <div className="text-[11px] text-slate-400">P99 (Tail Latency)</div>
                <div className="text-base font-bold text-slate-200 mt-1">{report.currentP99Ms} ms</div>
                <div className="text-[10px] text-slate-500">Baseline: {report.baselineP99Ms} ms ({report.p99DeltaPercent > 0 ? `+${report.p99DeltaPercent}%` : `${report.p99DeltaPercent}%`})</div>
              </div>
            </div>
          </div>

          {/* Granular Findings Table */}
          <div className="rounded-xl border border-slate-800 bg-slate-950 overflow-hidden shadow-xl">
            <div className="px-4 py-3 bg-slate-900/80 border-b border-slate-800 flex items-center justify-between">
              <span className="text-xs font-semibold text-slate-300">
                Persisted Regression Findings ({findings.length})
              </span>
            </div>
            {findings.length === 0 ? (
              <div className="p-8 text-center text-xs text-slate-500">
                No contract drift, status shifts, or latency regressions detected.
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-xs">
                  <thead className="bg-slate-900/40 text-slate-400 border-b border-slate-800/80 font-mono text-[11px]">
                    <tr>
                      <th className="py-2.5 px-4">Severity</th>
                      <th className="py-2.5 px-4">Finding Type</th>
                      <th className="py-2.5 px-4">Endpoint</th>
                      <th className="py-2.5 px-4">Baseline &rarr; Current</th>
                      <th className="py-2.5 px-4">Description</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-800/60 font-mono">
                    {findings.map((f) => (
                      <tr key={f.id} className="hover:bg-slate-900/30 transition-colors">
                        <td className="py-3 px-4">{getSeverityBadge(f.severity)}</td>
                        <td className="py-3 px-4 text-slate-300">{f.findingType}</td>
                        <td className="py-3 px-4 text-slate-200 font-semibold">
                          {f.httpMethod} {f.endpointPath}
                        </td>
                        <td className="py-3 px-4 text-slate-400">
                          {f.baselineValue} &rarr; <span className="text-slate-100">{f.currentValue}</span>
                        </td>
                        <td className="py-3 px-4 text-slate-400 font-sans">{f.description}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      ) : (
        <div className="p-16 text-center space-y-3 bg-slate-900/40 rounded-xl border border-slate-800">
          <FileText className="h-10 w-10 text-slate-600 mx-auto" />
          <p className="text-slate-300 font-medium text-sm">No Regression Data Available</p>
          <p className="text-slate-500 text-xs max-w-sm mx-auto">
            Regression analysis runs automatically when a prior completed test run exists for the target backend specification.
          </p>
        </div>
      )}
    </div>
  );
}
