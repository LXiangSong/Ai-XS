Ai-XS displays native agent events in the existing conversation. Android coalesces
paints without postponing their first deadline while tokens continue arriving.
Empty started activities remain visible, and partial tool input is separate from
tool execution and its final result.

Codex uses app-server item and delta notifications. Claude uses stream-json partial
messages, tool argument fragments and tool progress. The installed DSH ACP profile
projects committed assistant messages only; its `agent_*_chunk` names do not imply
that tokens are delivered before completion.

For DSH, `dsh_live_events.mjs` is loaded as a Cordis companion using a separate
per-runtime patch. It listens to `agent/assistant-stream` and forwards allowlisted
text, reasoning and tool argument frames through private ACP notifications. It
does not modify the installed CLI or expose provider replay metadata. Session IDs
filter child-agent traffic; attempt IDs and revisions isolate retries and ignore
duplicate frames. A committed-message mapping suppresses the stock ACP replay
only for content kinds already received live. Standard ACP remains the fallback
when a CLI version does not publish these transient events. Tool results and
usage accounting continue to use their native sources.

Shared conversation snapshots retain indexed reasoning deltas. Both independent
and collaborative turns consume the same normalized items. Live updates reflect
the data the agent/provider actually emits; the app does not fabricate reasoning
or simulated progress text.

Verification includes sustained-token Android scheduler tests, adapter lifecycle
and deduplication tests, real installed DSH with delayed loopback SSE for OpenAI
Completions and Anthropic Messages, and UI checks through the installed App.

Additional operation coverage: plan deltas and step updates, patch updates, turn
diffs, terminal interaction notices (without recording stdin), MCP progress,
hooks, approval review, model reroutes and retry/failure cards. Both Python modes
use realtime_events.py; native event display uses the equivalent RealtimeEvents
reducer. Delta-before-start events create an operation and late start snapshots
merge it without erasing output. Interrupted attempts settle unfinished cards.

The DSH companion observes public ShellExecution.observed offset readers inside
per-tool AsyncLocalStorage scope. It polls at 150 ms, leaves the model's output
cursor unchanged, and restores its process-local shell decorator on teardown.
No installed executable is edited. Real bash probes verify that the first stdout
line reaches the bridge during a delayed command and still reaches the model.
Claude child streams have independent block/argument buffers; task lifecycle and
tool elapsed events remain visible. Claude's CLI does not currently supply raw
stdout deltas on stream-json: these tools show execution and elapsed state until
the native result arrives. Providers that buffer responses or omit reasoning
cannot supply per-token reasoning; visible phase/elapsed state is shown instead.

Collaboration publishes first operation starts and terminal events immediately,
coalescing repeated argument/text updates at 200 ms; the UI coalesces reads at
250 ms. A 1.5 s poll remains as recovery when push events are missed. Neither
mode claims a synthetic percentage or successful completion for interrupted work.

DSH argument streams use `toolCallDraft` until a native tool lifecycle begins.
Canonical item IDs use attempt/block identity; native call IDs are aliases, so an
ID changing during generation does not create a second execution card. An
abandoned or failed attempt converts its draft to a `runtimeStatus` marked
`discarded` / 未执行. It is retained for diagnosis and excluded from tool counts.
Retries may reuse native IDs; separate attempts remain separate and two genuine
executions of the same command are never deduplicated by command text.

All three engines use TurnFileReports to inventory the selected project before
and after a turn, including shell-generated build outputs. The final fileReport
lists created/generated, modified and deleted paths independently of model prose
and native editing-tool names, persists in shared and collaboration transcripts,
and does not add tool-call counts. It reports net changes within the displayed
project scope, not exclusive authorship. Dependency/VCS/cache/runtime directories
and directory symlink targets are excluded explicitly. Scans are bounded (30,000
entries, 1.5 seconds and 16 MiB of small-file hashing per scan); incomplete scans
are labeled and cannot invent additions/deletions from missing inventory entries.
Interrupted turns retain files actually left on disk. Large outputs use metadata;
small files use content hashes to reject same-content rewrites and detect rapid
same-size edits on filesystems with coarse timestamps. No credentials are scanned
outside the selected project.

Real installed CLI verification uses two arbitrary model IDs for each supported
protocol and the native App RPC pipeline. Model-provided thinking, text, tool
start/argument/result events, a real delayed command and persisted file reports
are checked. An API/model that omits thinking or buffers SSE cannot provide
per-token thought. Codex unified exec may deliver early output only in its final
item snapshot; later chunks arrive via outputDelta. Claude stream-json currently
provides execution/elapsed state and final tool output, not raw stdout deltas.
These limits are not replaced with simulated output.
