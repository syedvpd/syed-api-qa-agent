"use client";

import React from "react";

/**
 * ApiNetworkBackground
 * Subtle animated "API network / execution graph" visual backdrop.
 * Displays node topologies (OpenAPI -> Discovery -> Planning -> Execution -> Assertion -> Report)
 * with faint flowing dashed lines and gentle data pulses.
 * Strictly respects prefers-reduced-motion and does not capture pointer events.
 */
export default function ApiNetworkBackground() {
  return (
    <div
      aria-hidden="true"
      className="pointer-events-none fixed inset-0 -z-10 overflow-hidden bg-[#070a12]"
    >
      {/* Ambient Radial Lighting */}
      <div className="absolute top-[-10%] left-[15%] h-[500px] w-[500px] rounded-full bg-emerald-500/[0.035] blur-[140px]" />
      <div className="absolute top-[20%] right-[10%] h-[600px] w-[600px] rounded-full bg-indigo-600/[0.04] blur-[160px]" />
      <div className="absolute bottom-[5%] left-[30%] h-[450px] w-[450px] rounded-full bg-cyan-500/[0.025] blur-[130px]" />

      {/* Subtle Dot Grid */}
      <svg
        className="absolute inset-0 h-full w-full opacity-[0.14]"
        xmlns="http://www.w3.org/2000/svg"
      >
        <defs>
          <pattern
            id="network-grid"
            width="32"
            height="32"
            patternUnits="userSpaceOnUse"
          >
            <circle cx="2" cy="2" r="1" fill="#64748b" />
          </pattern>
        </defs>
        <rect width="100%" height="100%" fill="url(#network-grid)" />
      </svg>

      {/* SVG Network Graph Lines & Nodes */}
      <svg
        className="absolute inset-0 h-full w-full"
        xmlns="http://www.w3.org/2000/svg"
      >
        <defs>
          <linearGradient id="lineGradA" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor="#10b981" stopOpacity="0.3" />
            <stop offset="50%" stopColor="#6366f1" stopOpacity="0.2" />
            <stop offset="100%" stopColor="#38bdf8" stopOpacity="0.1" />
          </linearGradient>
          <linearGradient id="lineGradB" x1="0%" y1="0%" x2="100%" y2="0%">
            <stop offset="0%" stopColor="#6366f1" stopOpacity="0.25" />
            <stop offset="100%" stopColor="#10b981" stopOpacity="0.15" />
          </linearGradient>
        </defs>

        {/* Path 1: Discovery -> Planning -> Concurrent Execution */}
        <path
          d="M 120,90 Q 380,140 600,100 T 1100,150"
          fill="none"
          stroke="url(#lineGradA)"
          strokeWidth="1.2"
          strokeDasharray="6,8"
          className="animate-flow-line"
        />
        {/* Path 2: Execution -> Assertions -> Report */}
        <path
          d="M 200,320 C 450,220 750,420 1150,280"
          fill="none"
          stroke="url(#lineGradB)"
          strokeWidth="1"
          strokeDasharray="5,7"
          className="animate-flow-line"
        />
        {/* Path 3: Lower dependency branch */}
        <path
          d="M 80,560 Q 500,480 850,590 T 1280,520"
          fill="none"
          stroke="#475569"
          strokeOpacity="0.15"
          strokeWidth="1"
          strokeDasharray="4,6"
        />

        {/* Nodes with gentle halos */}
        <g opacity="0.45">
          {/* Node 1 */}
          <circle cx="120" cy="90" r="3.5" fill="#10b981" />
          <circle cx="120" cy="90" r="8" fill="none" stroke="#10b981" strokeOpacity="0.3" />

          {/* Node 2 */}
          <circle cx="600" cy="100" r="3" fill="#6366f1" />
          <circle cx="600" cy="100" r="7" fill="none" stroke="#6366f1" strokeOpacity="0.25" />

          {/* Node 3 */}
          <circle cx="1100" cy="150" r="4" fill="#38bdf8" />
          <circle cx="1100" cy="150" r="9" fill="none" stroke="#38bdf8" strokeOpacity="0.3" />

          {/* Node 4 */}
          <circle cx="200" cy="320" r="3" fill="#10b981" />

          {/* Node 5 */}
          <circle cx="750" cy="420" r="3.5" fill="#a855f7" />
          <circle cx="750" cy="420" r="8" fill="none" stroke="#a855f7" strokeOpacity="0.2" />

          {/* Node 6 */}
          <circle cx="1150" cy="280" r="3" fill="#10b981" />
        </g>
      </svg>
    </div>
  );
}
