<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { init, use, type EChartsType } from 'echarts/core'
import { GraphChart, type GraphSeriesOption } from 'echarts/charts'
import { LabelLayout } from 'echarts/features'
import { CanvasRenderer } from 'echarts/renderers'
import type { WikiGraph } from '../api/workbench'

use([GraphChart, LabelLayout, CanvasRenderer])

const props = defineProps<{ nodes: WikiGraph['nodes']; edges: WikiGraph['edges']; selectedId: string }>()
const emit = defineEmits<{ pick: [id: string] }>()
const surface = ref<HTMLDivElement>()
const seriesId = 'wiki-network'
const minZoom = 0.3
const maxZoom = 4
const colors: Record<string, string> = { '实体': '#176eaa', '概念': '#178269', '综合': '#8750a3' }
let chart: EChartsType | undefined
let resizeObserver: ResizeObserver | undefined
let nodeIndices = new Map<string, number>()

function render() {
  if (!chart) return
  const degree = new Map<string, number>()
  for (const { source, target } of props.edges) {
    degree.set(source, (degree.get(source) ?? 0) + 1)
    degree.set(target, (degree.get(target) ?? 0) + 1)
  }
  const data = props.nodes.map(node => {
    const count = degree.get(node.id) ?? 0
    return {
      id: node.id, name: node.title,
      symbolSize: 14 + Math.min(18, Math.sqrt(count) * 4),
      itemStyle: { color: colors[node.kind] ?? '#647582' },
      label: { show: props.nodes.length <= 35 || count >= 5 },
    }
  })
  nodeIndices = new Map(data.map((node, index) => [node.id, index]))
  const graph: GraphSeriesOption = {
    id: seriesId,
    type: 'graph',
    layout: 'force',
    data,
    links: props.edges.map(({ source, target }) => ({ source, target })),
    left: '12%', top: '12%', right: '12%', bottom: '12%',
    roam: true,
    draggable: true,
    scaleLimit: { min: minZoom, max: maxZoom },
    force: {
      initLayout: 'circular', repulsion: 150, gravity: 0.11,
      edgeLength: [65, 115], friction: 0.55,
    },
    edgeSymbol: ['none', 'arrow'],
    edgeSymbolSize: 6,
    lineStyle: { color: '#526c78', width: 1, opacity: 0.48 },
    label: {
      position: 'bottom', distance: 6, color: '#29414b', fontSize: 12,
      width: 124, overflow: 'truncate',
    },
    labelLayout: { hideOverlap: true },
    emphasis: { focus: 'adjacency', label: { show: true }, lineStyle: { color: '#176eaa', opacity: 1, width: 2 } },
    blur: { lineStyle: { opacity: 0.09 }, label: { opacity: 0.22 } },
    selectedMode: 'single',
    select: { label: { show: true, fontWeight: 700 }, itemStyle: { borderColor: '#182d38', borderWidth: 3 } },
  }
  chart.setOption({ series: [graph] }, { notMerge: true })
  selectNode(props.selectedId)
}

function selectNode(id: string, previous?: string) {
  if (previous) {
    const index = nodeIndices.get(previous)
    if (index !== undefined) chart?.dispatchAction({ type: 'unselect', seriesId, dataIndex: index })
  }
  const index = nodeIndices.get(id)
  if (index !== undefined) chart?.dispatchAction({ type: 'select', seriesId, dataIndex: index })
}

function zoom(ratio: number) {
  const option = chart?.getOption() as { series?: Array<{ zoom?: number }> } | undefined
  const next = Math.min(maxZoom, Math.max(minZoom, (option?.series?.[0]?.zoom ?? 1) * ratio))
  chart?.setOption({ series: [{ id: seriesId, zoom: next }] })
}

onMounted(() => {
  if (!surface.value) return
  chart = init(surface.value)
  chart.on('click', { dataType: 'node' }, event => {
    const node = event.data as { id?: string } | undefined
    if (node?.id) emit('pick', node.id)
  })
  resizeObserver = new ResizeObserver(() => chart?.resize())
  resizeObserver.observe(surface.value)
  render()
})
watch(() => [props.nodes, props.edges], render, { flush: 'post' })
watch(() => props.selectedId, selectNode)
onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  chart?.dispose()
  chart = undefined
})
</script>

<template>
  <div class="network">
    <div ref="surface" class="surface" role="img" aria-label="Wiki 节点与引用连线" />
    <div class="controls">
      <button type="button" aria-label="缩小图谱" @click="zoom(0.8)">−</button>
      <button type="button" aria-label="放大图谱" @click="zoom(1.25)">+</button>
      <button type="button" @click="render">重置视图</button>
    </div>
    <p class="hint">拖动平移 · 滚轮缩放 · 点击节点查看页面</p>
  </div>
</template>

<style scoped>
.network { position: relative; height: clamp(310px, 56vh, 720px); min-width: 0; overflow: hidden; border: 1px solid var(--line); border-radius: .65rem; background: #f8fbfc; }
.surface { position: absolute; inset: 0; touch-action: none; }
.controls { position: absolute; top: .5rem; right: .5rem; display: flex; gap: .25rem; }
.controls button { min-height: 30px; min-width: 30px; padding: .2rem .45rem; font-size: .85rem; }
.hint { position: absolute; bottom: .5rem; left: .65rem; color: var(--muted); font-size: .8rem; pointer-events: none; }
</style>
