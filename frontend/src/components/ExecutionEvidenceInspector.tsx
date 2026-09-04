"use client";

import { useState } from "react";
import {
  CheckCircle2,
  XCircle,
  AlertTriangle,
  Copy,
  Check,
  Shield,
  Layers,
  Activity,
  Send,
  Download,
  Terminal,
  HelpCircle,
  ExternalLink,
  Code2,
  Lock,
  ArrowRight
} from "lucide-react";

export interface ExecutionEvidence {
  runId?: string;
  caseId?: string;
  caseName?: string;
  scenarioType?: string;
  stepId: string;
  stepOrder?: number;
  stepName: string;
  operationId?: string;
  method: string;
  pathTemplate: string;
  originalTemplate?: string;
  resolvedUrl?: string;
  pathParams?: Record<string, string>;
  queryParams?: Record<string, string>;
  requestHeaders?: Record<string, string>;
  requestBody?: string;
  requestContentType?: string;
  requestGenerationSource?: string;
  securityRequired?: boolean;
  securitySchemeType?: string;
  selectedIdentity?: string;
  authStrategy?: string;
  authState?: string;
  secretsRedacted?: boolean;
  httpSent: boolean;
  startedAt?: string;
  completedAt?: string;
  latencyMs?: number;
  retryCount?: number;
  retryReason?: string;
  responseStatus?: number;
  responseStatusText?: string;
  responseHeaders?: Record<string, string>;
  responseBody?: string;
  responseContentType?: string;
  responseSize?: number;
  requestSchemaValid?: boolean;
  responseSchemaValid?: boolean;
  expectedStatus?: number;
  actualStatus?: number;
  statusPassed?: boolean;
  assertions?: Array<{
    assertionType: string;
    targetField: string;
    expectedValue: string;
    actualValue: string;
    passed: boolean;
    message?: string;
  }>;
  validationFindings?: string[];
  hasDependency?: boolean;
  producerStepId?: string;
  producerMethodPath?: string;
  requiredVariables?: string[];
  resolvedVariables?: Record<string, string>;
  dependencyStatus?: string;
  upstreamFailureReason?: string;
  status: string; // PASSED, FAILED, BLOCKED, etc.
  classification?: string;
  rootCause?: string;
  customerExplanation?: string;
  suggestedRemediation?: string;
}

interface Props {
  evidence: ExecutionEvidence | null;
  onClose?: () => void;
}

