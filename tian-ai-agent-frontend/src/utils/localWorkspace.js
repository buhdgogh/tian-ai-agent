const MAX_FILE_BYTES = 1024 * 1024
const PAGE_ENTRIES = 100
const PAGE_CHARS = 10_000
const SKIP_NAMES = new Set([
  '.git', '.ssh', '.aws', '.docker', '.kube', '.envrc', '.npmrc', '.netrc', '.pypirc', '.git-credentials',
  'id_rsa', 'id_ed25519', 'node_modules', 'target', '.venv'
])

const failure = (errorCode, message) => ({ ok: false, errorCode, message })

function allowedSegment(segment) {
  const name = segment.toLowerCase()
  return Boolean(name) && name !== '.' && name !== '..' && !/[. ]$/.test(name) && !/[\\/:\x00-\x1f\x7f]/.test(name)
    && !SKIP_NAMES.has(name) && name !== '.env' && !name.startsWith('.env.')
    && !/\.(pem|key|p12|pfx|jks|keystore)$/.test(name)
}

async function directoryAt(root, parts) {
  let directory = root
  for (const part of parts) directory = await directory.getDirectoryHandle(part)
  return directory
}

async function listDirectory(root, parts, path, offset) {
  const directory = await directoryAt(root, parts)
  const entries = []
  let visible = 0
  for await (const entry of directory.values()) {
    if (!allowedSegment(entry.name)) continue
    if (visible++ < offset) continue
    if (entries.length === PAGE_ENTRIES) {
      return { ok: true, path, entries, nextOffset: offset + entries.length }
    }
    entries.push({ name: entry.name, kind: entry.kind })
  }
  return { ok: true, path, entries }
}

async function readFile(root, parts, path, offset) {
  const name = parts.pop()
  const parent = await directoryAt(root, parts)
  const handle = await parent.getFileHandle(name)
  const file = await handle.getFile()
  if (file.size > MAX_FILE_BYTES) return failure('WORKSPACE_FILE_TOO_LARGE', '只支持读取不超过 1 MiB 的文本文件')
  const bytes = new Uint8Array(await file.arrayBuffer())
  if (bytes.includes(0)) return failure('WORKSPACE_BINARY_FILE', '只支持 UTF-8 文本文件')
  let text
  try {
    text = new TextDecoder('utf-8', { fatal: true }).decode(bytes)
  } catch {
    return failure('WORKSPACE_BINARY_FILE', '只支持 UTF-8 文本文件')
  }
  let end = Math.min(offset + PAGE_CHARS, text.length)
  if (end < text.length && /[\uD800-\uDBFF]/.test(text[end - 1])) end--
  const result = { ok: true, path, content: text.slice(offset, end) }
  if (end < text.length) result.nextOffset = end
  return result
}

export async function readLocalWorkspace(root, { action, path, offset }) {
  if (typeof path !== 'string' || path.length > 1024 || !Number.isSafeInteger(offset)
    || offset < 0 || offset > MAX_FILE_BYTES || !['LIST', 'READ'].includes(action)) {
    return failure('WORKSPACE_INVALID_REQUEST', '工作区读取请求无效')
  }
  const parts = path === '' ? [] : path.split('/')
  if ((action === 'READ' && !parts.length) || !parts.every(allowedSegment)) {
    return failure('WORKSPACE_PATH_DENIED', '该路径不在可读工作区范围内')
  }
  try {
    if (action === 'LIST') return await listDirectory(root, parts, path, offset)
    return await readFile(root, parts, path, offset)
  } catch (error) {
    if (error.name === 'NotAllowedError' || error.name === 'SecurityError') {
      return failure('WORKSPACE_PERMISSION_DENIED', '目录读取权限已失效，请重新授权当前工作区')
    }
    if (error.name === 'NotFoundError' || error.name === 'TypeMismatchError') {
      return failure('WORKSPACE_ENTRY_NOT_FOUND', '该文件或目录不存在')
    }
    return failure('WORKSPACE_READ_FAILED', '无法读取该文件或目录')
  }
}
