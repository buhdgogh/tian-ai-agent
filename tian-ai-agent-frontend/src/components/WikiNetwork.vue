<template>
  <div class="network">
    <div ref="surface" class="surface" role="img" aria-label="Wiki 页面引用关系网络"></div>
    <div class="controls">
      <button type="button" aria-label="缩小图谱" @click="zoom(1 / 1.25)">−</button>
      <button type="button" aria-label="放大图谱" @click="zoom(1.25)">+</button>
      <button type="button" class="reset" @click="resetView">重置视图</button>
    </div>
    <p class="hint">拖动平移 · 滚轮缩放 · 悬停查看引用</p>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { init, use } from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { LabelLayout } from 'echarts/features'
import { CanvasRenderer } from 'echarts/renderers'

use([GraphChart, LabelLayout, CanvasRenderer])

const props = defineProps({
  nodes: { type: Array, required: true },
  edges: { type: Array, required: true },
  selected: { type: String, default: '' },
  kinds: { type: Object, required: true }
})
const emit = defineEmits(['pick'])
const surface = ref(null)
const seriesId = 'wiki-network'
const minZoom = 0.3
const maxZoom = 4
let chart
let resizeObserver
let nodeIndices = new Map()

function defaultZoom() {
  return props.nodes.length <= 18 && chart.getWidth() >= 640 ? 1.4 : 1
}

function render() {
  if (!chart) return
  const incoming = new Map()
  for (const { target } of props.edges) incoming.set(target, (incoming.get(target) || 0) + 1)
  const data = props.nodes.map(node => ({
    id: node.id,
    name: node.title,
    symbolSize: 9 + Math.min(19, Math.sqrt(incoming.get(node.id) || 0) * 4),
    itemStyle: { color: props.kinds[node.kind]?.color || '#b9bdca' },
    label: { show: props.nodes.length <= 30 || (incoming.get(node.id) || 0) >= 5 }
  }))
  nodeIndices = new Map(data.map((node, index) => [node.id, index]))
  chart.setOption({
    series: [{
      id: seriesId,
      type: 'graph',
      layout: 'force',
      zoom: defaultZoom(),
      data,
      links: props.edges,
      left: '12%',
      top: '12%',
      right: '12%',
      bottom: '12%',
      roam: true,
      draggable: true,
      nodeScaleRatio: 0.7,
      scaleLimit: { min: minZoom, max: maxZoom },
      force: {
        initLayout: 'circular',
        repulsion: 140,
        gravity: 0.1,
        edgeLength: [65, 105],
        friction: 0.55
      },
      lineStyle: { color: '#89909e', width: 1, opacity: 0.35 },
      label: {
        position: 'bottom',
        distance: 5,
        fontSize: 12,
        color: '#d7d9e1',
        width: 120,
        overflow: 'truncate'
      },
      labelLayout: { hideOverlap: true },
      emphasis: {
        focus: 'adjacency',
        label: { show: true, color: '#fff' },
        lineStyle: { color: '#b7a6ff', opacity: 0.95, width: 1.8 }
      },
      blur: { lineStyle: { opacity: 0.09 }, label: { opacity: 0.2 } },
      selectedMode: 'single',
      select: {
        label: { show: true, color: '#fff' },
        itemStyle: { borderColor: '#fff', borderWidth: 2 }
      }
    }]
  }, { notMerge: true })
  selectNode(props.selected)
}

function selectNode(id, previous) {
  if (!chart) return
  const oldIndex = nodeIndices.get(previous)
  if (oldIndex !== undefined) chart.dispatchAction({ type: 'unselect', seriesId, dataIndex: oldIndex })
  const index = nodeIndices.get(id)
  if (index !== undefined) chart.dispatchAction({ type: 'select', seriesId, dataIndex: index })
}

function zoom(factor) {
  const current = chart?.getOption()?.series?.[0]?.zoom ?? 1
  const next = Math.max(minZoom, Math.min(maxZoom, current * factor))
  chart?.setOption({ series: [{ id: seriesId, zoom: next }] })
}

function resetView() {
  chart?.setOption({ series: [{ id: seriesId, center: null, zoom: defaultZoom() }] })
}

onMounted(() => {
  chart = init(surface.value)
  chart.on('click', { dataType: 'node' }, event => emit('pick', event.data.id))
  chart.getZr().on('click', event => {
    if (!event.target) emit('pick', '')
  })
  resizeObserver = new ResizeObserver(() => chart?.resize())
  resizeObserver.observe(surface.value)
  render()
})

watch(() => [props.nodes, props.edges], render, { flush: 'post' })
watch(() => props.selected, selectNode)

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  chart?.dispose()
  chart = null
})
</script>

<style scoped>
.network { position:relative; height:clamp(400px, 67vh, 780px); min-width:0; overflow:hidden; }
.surface { position:absolute; inset:0; touch-action:none; }
.controls {
  position:absolute;
  top:12px;
  right:12px;
  display:flex;
  gap:4px;
  padding:4px;
  border:1px solid #3e414b;
  border-radius:9px;
  background:#25272f;
}
.controls button {
  min-width:32px;
  min-height:30px;
  padding:0 6px;
  border:0;
  border-radius:5px;
  color:#e5e7ef;
  background:transparent;
  font-size:16px;
}
.controls .reset { padding-inline:8px; font-size:12px; }
.controls button:hover { background:#3a3d49; }
.controls button:focus-visible { outline-color:#beaaff; outline-offset:-2px; }
.hint { position:absolute; left:13px; bottom:11px; pointer-events:none; color:#a8aab6; font-size:11px; }
@media (max-width:640px) { .network { height:clamp(340px, 57vh, 540px); } }
</style>
