/** Ai-XS ACP companion: forward DSH's transient frames before durable ACP output. */
import {AsyncLocalStorage} from 'node:async_hooks';
export const name = 'ai-xs-live-events';

export function apply(ctx) {
  const attempts = new Map();
  const childOwners = new Map();
  const executions = new AsyncLocalStorage();
  const send = (method, params) => process.stdout.write(JSON.stringify({jsonrpc: '2.0', method, params}) + '\n');
  const scope = session => {
    const sessionId = session.id ?? session.header?.id;
    const ancestors = [], visited = new Set([sessionId]);
    let current = session;
    for (let depth = 0; depth < 32; depth++) {
      const parent = current?.header?.parentSession;
      if (typeof parent !== 'string' || visited.has(parent)) break;
      ancestors.push(parent); visited.add(parent);
      current = ctx.get?.('sessions')?.get(parent);
      if (!current) break;
    }
    return {sessionId, ancestors};
  };
  // Observe the supported tool pipeline; never alter permissions, input or result.
  ctx.on('tools/execute', async (exec, next) => {
    if (!exec.agent) return next();
    const started = Date.now();
    const progress = () => send('aixs/toolProgress', {...scope(exec.agent.session),
      itemId: exec.callId, tool: exec.name, elapsedSeconds: Math.floor((Date.now() - started) / 1000)});
    progress();
    const timer = setInterval(progress, 1000); timer.unref();
    try { return await executions.run({...scope(exec.agent.session), itemId: exec.callId}, next); }
    finally { clearInterval(timer); }
  }, {global: true});
  ctx.on('agent/request-error', async (payload, next) => {
    const decision = await next();
    send('aixs/runtimeProgress', {...scope(payload.agent.session),
      phase: decision?.kind === 'retry' ? 'retrying' : 'waiting', detail: '模型请求失败'});
    return decision;
  }, {global: true});
  ctx.on('agent/request', (payload, next) => {
    send('aixs/runtimeProgress', {...scope(payload.agent.session), phase: 'waiting', detail: ''});
    return next();
  }, {global: true});
  ctx.on('agent/error', payload => {
    send('aixs/runtimeError', {...scope(payload.agent.session),
      message: payload.error instanceof Error ? payload.error.message : String(payload.error)});
  }, {global:true});
  ctx.on('subagent/start', info => {
    const child=ctx.get?.('agents')?.get(info.id);
    if (!child) return;
    const owner=scope(child.session);childOwners.set(info.runId,owner);
    send('aixs/childTask',{...owner,id:info.runId,status:'inProgress'});
  },{global:true});
  ctx.on('subagent/end', info => {
    const owner=childOwners.get(info.runId);if(!owner)return;
    childOwners.delete(info.runId);
    const reason=typeof info.stopReason==='string'?info.stopReason:info.stopReason?.kind;
    send('aixs/childTask',{...owner,id:info.runId,status:reason==='completed'?'completed':
      ['aborted','interrupted'].includes(reason)?'interrupted':'failed'});
  },{global:true});
  // ACP deployments may omit ctx.jobs. Observe the public shell handle directly,
  // with per-execution async context so parallel tools cannot receive each other's output.
  ctx.inject(['shell'], shellCtx => {
    const shell = shellCtx.shell, original = shell.execute;
    const timers = new Set();
    async function observedExecute(...args) {
      const owner = executions.getStore();
      const processHandle = await original.apply(this, args);
      if (!owner || !processHandle?.observed) return processHandle;
      const offsets = {stdout: 0, stderr: 0};
      const read = () => {
        for (const channel of ['stdout', 'stderr']) {
          try {
            const result = processHandle.observed[channel].readFrom(offsets[channel]);
            offsets[channel] = result.nextOffset;
            if (result.text) send('aixs/toolOutput', {...owner, delta: result.text, channel, lossy: result.lossy});
          } catch { /* An observer must never affect tool execution. */ }
        }
      };
      const timer = setInterval(read, 150); timer.unref(); timers.add(timer); read();
      const stop = () => { read(); clearInterval(timer); timers.delete(timer); };
      processHandle.done.then(stop, stop);
      return processHandle;
    }
    shell.execute = observedExecute;
    shellCtx.on('dispose', () => {
      if (shell.execute === observedExecute) shell.execute = original;
      for (const timer of timers) clearInterval(timer);
    });
  });
  ctx.on('agent/assistant-stream', ({agent, frame}) => {
    const sessionId = agent.session.id;
    if (frame.type === 'start') attempts.set(sessionId, frame.attemptId);
    let safe = {type: frame.type, attemptId: frame.attemptId, revision: frame.revision};
    if (frame.type === 'chunk') {
      const c = frame.chunk;
      // Keep credentials, provider replay envelopes, usage and internal metadata off this channel.
      if (!['block-start', 'text-delta', 'reasoning-delta', 'tool-call-delta', 'block-end'].includes(c.type)) return;
      const chunk = {type: c.type, index: c.index};
      if (c.type === 'block-start') chunk.blockType = c.blockType;
      else if (c.type === 'text-delta' || c.type === 'reasoning-delta') chunk.text = c.text;
      else if (c.type === 'tool-call-delta') Object.assign(chunk, {id: c.id, name: c.name, argumentsDelta: c.argumentsDelta});
      else {
        const b = c.block;
        if (!['text', 'reasoning', 'tool-call'].includes(b.type)) return;
        chunk.block = b.type === 'tool-call'
          ? {type: b.type, id: b.id, name: b.name, arguments: b.arguments}
          : {type: b.type, text: b.text};
      }
      safe.chunk = chunk;
    } else if (frame.type === 'end') {
      safe.outcome = frame.outcome;
      attempts.delete(sessionId);
    } else if (frame.type !== 'start') return;
    send('aixs/assistantStream', {...scope(agent.session), frame: safe});
  }, {global: true});
  ctx.on('session/event', (session, event) => {
    const owner = scope(session);
    const attemptId = attempts.get(session.header.id);
    if (event.type === 'assistant/message' && attemptId) send('aixs/assistantCommit', {
      ...owner, attemptId, messageId: event.data.message.id
    });
    if (owner.ancestors.length) {
      if (event.type === 'turn/start' || event.type === 'turn/end') send('aixs/childLifecycle', {
        ...owner, type: event.type, turn: event.data.turn, reason: event.data.reason
      });
      else if (event.type === 'request/context') send('aixs/childModel', {
        ...owner, model: event.data.model, contextWindow: event.data.contextWindow
      });
      else if (event.type === 'tool/call') {
        let input = event.data.arguments;
        try { input = JSON.parse(input); } catch { /* Preserve malformed tool arguments. */ }
        send('aixs/childUpdate', {...owner, update: {sessionUpdate: 'tool_call',
          toolCallId: event.data.callId, title: event.data.name, rawInput: input, status: 'in_progress'}});
      } else if (event.type === 'tool/result') {
        const message = event.data.message;
        send('aixs/childUpdate', {...owner, update: {sessionUpdate: 'tool_call_update',
          toolCallId: message.toolCallId, status: message.isError ? 'failed' : 'completed',
          content: message.content.map(block => ({type:'text', text:block.type === 'text' ? block.text :
            block.type.includes('image') ? '[图片结果]' : block.type.includes('audio') ? '[音频结果]' : '[工具结果：'+block.type+']'}))}});
      } else if (event.type === 'assistant/message') {
        for (const block of event.data.message.content) {
          if (!['text','reasoning'].includes(block.type)) continue;
          send('aixs/childUpdate', {...owner, update: {
            sessionUpdate: block.type === 'reasoning' ? 'agent_thought_chunk' : 'agent_message_chunk',
            messageId: event.data.message.id, content: {type:'text', text:block.text}
          }});
        }
      }
    }
    if (event.type === 'compaction/start' || event.type === 'compaction/end') send('aixs/runtimeProgress', {
      ...owner, phase: event.type.endsWith('/start') ? 'compacting' : 'waiting',
      detail: event.type.endsWith('/start') ? '整理上下文' : '上下文整理结束'
    });
    if (event.type !== 'assistant/message') return;
  }, {global: true});
}
