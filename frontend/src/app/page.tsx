import Link from "next/link";
import { ShieldCheck, PlayCircle, Cpu, Network, CheckCircle2, ArrowRight, FileText } from "lucide-react";

export default function Home() {
  return (
    <div className="space-y-12 py-6">
      {/* Hero Section */}
      <div className="text-center max-w-3xl mx-auto space-y-4">
        <div className="inline-flex items-center space-x-2 px-3 py-1 rounded-full bg-emerald-500/10 border border-emerald-500/30 text-emerald-400 text-xs font-semibold">
          <Cpu className="h-3.5 w-3.5" />
          <span>Deterministic Autonomous Testing Engine</span>
        </div>
        <h1 className="text-4xl sm:text-5xl font-extrabold tracking-tight text-white leading-tight">
          Comprehensive API QA Against <span className="text-emerald-400">Live Deployed Backends</span>
        </h1>
        <p className="text-slate-400 text-base sm:text-lg leading-relaxed">
          Provide your live OpenAPI or Swagger URL. Syed API QA Agent maps dependencies, synthesizes valid data,
          executes stateful CRUD workflows, isolates failures, and produces evidence-based PDF reports — without an LLM.
        </p>
        <div className="flex items-center justify-center space-x-4 pt-4">
          <Link
            href="/new-run"
            className="flex items-center space-x-2 px-5 py-3 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-white font-semibold transition-all shadow-lg shadow-emerald-950"
          >
            <PlayCircle className="h-5 w-5" />
            <span>Launch Test Run</span>
          </Link>
          <Link
            href="/dashboard"
            className="flex items-center space-x-2 px-5 py-3 rounded-lg bg-slate-800/80 hover:bg-slate-750 border border-slate-700 text-slate-200 font-semibold transition-all"
          >
            <span>View Dashboard</span>
            <ArrowRight className="h-4 w-4" />
          </Link>
        </div>
      </div>

      {/* Core Architectural Pillars */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-6 pt-6">
        <div className="p-6 rounded-xl bg-slate-900/60 border border-slate-800 space-y-3">
          <div className="h-10 w-10 rounded-lg bg-blue-500/10 border border-blue-500/30 flex items-center justify-center text-blue-400">
            <Cpu className="h-5 w-5" />
          </div>
          <h3 className="font-semibold text-lg text-white">Zero LLM Dependency</h3>
          <p className="text-sm text-slate-400 leading-relaxed">
            100% deterministic algorithms, graph theory, state machines, and schema rules. Zero API tokens, zero AI hallucinations, zero cost per run.
          </p>
        </div>

        <div className="p-6 rounded-xl bg-slate-900/60 border border-slate-800 space-y-3">
          <div className="h-10 w-10 rounded-lg bg-emerald-500/10 border border-emerald-500/30 flex items-center justify-center text-emerald-400">
            <Network className="h-5 w-5" />
          </div>
          <h3 className="font-semibold text-lg text-white">Dependency Graph & State</h3>
          <p className="text-sm text-slate-400 leading-relaxed">
            Infers entity IDs and variables across parent-child routes. Automatically coordinates stateful CREATE &rarr; READ &rarr; UPDATE &rarr; DELETE cycles.
          </p>
        </div>

        <div className="p-6 rounded-xl bg-slate-900/60 border border-slate-800 space-y-3">
          <div className="h-10 w-10 rounded-lg bg-purple-500/10 border border-purple-500/30 flex items-center justify-center text-purple-400">
            <ShieldCheck className="h-5 w-5" />
          </div>
          <h3 className="font-semibold text-lg text-white">Production Safety First</h3>
          <p className="text-sm text-slate-400 leading-relaxed">
            SSRF defense, loopback & private IP blocking, credential masking, rate-limiting, and gated destructive verbs in production environments.
          </p>
        </div>
      </div>
    </div>
  );
}
