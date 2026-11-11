# Capacity baseline

What a run costs in time, memory and disk as it grows, measured on fixed
synthetic runs. These are numbers from one machine and one build, a baseline
to compare against — not a promise that larger runs scale the same way.

## How it was measured

`CapacityBaseline` runs the packaged CLI in its own JVM with a 1 GiB heap
ceiling and GC logging, against a local server that answers every request
with a JSON body of a fixed size. Each run is one scenario of repeat groups
reading that body, so the grid crosses the number of requests with the size of
each response. It writes the table to
`integration-tests/target/capacity/baseline.md`:

```bash
./mvnw -B package -pl integration-tests -am \
  -Dtest=CapacityBaseline -Dsurefire.failIfNoSpecifiedTests=false
```

Its name keeps it out of test discovery, so the ordinary build neither runs
nor skips it, and a strict qualification run counts no skipped suite.

*Max heap after GC* is the largest heap left after a collection, the closest
of the three heap columns to what the run actually holds; *before GC* and *at
exit* include garbage not yet collected.

## Baseline

Faultora 0.11.3, Java 21.0.12, Windows, 24 processors, `-Xmx1g`, 2026-10-28:

| Requests | Body | Wall time | Max heap before GC | Max heap after GC | Heap used at exit | GCs | Output on disk |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 100 | 1 KiB | 1469 ms | 39 MiB | 9 MiB | 15 MiB | 2 | 175 KiB |
| 500 | 1 KiB | 1783 ms | 39 MiB | 9 MiB | 31 MiB | 2 | 869 KiB |
| 1000 | 1 KiB | 1990 ms | 39 MiB | 9 MiB | 51 MiB | 2 | 1737 KiB |
| 100 | 64 KiB | 1287 ms | 39 MiB | 9 MiB | 66 MiB | 2 | 176 KiB |
| 500 | 64 KiB | 1817 ms | 143 MiB | 76 MiB | 80 MiB | 5 | 871 KiB |
| 1000 | 64 KiB | 2134 ms | 221 MiB | 127 MiB | 200 MiB | 6 | 1741 KiB |
| 100 | 256 KiB | 1369 ms | 130 MiB | 55 MiB | 149 MiB | 4 | 176 KiB |
| 500 | 256 KiB | 2051 ms | 428 MiB | 320 MiB | 419 MiB | 13 | 873 KiB |
| 1000 | 256 KiB | 2642 ms | 820 MiB | 658 MiB | 766 MiB | 26 | 1745 KiB |

## What the 0.11.3 baseline showed

- **Memory follows requests × response size.** Evidence is kept per node for
  the assertions that may read it, so a run holds every response body it
  received until it ends. At 256 KiB per response the retained heap is about
  2.6 bytes per byte received (658 MiB for 250 MiB of bodies), which covers
  the body and what is built from it.
- **Disk follows requests alone.** The journal and the JSON report record
  about 1.7 KiB per request whatever the body size; bodies are not written.
- **Time is not the constraint** at these sizes: a thousand local requests
  finish in under three seconds, JVM start included.

## Run limits

The default policy admits up to 1000 requests per run and response bodies up
to 1 MiB each. The runner can narrow these with `--max-requests` and
`--max-payload-bytes`. Its `--max-evidence-bytes` bounds body capture per
response. The engine also admits at most 64 MiB of policy-permitted evidence
content across the entire run, including parallel steps, repeats, polls and
cleanup. Engine integrations can narrow this ceiling with the integer
`maxRunEvidenceBytes` setting in `ConnectorContext.config`.

When the shared budget cannot admit a capture, the journal records
`EVIDENCE_BUDGET_EXCEEDED` for its node. Assertions that require the withheld
content see incomplete evidence. Status, counts and applicable digests
remain available. Already admitted content stays available through its
consumers. The engine binds step outputs before releasing direct evidence
and retains direct evidence through its last targeted assertion. Captures
charge the run allowance once even if the original evidence is later
released, because bound outputs may still hold that content.

The ordinary journal bounds retained events to 100,000 records and 64 MiB
of UTF-8 NDJSON, with capacity reserved for cleanup and terminal records.
The runner spool applies its own byte and record quotas. Event budget
exhaustion stops further business admissions and reports
`JOURNAL_WRITE_FAILED` with `journalOperation: event-budget`. Cleanup
still runs.

The byte budgets measure serialized content. Heap also holds parsed
values, expression outputs, in-flight responses, event objects, and
engine and JVM overhead.

## Measurement with the run budget

The same nine workloads ran on Windows with Java 21.0.12.1, 24 processors
and `-Xmx1g` on 2026-11-11. The harness verified the request counts and
checked that captured HTTP body bytes stayed within the run allowance.
These are the three cases with 256 KiB responses.

| Requests | Captured bodies | Captures limited | Max heap before GC | Max heap after GC |
|---:|---:|---:|---:|---:|
| 100 | 25 MiB | 0 | 132 MiB | 56 MiB |
| 500 | 63.75 MiB | 245 | 349 MiB | 63 MiB |
| 1000 | 63.75 MiB | 745 | 374 MiB | 102 MiB |

The largest workload in the historical 0.11.3 grid measured 658 MiB
after GC. The new grid's largest observed post-GC heap was 102 MiB.
These observations apply to the synthetic grid and its configuration.
