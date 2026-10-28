# Capacity baseline

What a run costs in time, memory and disk as it grows, measured on fixed
synthetic runs. These are numbers from one machine and one build, a baseline
to compare against — not a promise that larger runs scale the same way.

## How it was measured

`CapacityBaselineTest` runs the packaged CLI in its own JVM with a 1 GiB heap
ceiling and GC logging, against a local server that answers every request
with a JSON body of a fixed size. Each run is one scenario of repeat groups
reading that body, so the grid crosses the number of requests with the size of
each response. It writes the table to
`integration-tests/target/capacity/baseline.md`:

```bash
FAULTORA_CAPACITY=1 ./mvnw -B verify -pl integration-tests -am \
  -Dtest=CapacityBaselineTest -Dsurefire.failIfNoSpecifiedTests=false
```

The ordinary build skips it. *Max heap after GC* is the largest heap left after
a collection, the closest of the three heap columns to what the run actually
holds; *before GC* and *at exit* include garbage not yet collected.

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

## What it shows

- **Memory follows requests × response size.** Evidence is kept per node for
  the assertions that may read it, so a run holds every response body it
  received until it ends. At 256 KiB per response the retained heap is about
  2.6 bytes per byte received (658 MiB for 250 MiB of bodies), which covers
  the body and what is built from it.
- **Disk follows requests alone.** The journal and the JSON report record
  about 1.7 KiB per request whatever the body size; bodies are not written.
- **Time is not the constraint** at these sizes: a thousand local requests
  finish in under three seconds, JVM start included.

## The limit

The default policy admits up to 1000 requests per run and response bodies up
to 1 MiB each. At the ratio above, a run that used both to the full would hold
roughly 2.6 GiB, more than the 1 GiB measured here allows; that figure is an
extrapolation, not a run. Until a shared evidence budget bounds what a whole
run keeps (planned as R08), size the heap for the largest product of requests
and response size a scenario can produce. `faultora test` fixes both limits;
a runner narrows them with `--max-requests`, `--max-payload-bytes` and
`--max-evidence-bytes`, the last bounding the body kept from each response.
Nothing here establishes behaviour beyond the measured grid.