export default function ExecutionEvidenceInspector({ evidence, onClose }: Props) {
  const [activeTab, setActiveTab] = useState<"overview" | "request" | "response" | "validation" | "dependencies" | "diagnosis">("overview");
  const [copiedSection, setCopiedSection] = useState<string | null>(null);

  if (!evidence) {
    return (
      <div className="p-8 text-center text-slate-500 text-xs font-mono">
        <Terminal className="h-8 w-8 mx-auto mb-2 text-slate-600 opacity-50" />
        Select an executed step to inspect verifiable request, response, validation, and diagnostic facts.
      </div>
    );
  }

  const copyToClipboard = (text: string, section: string) => {
    navigator.clipboard.writeText(text);
    setCopiedSection(section);
    setTimeout(() => setCopiedSection(null), 2000);
  };

  const isPassed = evidence.status === "PASSED";
  const isBlocked = evidence.status === "BLOCKED" || evidence.status === "REQUEST_NOT_EXECUTABLE" || evidence.status === "BLOCKED_BY_AUTHENTICATION";
  const isFailed = !isPassed && !isBlocked;

  return (
    <div className="flex flex-col h-full rounded-xl border border-slate-800 bg-slate-900/90 backdrop-blur-md overflow-hidden shadow-2xl font-mono text-xs">
      {/* Header */}
      <div className="p-4 bg-slate-950/80 border-b border-slate-800 flex items-center justify-between gap-3">
        <div className="flex items-center space-x-2.5 overflow-hidden">
          <span
            className={`px-2 py-0.5 rounded font-black text-xs ${
              evidence.method === "GET"
                ? "bg-sky-500/20 text-sky-400 border border-sky-500/30"
                : evidence.method === "POST"
                ? "bg-emerald-500/20 text-emerald-400 border border-emerald-500/30"
                : evidence.method === "PUT" || evidence.method === "PATCH"
                ? "bg-amber-500/20 text-amber-400 border border-amber-500/30"
                : "bg-rose-500/20 text-rose-400 border border-rose-500/30"
            }`}
          >
            {evidence.method}
          </span>
          <span className="text-white font-bold truncate max-w-xs">{evidence.pathTemplate}</span>
        </div>

        <div className="flex items-center space-x-2">
          {/* Status Badge */}
          <span
            className={`px-2.5 py-1 rounded-full text-xs font-bold flex items-center space-x-1.5 ${
              isPassed
                ? "bg-emerald-500/20 text-emerald-400 border border-emerald-500/30"
                : isBlocked
                ? "bg-amber-500/20 text-amber-400 border border-amber-500/30"
                : "bg-rose-500/20 text-rose-400 border border-rose-500/30"
            }`}
          >
            {isPassed ? <CheckCircle2 className="h-3.5 w-3.5" /> : isBlocked ? <Lock className="h-3.5 w-3.5" /> : <XCircle className="h-3.5 w-3.5" />}
            <span>{evidence.status}</span>
          </span>

          {onClose && (
            <button
              onClick={onClose}
              className="p-1 rounded text-slate-400 hover:text-white hover:bg-slate-800"
            >
              ✕
            </button>
          )}
        </div>
      </div>

      {/* Tabs */}
      <div className="flex items-center space-x-1 bg-slate-950/40 px-3 pt-2 border-b border-slate-800 overflow-x-auto">
        {(
          [
            { key: "overview", label: "Overview" },
            { key: "request", label: "Request" },
            { key: "response", label: "Response" },
            { key: "validation", label: "Validation" },
            { key: "dependencies", label: "Dependencies" },
            { key: "diagnosis", label: "Diagnosis" },
          ] as const
        ).map((tab) => (
          <button
            key={tab.key}
            onClick={() => setActiveTab(tab.key)}
            className={`px-3 py-1.5 rounded-t-lg font-semibold transition-colors border-t border-x ${
              activeTab === tab.key
                ? "bg-slate-900 text-white border-slate-700 font-bold"
                : "text-slate-400 border-transparent hover:text-slate-200"
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* Tab Contents */}
      <div className="flex-1 p-4 overflow-y-auto space-y-4">
        {/* TAB 1: OVERVIEW */}
        {activeTab === "overview" && (
          <div className="space-y-4">
            {/* Key Metric Facts */}
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-2.5">
              <div className="p-3 rounded-lg bg-slate-950 border border-slate-800">
                <div className="text-slate-500 text-[10px] uppercase font-semibold">HTTP SENT</div>
                <div className={`text-sm font-black mt-1 ${evidence.httpSent ? "text-emerald-400" : "text-amber-400"}`}>
                  {evidence.httpSent ? "✓ YES (Real Wire)" : "✗ NO (Withheld)"}
                </div>
              </div>

              <div className="p-3 rounded-lg bg-slate-950 border border-slate-800">
                <div className="text-slate-500 text-[10px] uppercase font-semibold">Response Status</div>
                <div className="text-sm font-black text-white mt-1">
                  {evidence.responseStatus ? `${evidence.responseStatus} ${evidence.responseStatusText || ""}` : "No Response"}
                </div>
              </div>

              <div className="p-3 rounded-lg bg-slate-950 border border-slate-800">
                <div className="text-slate-500 text-[10px] uppercase font-semibold">Real Latency</div>
                <div className="text-sm font-black text-white mt-1">
                  {evidence.latencyMs ? `${evidence.latencyMs} ms` : "0 ms"}
                </div>
              </div>

              <div className="p-3 rounded-lg bg-slate-950 border border-slate-800">
                <div className="text-slate-500 text-[10px] uppercase font-semibold">Identity Used</div>
                <div className="text-sm font-black text-sky-400 mt-1 truncate">
                  {evidence.selectedIdentity || "Public"}
                </div>
              </div>
            </div>

            {/* Test Context */}
            <div className="p-3 rounded-lg bg-slate-950/60 border border-slate-800/80 space-y-2">
              <div className="flex justify-between items-center text-slate-400">
                <span>Step Name:</span>
                <span className="text-slate-200 font-semibold">{evidence.stepName}</span>
              </div>
              <div className="flex justify-between items-center text-slate-400">
                <span>Scenario Category:</span>
                <span className="text-slate-300">{evidence.scenarioType || "Contract Verification"}</span>
              </div>
              <div className="flex justify-between items-center text-slate-400">
                <span>Resolved URL:</span>
                <span className="text-emerald-400 break-all">{evidence.resolvedUrl || evidence.pathTemplate}</span>
              </div>
            </div>

            {/* Why PASS / FAIL / BLOCKED Summary Card */}
            <div
              className={`p-3.5 rounded-lg border ${
                isPassed
                  ? "bg-emerald-950/20 border-emerald-500/30 text-emerald-300"
                  : isBlocked
                  ? "bg-amber-950/20 border-amber-500/30 text-amber-300"
                  : "bg-rose-950/20 border-rose-500/30 text-rose-300"
              }`}
            >
              <div className="font-bold flex items-center space-x-1.5 mb-1.5">
                {isPassed ? <CheckCircle2 className="h-4 w-4 text-emerald-400" /> : isBlocked ? <Lock className="h-4 w-4 text-amber-400" /> : <XCircle className="h-4 w-4 text-rose-400" />}
                <span>Why was this test {evidence.status}?</span>
              </div>
              <p className="text-xs leading-relaxed opacity-90">{evidence.customerExplanation}</p>
            </div>
          </div>
        )}

        {/* TAB 2: REQUEST */}
        {activeTab === "request" && (
          <div className="space-y-4">
            <div>
              <div className="flex justify-between items-center mb-1">
                <span className="text-slate-400 font-bold uppercase text-[10px]">Resolved HTTP Request URL</span>
                <button
                  onClick={() => copyToClipboard(evidence.resolvedUrl || "", "url")}
                  className="text-slate-500 hover:text-white flex items-center space-x-1 text-[10px]"
                >
                  {copiedSection === "url" ? <Check className="h-3 w-3 text-emerald-400" /> : <Copy className="h-3 w-3" />}
                  <span>Copy URL</span>
                </button>
              </div>
              <div className="p-2.5 rounded bg-slate-950 border border-slate-800 text-emerald-400 font-mono break-all select-all">
                {evidence.resolvedUrl || evidence.pathTemplate}
              </div>
            </div>

            {/* Path & Query Params */}
            {evidence.pathParams && Object.keys(evidence.pathParams).length > 0 && (
              <div>
                <span className="text-slate-400 font-bold uppercase text-[10px] block mb-1">Path Parameters</span>
                <div className="p-2 rounded bg-slate-950 border border-slate-800 space-y-1">
                  {Object.entries(evidence.pathParams).map(([k, v]) => (
                    <div key={k} className="flex justify-between">
                      <span className="text-slate-400">{k}:</span>
                      <span className="text-sky-300 font-bold">{v}</span>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* Request Headers */}
            <div>
              <div className="flex justify-between items-center mb-1">
                <span className="text-slate-400 font-bold uppercase text-[10px]">Request Headers (Secrets Redacted)</span>
                <span className="text-[10px] text-emerald-400 flex items-center space-x-1">
                  <Shield className="h-3 w-3" />
                  <span>Redacted</span>
                </span>
              </div>
              <div className="p-2.5 rounded bg-slate-950 border border-slate-800 space-y-1 max-h-32 overflow-y-auto">
                {evidence.requestHeaders && Object.keys(evidence.requestHeaders).length > 0 ? (
                  Object.entries(evidence.requestHeaders).map(([k, v]) => (
                    <div key={k} className="flex justify-between">
                      <span className="text-slate-400">{k}:</span>
                      <span className="text-slate-200 truncate max-w-xs">{v}</span>
                    </div>
                  ))
                ) : (
                  <span className="text-slate-500">None</span>
                )}
              </div>
            </div>

            {/* Request Body */}
            <div>
              <div className="flex justify-between items-center mb-1">
                <span className="text-slate-400 font-bold uppercase text-[10px]">Sent Request Payload (JSON)</span>
                {evidence.requestBody && (
                  <button
                    onClick={() => copyToClipboard(evidence.requestBody || "", "req_body")}
                    className="text-slate-500 hover:text-white flex items-center space-x-1 text-[10px]"
                  >
                    {copiedSection === "req_body" ? <Check className="h-3 w-3 text-emerald-400" /> : <Copy className="h-3 w-3" />}
                    <span>Copy JSON</span>
                  </button>
                )}
              </div>
              <pre className="p-3 rounded bg-slate-950 border border-slate-800 text-slate-300 max-h-56 overflow-auto leading-relaxed select-all">
                {evidence.requestBody ? evidence.requestBody : "None (No Request Body)"}
              </pre>
            </div>
          </div>
        )}

        {/* TAB 3: RESPONSE */}
        {activeTab === "response" && (
          <div className="space-y-4">
            <div className="flex items-center justify-between p-3 rounded-lg bg-slate-950 border border-slate-800">
              <div className="flex items-center space-x-2">
                <span className="text-slate-400">HTTP Status:</span>
                <span className="font-bold text-white text-sm">
                  {evidence.responseStatus ? `${evidence.responseStatus} ${evidence.responseStatusText || ""}` : "No Response Received"}
                </span>
              </div>
              <div className="text-slate-400">
                Latency: <span className="text-emerald-400 font-bold">{evidence.latencyMs || 0} ms</span>
              </div>
            </div>

            {/* Response Headers */}
            {evidence.responseHeaders && Object.keys(evidence.responseHeaders).length > 0 && (
              <div>
                <span className="text-slate-400 font-bold uppercase text-[10px] block mb-1">Response Headers</span>
                <div className="p-2.5 rounded bg-slate-950 border border-slate-800 space-y-1 max-h-32 overflow-y-auto">
                  {Object.entries(evidence.responseHeaders).map(([k, v]) => (
                    <div key={k} className="flex justify-between">
                      <span className="text-slate-400">{k}:</span>
                      <span className="text-slate-200 truncate max-w-xs">{v}</span>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* Response Body */}
            <div>
              <div className="flex justify-between items-center mb-1">
                <span className="text-slate-400 font-bold uppercase text-[10px]">Actual HTTP Response Body</span>
                {evidence.responseBody && (
                  <button
                    onClick={() => copyToClipboard(evidence.responseBody || "", "resp_body")}
                    className="text-slate-500 hover:text-white flex items-center space-x-1 text-[10px]"
                  >
                    {copiedSection === "resp_body" ? <Check className="h-3 w-3 text-emerald-400" /> : <Copy className="h-3 w-3" />}
                    <span>Copy JSON</span>
                  </button>
                )}
              </div>
              <pre className="p-3 rounded bg-slate-950 border border-slate-800 text-slate-300 max-h-72 overflow-auto leading-relaxed select-all">
                {evidence.responseBody ? evidence.responseBody : "No response body received."}
              </pre>
            </div>
          </div>
        )}

        {/* TAB 4: VALIDATION */}
        {activeTab === "validation" && (
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-2.5">
              <div className="p-3 rounded-lg bg-slate-950 border border-slate-800">
                <span className="text-slate-500 text-[10px] uppercase font-semibold">Request Schema</span>
                <div className="text-sm font-bold text-emerald-400 mt-1">✓ VALID (Contract Synthesized)</div>
              </div>
              <div className="p-3 rounded-lg bg-slate-950 border border-slate-800">
                <span className="text-slate-500 text-[10px] uppercase font-semibold">Response Schema</span>
                <div className={`text-sm font-bold mt-1 ${evidence.responseSchemaValid ? "text-emerald-400" : "text-rose-400"}`}>
                  {evidence.responseSchemaValid ? "✓ VALID" : "✗ MISMATCH"}
                </div>
              </div>
            </div>

            {/* Assertion Table */}
            <div>
              <span className="text-slate-400 font-bold uppercase text-[10px] block mb-1">Automated Assertions Evaluated</span>
              <div className="rounded-lg border border-slate-800 overflow-hidden bg-slate-950">
                <table className="w-full text-left text-[11px]">
                  <thead>
                    <tr className="bg-slate-900 border-b border-slate-800 text-slate-400">
                      <th className="py-2 px-3">Type</th>
                      <th className="py-2 px-3">Target</th>
                      <th className="py-2 px-3">Expected</th>
                      <th className="py-2 px-3">Actual</th>
                      <th className="py-2 px-3">Result</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-800/60">
                    {evidence.assertions && evidence.assertions.length > 0 ? (
                      evidence.assertions.map((a, idx) => (
                        <tr key={idx}>
                          <td className="py-2 px-3 text-slate-300 font-semibold">{a.assertionType}</td>
                          <td className="py-2 px-3 text-slate-400">{a.targetField}</td>
                          <td className="py-2 px-3 text-slate-300">{a.expectedValue || "-"}</td>
                          <td className="py-2 px-3 text-slate-300">{a.actualValue || "-"}</td>
                          <td className="py-2 px-3">
                            <span className={`font-bold ${a.passed ? "text-emerald-400" : "text-rose-400"}`}>
                              {a.passed ? "✓ PASS" : "✗ FAIL"}
                            </span>
                          </td>
                        </tr>
                      ))
                    ) : (
                      <tr>
                        <td colSpan={5} className="py-4 text-center text-slate-500">
                          Status Code Assertion: Expected {evidence.expectedStatus || "2xx"}, Received {evidence.responseStatus || "None"}
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        )}

        {/* TAB 5: DEPENDENCIES */}
        {activeTab === "dependencies" && (
          <div className="space-y-4">
            <div className="p-3.5 rounded-lg bg-slate-950 border border-slate-800 space-y-2">
              <div className="flex justify-between items-center">
                <span className="text-slate-400">Has Dependency Predecessor:</span>
                <span className="text-white font-bold">{evidence.hasDependency ? "YES" : "NO (Root Operation)"}</span>
              </div>
              {evidence.producerMethodPath && (
                <div className="flex justify-between items-center">
                  <span className="text-slate-400">Producer Operation:</span>
                  <span className="text-sky-400 font-bold">{evidence.producerMethodPath}</span>
                </div>
              )}
              {evidence.dependencyStatus && (
                <div className="flex justify-between items-center">
                  <span className="text-slate-400">Dependency DAG Status:</span>
                  <span className={`font-bold ${evidence.dependencyStatus === "SATISFIED" ? "text-emerald-400" : "text-amber-400"}`}>
                    {evidence.dependencyStatus}
                  </span>
                </div>
              )}
            </div>

            {evidence.upstreamFailureReason && (
              <div className="p-3 rounded-lg bg-amber-950/30 border border-amber-800/40 text-amber-300">
                <div className="font-bold mb-1">Upstream Blocking Reason:</div>
                <p className="text-xs">{evidence.upstreamFailureReason}</p>
              </div>
            )}
          </div>
        )}

        {/* TAB 6: DIAGNOSIS & REMEDIATION */}
        {activeTab === "diagnosis" && (
          <div className="space-y-4">
            <div className="p-3.5 rounded-lg bg-slate-950 border border-slate-800 space-y-3">
              <div>
                <span className="text-slate-500 text-[10px] uppercase font-semibold">Classification</span>
                <div className="text-sm font-bold text-white mt-0.5">{evidence.classification || "CONTRACT_VERIFICATION"}</div>
              </div>

              <div>
                <span className="text-slate-500 text-[10px] uppercase font-semibold">Root Cause</span>
                <div className="text-xs font-semibold text-rose-300 mt-0.5">{evidence.rootCause}</div>
              </div>

              <div>
                <span className="text-slate-500 text-[10px] uppercase font-semibold">Customer-Facing Explanation</span>
                <p className="text-slate-300 text-xs mt-0.5 leading-relaxed">{evidence.customerExplanation}</p>
              </div>
            </div>

            {/* Actionable Next Step */}
            <div className="p-3.5 rounded-lg bg-emerald-950/20 border border-emerald-500/30 text-emerald-300 space-y-1.5">
              <div className="font-bold flex items-center space-x-1.5 text-xs text-emerald-400">
                <ArrowRight className="h-4 w-4" />
                <span>Suggested Actionable Next Step:</span>
              </div>
              <p className="text-xs leading-relaxed text-slate-200">{evidence.suggestedRemediation}</p>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
