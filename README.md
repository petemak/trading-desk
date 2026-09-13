# Tradaing Desk

## Introduction

In this project we assemble a five-agent Claude Code team that designs, builds, and reviews a real-time stock portfolio dashboard — while picking up the market vocabulary you need along the way. No prior trading experience assumed.


## Claude Agent team

A Claude Code agent team is one orchestrator — usually your main Claude Code session, driven by you — that delegates well-scoped pieces of work to subagents. Each subagent is a separate context window with its own system prompt, its own restricted toolset, and a narrow job description. The orchestrator decides what to delegate, in what order, and stitches the results together.

A single long-running Claude Code session accumulates context: every file it has read, every wrong turn it corrected, every unrelated decision it made. That context competes for attention with the task in front of it. 

Subagents reset that cost — each one starts clean, reads only what its brief points it to, and returns a focused result. For a five-module app like this one, that keeps each agent's reasoning about, say, the trading panel's validation logic uncontaminated by the dashboard's chart-sizing decisions


## The full roster

Nine agents, all defined as flat files under .claude/agents/ — no nesting by module. This table is the map: which file, what it's responsible for, and where it sits in the run order (spelled out in full in the orchestration playbook).

| Agent	                    | File	                     | Responsible for	                       | Runs          | 
---------------------------------------------------------------------------------------------------------------------
| Project Setup	            | project-setup.md	         | deps.edn, skeleton, Tailwind, CLAUDE.md | 1st, alone    | 
| State & Backend Architect	| state-backend-architect.md | DataScript schema, Component system, Pedestal/WS | 2nd, alone | 
| Dashboard Engineer    	| dashboard-engineer.md	     | Module 2 — dashboard                    | 3rd, parallel | 
| Trading Engineer	        | trading-engineer.md	     | Module 3 — trading	                   | 3rd, parallel | 
| Market Intel Engineer  	| market-intel-engineer.md   | Module 4 — watchlist/news	           | 3rd, parallel | 
| Ledger Engineer	        | ledger-engineer.md         | Module 5 — history	                   | 3rd, parallel |
| Code Reviewer	            | code-reviewer.md	         | Code review pass — incl. Kaocha	       | 4th, parallel |
| Design Reviewer	        | design-reviewer.md         | Design pass	                           | 4th, parallel |
| Eval Agent                | eval-agent.md 	         | Evals                                   | 5th, alone    |

##  Tech stack

This build is strictly ClojureScript on the frontend and Clojure on the backend — no React anywhere in the stack. 
- Replicant renders hiccup straight to the DOM (it's a rendering library, not a React wrapper — it diffs and patches hiccup trees itself)- DataScript is the in-browser data store: an immutable, Datalog-queryable database that replaces the plain JS object you might otherwise reach for. 
- Tailwind CSS handles styling via utility classes instead of hand-written CSS. 
- The Clojure backend is structured, not a single script: 
  - Aero reads config.edn into a plain map (port, tick interval, environment)
  - Pedestal on Jetty serves HTTP routes and the WebSocket that pushes price ticks, and 
  - Component wires the config, the price engine
  - Pedestal server into one system with explicit start/stop lifecycles — so the whole backend comes up and shuts down as one unit from the REPL, with no orphaned threads or ports left open between restarts. 
  - Kaocha runs the backend's unit tests (clojure -M:test), with each agent responsible for testing only the namespaces it owns.

