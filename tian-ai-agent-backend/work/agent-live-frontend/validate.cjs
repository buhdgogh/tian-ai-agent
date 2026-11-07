const fs = require('node:fs')
const { parse, compileScript, compileTemplate } = require('E:/Code/Projects/Tian-AI-Agent-main/tian-ai-agent-frontend/node_modules/@vue/compiler-sfc')
for (const name of ['Medical.vue', 'ChatRoom.vue']) {
  const { descriptor, errors } = parse(fs.readFileSync(__dirname + '/' + name, 'utf8'), { filename: name })
  if (errors.length) throw new Error(errors.join('\n'))
  const script = compileScript(descriptor, { id: name })
  const template = compileTemplate({ source: descriptor.template.content, filename: name, id: name, compilerOptions: { bindingMetadata: script.bindings } })
  if (template.errors.length) throw new Error(template.errors.join('\n'))
  console.log(name + ': compiled')
}
