# Agent-Ready Observability: Logging for Machines, Not Humans

A demo of an AI agent that investigates production incidents: it reads logs and source code, follows a
`trace_id` through interleaved logs to the line of code that failed, and writes an incident report.
Everything runs locally: the agent uses a small open model via [Ollama](https://ollama.com), so no logs or
code leave the machine.

The point of the demo: the agent is only as good as the telemetry. The same code runs with two logging setups,
plain text and structured, and the agent investigates both logs.

## The program

The Java code lives in [`demo/`](demo). The logic is in one file, [`demo/chaindivide/ChainDivide.java`](demo/chaindivide/ChainDivide.java). `ChainDivide`
generates five random numbers from 0 to 4, logs each of them, then divides them one by another with `BigDecimal`.
Sometimes a divisor is 0, and `ArithmeticException: Division by zero` is thrown. Several runs execute in
parallel, so log lines of different runs are interleaved; like OpenTelemetry instrumentation, every run gets its
own trace context (`ChainDivide.Trace`).

The logic logs only through the `ChainDivide.Log` interface: an event name, a human-readable message
and the data as key-value pairs.

```java
log.info("number.generated", "Generated number #1: 3", "position", 1, "value", 3);
```

What ends up in the log is decided by the logging setup, one per entry point:

| Entry point | Log | What it writes |
|---|---|---|
| [`demo/SimpleLogging.java`](demo/SimpleLogging.java) | `logs/chain-divide-simple.log` | plain text: timestamp, level, message; drops event names, data, the trace context and stack traces |
| [`demo/ImprovedLogging.java`](demo/ImprovedLogging.java) | `logs/chain-divide.log` | one JSON object per line with everything |

## Two kinds of logs

`logs/chain-divide-simple.log`:

```
2026-10-18 17:04:35 INFO Generated number #1: 4
2026-10-18 17:04:35 INFO Generated number #1: 3
2026-10-18 17:04:35 ERROR Chain divide failed: Division by zero
```

The numbers are there, but with parallel runs nothing tells which numbers belong to which error, and there is no
stack trace, so not even the failing line is known.

`logs/chain-divide.log`:

```json
{"timestamp":"2026-10-18T17:04:36.002215Z","level":"INFO","service.name":"chain-divide","service.version":"1.4.2",
 "trace_id":"65a3aa41a5340852d8ffb5c255c6d05a","span_id":"e4405040f768ebab","event":"number.generated",
 "message":"Generated number #1: 2","position":1,"value":2,
 "code.file.path":"ChainDivide.java","code.line.number":47,"code.function.name":"chaindivide.ChainDivide.chainDivide"}
```

A handful of fields turns an investigation into a series of joins:

| Field | Lets the agent... |
|---|---|
| `event` (`number.generated`, `chain_divide.completed`, `chain_divide.failed`) | count and group without parsing free text |
| `trace_id`, `span_id` | join the log lines of one run |
| `service.name`, `service.version` | tell which build produced the line |
| `code.file.path`, `code.line.number`, `code.function.name` | go from a log line to the code |
| `exception.type`, `exception.message`, `exception.cause_chain`, `exception.stacktrace` | find the failing line and the root cause |
| key-value pairs (`position`, `value`, `result`) | see the data the code processed |

Field names follow the [OpenTelemetry semantic conventions](https://opentelemetry.io/docs/specs/semconv/)
where one exists. There are no dependencies: the `Log` implementations in the entry points do what a logging
library would do in a real service.

## The agent

The agent (Python, [LangChain](https://python.langchain.com) + Ollama) runs a four-step pipeline for every
log file in `logs/`:

| Step | What happens | LLM? |
|---|---|---|
| **Detect** | Parse the log (JSON or plain text), find errors, extract the exception and our own stack frames, group repeated errors into one incident | no, plain code |
| **Route** | Classify the incident (category, severity); resolve the owning team from a CODEOWNERS-like map | partly |
| **Investigate** | A tool-using agent reads the code and pulls the logs of affected runs by `trace_id` | yes |
| **Report** | Write a Markdown report: facts (log file, counts, timestamps, owner) come from code, hypothesis and fix from the LLM | yes |

### Agent tools

| Tool | Purpose |
|---|---|
| `get_trace(trace_id)` | All log lines of one run, in order; for errors, the exception and our own stack frames |
| `search_logs(pattern)` | Regex search over the log being investigated |
| `read_file(path, start, end)` | Read source code with line numbers |
| `search_code(pattern)` | Regex search over the source code |
| `list_files()` | List project source files |

The file tools only see `demo/chaindivide/`, the code under investigation (`SOURCE_DIRS` in `sre_agent/utils.py`);
the entry points with their logging setups are hidden. The logs are
reachable only through `get_trace` and `search_logs`, and only the log being investigated, so the agent cannot
borrow data from the other log.

For an error in `chain-divide.log`, `get_trace` shows the whole run:

```
20: 2026-10-18T17:04:36.007306Z INFO Generated number #1: 3
32: 2026-10-18T17:04:36.018961Z INFO Generated number #2: 3
38: 2026-10-18T17:04:36.024108Z INFO Generated number #3: 3
40: 2026-10-18T17:04:36.025647Z INFO Generated number #4: 4
57: 2026-10-18T17:04:36.032392Z INFO Generated number #5: 0
68: 2026-10-18T17:04:36.037292Z ERROR Chain divide failed: java.lang.ArithmeticException: Division by zero
java.lang.ArithmeticException: Division by zero
	at chaindivide.ChainDivide.chainDivide(ChainDivide.java:55)
	at chaindivide.ChainDivide.run(ChainDivide.java:35)
	at chaindivide.ChainDivide.lambda$runInParallel$0(ChainDivide.java:27)
  ...
```

If the agent finishes without calling `get_trace` while the incident has trace ids, it is asked to look at the
logs of the affected runs and answer again. If it hits the step limit, it summarizes what it has found so far
instead of returning nothing.

### What to expect

On `chain-divide.log` the agent finds the failing line, `demo/chaindivide/ChainDivide.java:55`, and for each failed run
shows which number was 0 and at which position.

On `chain-divide-simple.log` the incident has an unknown location and goes to the `@on-call` owner. The agent can
find the division by searching the code, but it cannot tell which numbers led to which error.

## Setup

Requirements: JDK 25 (e.g. `brew install openjdk@25` or `sdk install java 25-tem`),
Python 3 (tested with 3.14) and [Ollama](https://ollama.com).

```bash
ollama pull qwen3.5:4b

python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

## Running the demo

One command from the repository root drops `logs/` and `reports/`, writes both logs and runs the agent:

```bash
make
```

The steps also run on their own: `make clean`, `make logs`, `make agent`. Variables:

| Variable | Default | Meaning |
|---|---|---|
| `RUNS` | `20` | Parallel runs per log |
| `JAVA` | `java` | Java launcher (JDK 25) |
| `PYTHON` | `.venv/bin/python` | Python with the agent's requirements |

Without a local JDK 25, run Java in Docker:

```bash
make JAVA="docker run --rm -v $PWD:/w -w /w maven:3-eclipse-temurin-25 java"
```

The agent prints each pipeline step and every tool call live. Reports go to a directory named after the log:
`reports/chain-divide/` and `reports/chain-divide-simple/`. The first row of every report names the log file.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `AGENT_MODEL` | `qwen3.5:4b` | Any Ollama model with tool calling |
| `AGENT_THINK` | off | Set to `1` to enable the model's thinking mode (slower) |

```bash
AGENT_MODEL=qwen3.5:35b .venv/bin/python -m sre_agent.agent
```

`qwen3.5:4b` is fast but sometimes gets details wrong, e.g. it mistypes a trace id or sets `confidence: high`
without evidence. `qwen3.5:35b` (a Mixture-of-Experts model, ~3B active parameters, 24 GB download) is noticeably
more accurate and still reasonably fast on a machine with enough memory.

## Project layout

```
Makefile                make: clean -> logs -> agent
demo/
  chaindivide/
    ChainDivide.java    The logic with the bug, the Log interface and the Trace context
  SimpleLogging.java    Entry point: plain text logs -> logs/chain-divide-simple.log
  ImprovedLogging.java  Entry point: JSON logs -> logs/chain-divide.log
sre_agent/
  agent.py              Detect -> Route -> Investigate -> Report pipeline
  utils.py              Log parsing and agent tools
  models.py             Data classes and structured-output schemas
  report.md.j2          Jinja2 template of the incident report
logs/                   Logs the agent analyzes (created by the entry points)
reports/                Generated incident reports, one directory per log
```
