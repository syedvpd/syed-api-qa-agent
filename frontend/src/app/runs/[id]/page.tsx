import Link from "next/link";
import { ArrowLeft, Clock, ShieldCheck, Activity, FileText } from "lucide-react";

export default function RunDetailsPage({ params }: { params: { id: string } }) {
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <Link
          href="/dashboard"
          className="inline-flex items-center space-x-1.5 text-xs text-slate-400 hover:text-slate-200 transition-colors"
        >
          <ArrowLeft className="h-3.5 w-3.5" />
          <span>Back to Dashboard</span>
        </Link>
        <span className="text-xs px-2.5 py-1 rounded bg-slate-800 text-slate-400 border border-slate-700 font-mono">
          Run ID: {params.id}
        </span>
      </div>

      <div className="p-6 rounded-xl bg-slate-900/60 border border-slate-800 space-y-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center space-x-3">
            <div className="h-3 w-3 rounded-full bg-emerald-400 animate-ping" />
            <h1 className="text-xl font-bold text-white">Autonomous Test Execution</h1>
          </div>
          <span className="text-xs font-semibold px-2 py-0.5 rounded bg-emerald-500/10 text-emerald-400 border border-emerald-500/30">
            PHASE 0 FOUNDATION
          </span>
        </div>

        <p className="text-sm text-slate-400">
          In Phase 1, this view connects directly to the backend Server-Sent Events (SSE) stream at{" "}
          <code className="text-emerald-400 font-mono text-xs">/api/runs/{params.id}/stream</code>, displaying
          real-time endpoint discovery, step-by-step CRUD progress, and live latency measurements.
        </p>
      </div>
    </div>
  );
}
