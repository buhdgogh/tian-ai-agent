import { javaCall } from './java'
export { getDesktopAccess, saveDesktopAccess } from './java'

export type ModelProtocol = 'OPENAI_COMPATIBLE' | 'ANTHROPIC' | 'OPENAI_EMBEDDING' | 'DASHSCOPE'
export interface ModelProvider {
  id: string
  model: string
  protocol: ModelProtocol
  baseUrl: string
  priority: number
  enabled: boolean
  hasApiKey: boolean
  reasoningEffort?: string
  workspaceId?: string
  streamUsage?: boolean
}
export interface ModelSettings {
  version: string
  providers: ModelProvider[]
  openCodeFree: { models: string[]; hasApiKey: boolean }
}
export interface ModelSettingsUpdate {
  version: string
  providers: (ModelProvider & { apiKey?: string })[]
  openCodeFree: { apiKey?: string; clear: boolean }
}
export type PresetCollection = 'skills' | 'prompt-templates'
export interface AgentPreset { id: string; name: string; description: string; content: string; enabled: boolean }
export interface McpTool { name: string; description: string; enabled: boolean }
export interface McpServer { id: string; url: string; enabled: boolean; hasToken: boolean; tools: McpTool[] }
export interface McpServerUpdate { url: string; enabled: boolean; token?: string; clearToken: boolean; tools: McpTool[] }
export type ToolCredential = 'mineru' | 'search'
export type CredentialStatus = 'PERSONAL' | 'DEVELOPMENT' | 'UNCONFIGURED'

export const getModelProviders = (): Promise<ModelSettings> => javaCall('models')
export function saveModelProviders(settings: ModelSettingsUpdate): Promise<ModelSettings> {
  const providers = settings.providers.map(({ hasApiKey, ...provider }) => provider)
  return javaCall('saveModels', { body: { ...settings, providers } })
}
export const getAgentPresets = (collection: PresetCollection): Promise<AgentPreset[]> =>
  javaCall(collection === 'skills' ? 'skills' : 'prompts')
export const saveAgentPreset = (collection: PresetCollection, body: Omit<AgentPreset, 'id'>, id?: string): Promise<AgentPreset> =>
  javaCall(collection === 'skills' ? 'saveSkill' : 'savePrompt', { id, body })
export const deleteAgentPreset = (collection: PresetCollection, id: string): Promise<void> =>
  javaCall(collection === 'skills' ? 'deleteSkill' : 'deletePrompt', { id })

type ServerDto = Omit<McpServer, 'tools'> & { tools: Omit<McpTool, 'enabled'>[] }
function server(dto: ServerDto): McpServer {
  return { ...dto, tools: dto.tools.map(tool => ({ ...tool, enabled: true })) }
}
export async function getMcpServers(): Promise<McpServer[]> {
  return (await javaCall<ServerDto[]>('mcpServers')).map(server)
}
export async function discoverMcpTools(url: string, selected: McpTool[], token?: string, id?: string): Promise<McpTool[]> {
  const tools = await javaCall<Omit<McpTool, 'enabled'>[]>('discoverMcp', { id, body: { url, token: token?.trim() || undefined } })
  return tools.map(tool => ({ ...tool, enabled: selected.some(item => item.name === tool.name && item.enabled) }))
}
export async function importMcpServer(input: McpServerUpdate): Promise<McpServer> { return saveMcpServer(undefined, input) }
export async function saveMcpServer(id: string | undefined, input: McpServerUpdate): Promise<McpServer> {
  return server(await javaCall<ServerDto>('saveMcp', { id, body: {
    url: input.url, enabled: input.enabled, token: input.clearToken ? '' : input.token?.trim() || undefined,
    tools: input.tools.filter(tool => tool.enabled).map(tool => tool.name),
  } }))
}
export const deleteMcpServer = (id: string): Promise<void> => javaCall('deleteMcp', { id })
export const getToolSettings = (): Promise<Record<ToolCredential, CredentialStatus>> => javaCall('toolCredentials')
export const saveToolCredential = (key: ToolCredential, value: string): Promise<void> =>
  javaCall(key === 'mineru' ? 'saveMineru' : 'saveSearch', { body: { value } })
export const clearToolCredential = (key: ToolCredential): Promise<void> => javaCall(key === 'mineru' ? 'clearMineru' : 'clearSearch')
