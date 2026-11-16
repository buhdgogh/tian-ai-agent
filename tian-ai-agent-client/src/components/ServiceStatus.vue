<script setup lang="ts">
import type { HealthStatus } from '../api/java'

defineProps<{ state: HealthStatus }>()
</script>

<template>
  <span class="status" :data-state="state"><slot /></span>
</template>

<style scoped>
.status { display: inline-block; padding: 0.2rem 0.6rem; border-radius: 0.4rem; font-weight: 600; }
.status[data-state='available'] { background: #e8f6ee; color: #145638; }
.status[data-state='unavailable'] { background: #fcefed; color: #8b3028; }
.status[data-state='timeout'] { background: #fff3db; color: #795012; }
</style>
