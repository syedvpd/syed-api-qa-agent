"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { ArrowLeft, Download, ShieldCheck, FileText, AlertTriangle, AlertCircle, Loader2 } from "lucide-react";
import { getApiBaseUrl } from "@/lib/api";

export default function RunReportPage({ params }: { params: { id: string } }) {
  const [reportHtml, setReportHtml] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [downloadingPdf, setDownloadingPdf] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [statusError, setStatusError] = useState<number | null>(null);

  useEffect(() => {
    const apiBase = getApiBaseUrl();
    setLoading(true);
    setErrorMessage(null);

    fetch(`${apiBase}/api/runs/${params.id}/report`)
      .then((res) => {
        if (!res.ok) {
          setStatusError(res.status);
          if (res.status === 404) {
            throw new Error("Test run or report not found.");
          } else if (res.status === 401 || res.status === 403) {
            throw new Error("Unauthorized to access this audit report.");
          } else {
            throw new Error(`Failed to load report (HTTP ${res.status}).`);
          }
        }
        return res.text();
      })
      .then((html) => {
        setReportHtml(html);
        setLoading(false);
      })
      .catch((err) => {
        setErrorMessage(err.message || "Target backend is unreachable.");
        setLoading(false);
      });
  }, [params.id]);

  const handleDownloadHtml = () => {
    if (!reportHtml) return;
    const blob = new Blob([reportHtml], { type: "text/html" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `Syed-API-QA-Report-${params.id}.html`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const handleDownloadPdf = async () => {
    const apiBase = getApiBaseUrl();
    setDownloadingPdf(true);
    setErrorMessage(null);

    try {
      const res = await fetch(`${apiBase}/api/runs/${params.id}/report/pdf`);
      if (!res.ok) {
        if (res.status === 401) {
          throw new Error("Authentication required to download PDF report.");
        } else if (res.status === 403) {
          throw new Error("Access denied: You do not own this test run.");
        } else if (res.status === 404) {
          throw new Error("PDF report not found for this run.");
        } else {
          throw new Error(`PDF download failed (HTTP ${res.status}).`);
        }
      }

      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `syed-qa-report-${params.id}.pdf`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    } catch (err: any) {
      setErrorMessage(err.message || "Failed to connect to backend server.");
    } finally {
      setDownloadingPdf(false);
    }
  };

  return (
    <div className="space-y-6">
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
          <span className="text-xs font-mono text-slate-300">Executive Audit Report</span>
        </div>

        <div className="flex items-center space-x-3">
          <button
            onClick={handleDownloadPdf}
            disabled={downloadingPdf || loading}
            className="flex items-center space-x-1.5 px-3.5 py-1.5 rounded-lg bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-xs font-semibold text-white shadow-sm transition-colors"
          >
            {downloadingPdf ? (
              <Loader2 className="h-3.5 w-3.5 animate-spin" />
            ) : (
              <Download className="h-3.5 w-3.5" />
            )}
            <span>{downloadingPdf ? "Generating PDF..." : "Download Official PDF Report"}</span>
          </button>

          <button
            onClick={handleDownloadHtml}
            disabled={!reportHtml || loading}
            className="flex items-center space-x-1.5 px-3.5 py-1.5 rounded-lg bg-emerald-600 hover:bg-emerald-500 disabled:opacity-50 text-xs font-semibold text-white shadow-sm transition-colors"
          >
            <Download className="h-3.5 w-3.5" />
            <span>Download HTML Report</span>
          </button>
        </div>
      </div>

      {errorMessage && (
        <div className="p-4 rounded-lg bg-rose-950/40 border border-rose-800/60 flex items-center space-x-3 text-rose-300 text-xs">
          <AlertCircle className="h-4 w-4 shrink-0 text-rose-400" />
          <span>{errorMessage}</span>
        </div>
      )}

      {loading ? (
        <div className="p-16 text-center space-y-3 bg-slate-900/20 rounded-xl border border-slate-800/80">
          <Loader2 className="h-6 w-6 text-indigo-400 animate-spin mx-auto" />
          <div className="text-slate-400 text-xs font-mono">Loading audit report...</div>
        </div>
      ) : reportHtml ? (
        <div className="rounded-xl border border-slate-800 overflow-hidden bg-slate-950 shadow-2xl">
          <iframe
            srcDoc={reportHtml}
            title="Executive Report"
            className="w-full h-[850px] border-0"
            sandbox="allow-scripts allow-same-origin"
          />
        </div>
      ) : (
        <div className="p-16 text-center space-y-3 bg-slate-900/40 rounded-xl border border-slate-800">
          <FileText className="h-10 w-10 text-slate-600 mx-auto" />
          <p className="text-slate-300 font-medium text-sm">
            {statusError === 404
              ? "Test Run Not Found"
              : statusError === 401 || statusError === 403
              ? "Access Restricted"
              : "Report Not Yet Generated"}
          </p>
          <p className="text-slate-500 text-xs max-w-sm mx-auto">
            {statusError === 404
              ? "The requested test run does not exist in the database."
              : statusError === 401 || statusError === 403
              ? "You do not have permission to view this report."
              : "The executive report will be generated as soon as the test run execution finishes."}
          </p>
        </div>
      )}
    </div>
  );
}
