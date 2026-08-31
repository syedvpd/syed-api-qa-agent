import type { Metadata } from "next";
import "./globals.css";
import Link from "next/link";
import { ShieldCheck, PlayCircle, LayoutDashboard, Terminal } from "lucide-react";

export const metadata: Metadata = {
  title: "Syed API QA Agent | Autonomous API Testing",
  description: "Autonomous, deterministic API Quality Assurance for live deployed backends.",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <body className="antialiased bg-[#090d16] text-slate-100 min-h-screen flex flex-col">
        <header className="border-b border-slate-800 bg-[#0d1322]/80 backdrop-blur-md sticky top-0 z-50">
          <div className="max-w-7xl mx-auto px-6 h-16 flex items-center justify-between">
            <div className="flex items-center space-x-3">
              <div className="h-9 w-9 rounded-lg bg-emerald-500/20 border border-emerald-500/40 flex items-center justify-center text-emerald-400">
                <ShieldCheck className="h-5 w-5" />
              </div>
              <Link href="/" className="font-bold text-lg tracking-tight hover:text-emerald-400 transition-colors">
                SYED <span className="text-emerald-400">API QA</span> AGENT
              </Link>
              <span className="text-xs px-2 py-0.5 rounded bg-slate-800 text-slate-400 border border-slate-700 font-mono">
                ZERO-LLM
              </span>
            </div>

            <nav className="flex items-center space-x-6 text-sm font-medium">
              <Link
                href="/dashboard"
                className="flex items-center space-x-2 text-slate-400 hover:text-slate-200 transition-colors"
              >
                <LayoutDashboard className="h-4 w-4" />
                <span>Dashboard</span>
              </Link>
              <Link
                href="/new-run"
                className="flex items-center space-x-2 px-3 py-1.5 rounded-md bg-emerald-600 hover:bg-emerald-500 text-white font-semibold transition-all shadow-sm shadow-emerald-900/40"
              >
                <PlayCircle className="h-4 w-4" />
                <span>New Test Run</span>
              </Link>
            </nav>
          </div>
        </header>

        <main className="flex-1 max-w-7xl w-full mx-auto px-6 py-8">{children}</main>

        <footer className="border-t border-slate-800/80 py-6 text-center text-xs text-slate-500">
          <div className="max-w-7xl mx-auto px-6 flex justify-between items-center">
            <span>Syed API QA Agent &bull; Production Architecture &bull; Phase 0 Foundation</span>
            <div className="flex items-center space-x-4">
              <span className="flex items-center space-x-1">
                <Terminal className="h-3 w-3 text-emerald-400" />
                <span>Deterministic Execution Engine</span>
              </span>
            </div>
          </div>
        </footer>
      </body>
    </html>
  );
}
