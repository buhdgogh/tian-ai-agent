const { randomUUID } = require('node:crypto');

const ROUTES = {
  chat: 'ai/chat',
  rag: 'ai/chat/rag',
  agent: 'ai/dispatch/stream',
};

module.exports = class TianProvider {
  constructor({ config }) {
    this.mode = config.mode;
    this.route = ROUTES[this.mode];
    this.timeout = config.timeoutMs ?? 120_000;
    if (!Object.hasOwn(ROUTES, this.mode)) {
      throw new Error('Tian provider mode must be chat, rag or agent.');
    }
  }

  id() {
    return `tian-${this.mode}`;
  }

  async callApi(prompt) {
    const token = process.env.TIAN_EVAL_TOKEN?.trim();
    if (!token) return { error: '请设置 TIAN_EVAL_TOKEN 为工作台访问令牌。' };

    const spaceId = this.mode === 'rag' ? process.env.TIAN_EVAL_SPACE_ID?.trim() : undefined;
    if (this.mode === 'rag' && !spaceId) {
      return { error: '请设置 TIAN_EVAL_SPACE_ID 为评测知识库空间 ID。' };
    }

    const base = process.env.TIAN_EVAL_BASE_URL?.trim() || 'http://127.0.0.1:8123/api';
    try {
      const url = new URL(`${base.replace(/\/+$/, '')}/${this.route}`);
      if (!['http:', 'https:'].includes(url.protocol) || url.search || url.hash || url.username || url.password) {
        return { error: 'TIAN_EVAL_BASE_URL 应为包含 /api 的 HTTP(S) 基础地址。' };
      }
      const response = await fetch(url, {
        method: 'POST',
        redirect: 'error',
        headers: {
          Authorization: `Bearer ${token}`,
          'Content-Type': 'application/json',
          Accept: this.mode === 'agent' ? 'text/event-stream' : this.mode === 'rag' ? 'application/json' : 'text/plain',
        },
        body: JSON.stringify({ message: prompt, chatId: `pf-${randomUUID()}`, spaceId }),
        signal: AbortSignal.timeout(this.timeout),
      });
      if (!response.ok) {
        await response.body?.cancel();
        const detail = response.status === 401 || response.status === 403 ? '，请检查令牌和访问权限' : '';
        return { error: `Tian ${this.route} 返回 HTTP ${response.status}${detail}。` };
      }

      const type = response.headers.get('content-type')?.toLowerCase() ?? '';
      if (this.mode === 'agent' && type.startsWith('text/event-stream')) {
        return readDispatch(await response.text());
      }
      if (this.mode === 'rag' && type.startsWith('application/json')) {
        const { answer, sources, evidence_level: evidenceLevel } = await response.json();
        if (typeof answer === 'string' && answer.trim() && Array.isArray(sources)) {
          return { output: { answer, sources, evidenceLevel } };
        }
        return { error: 'RAG 响应缺少非空 answer 或 sources 数组。' };
      }
      if (this.mode === 'chat' && type.startsWith('text/plain')) {
        const answer = await response.text();
        return answer.trim() ? { output: answer } : { error: '普通对话返回空答案。' };
      }
      await response.body?.cancel();
      return { error: `Tian ${this.route} 返回了非预期的响应类型。` };
    } catch (error) {
      if (error.name === 'TimeoutError' || error.name === 'AbortError') {
        return { error: `Tian ${this.route} 超时（${this.timeout} ms）。` };
      }
      const code = error.cause?.code;
      return { error: `Tian ${this.route} 请求或解析失败${code ? `（${code}）` : ''}，请检查后端地址和服务日志。` };
    }
  }
};

function readDispatch(stream) {
  const result = { answer: '' };
  let done = false;
  for (const frame of stream.replace(/\r\n?/g, '\n').split('\n\n')) {
    const lines = frame.split('\n');
    const event = lines.find((line) => line.startsWith('event:'))?.slice(6).trim();
    const data = lines.filter((line) => line.startsWith('data:')).map((line) => line.slice(5).replace(/^ /, '')).join('\n');
    if (!event || !data) continue;

    const payload = JSON.parse(data);
    switch (event) {
      case 'run':
        result.runId = payload.runId;
        break;
      case 'route':
        result.route = payload;
        break;
      case 'delta':
        result.answer += payload.text;
        break;
      case 'task':
        result.taskId = payload.id;
        break;
      case 'error':
        return { error: `Agent SSE 执行失败，runId=${result.runId ?? 'unknown'}，请查看后端运行日志。` };
      case 'done':
        done = true;
        break;
    }
  }
  if (!done || !result.runId || !result.route || (!result.answer.trim() && !result.taskId)) {
    return { error: 'Agent SSE 响应缺少 run、route、done、回答或任务 ID。' };
  }
  return { output: result };
}
