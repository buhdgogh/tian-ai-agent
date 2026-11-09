<template>
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
    stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <path :d="paths[name]" />
  </svg>
</template>

<script setup>
defineProps({ name: { type: String, required: true } })

const paths = {
  brand: 'M12 2l8 4.6v10.8L12 22l-8-4.6V6.6L12 2z M4 6.6l8 4.6 8-4.6M12 11.2V22',
  agent: 'M20 11.5a8 8 0 0 1-8 8H4l1.5-3.5A8 8 0 1 1 20 11.5z M8 11.5h.01M12 11.5h.01M16 11.5h.01',
  documents: 'M5 4h5l2 2h7a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z M8 12h8m-8 4h6',
  wiki: 'M12 7V4m0 3a2 2 0 1 0 0-4 2 2 0 0 0 0 4z M12 7l-6 9m6-9l6 9M6 20a2 2 0 1 0 0-4 2 2 0 0 0 0 4z M18 20a2 2 0 1 0 0-4 2 2 0 0 0 0 4z M8 18h8',
  logs: 'M4 19V5m0 14h16M7 14l4-4 3 2 5-6',
  plus: 'M12 5v14M5 12h14',
  key: 'M15.5 13a4.5 4.5 0 1 0-4.5-4.5L4 15.5V20h4v-3h3l2.5-4.5z M16 8h.01',
  upload: 'M12 16V4m-5 5 5-5 5 5M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3',
  refresh: 'M20 7v5h-5M4 17v-5h5m-4-1a7 7 0 0 1 12-5l3 3M4 15l3 3a7 7 0 0 0 12-5',
  arrow: 'M5 12h14m-6-6 6 6-6 6'
}
</script>
