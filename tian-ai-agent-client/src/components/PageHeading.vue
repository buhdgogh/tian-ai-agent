<script setup lang="ts">
defineProps<{ title: string; detail?: string }>()
</script>

<template>
  <header class="page-heading">
    <div>
      <h1>{{ title }}</h1>
      <p v-if="detail">{{ detail }}</p>
    </div>
    <slot />
  </header>
</template>
