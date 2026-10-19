<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'
defineProps<{ title: string; drawer?: boolean; busy?: boolean }>()
const emit = defineEmits<{ close: [] }>()
const panel = ref<HTMLElement>()
const previous = document.activeElement as HTMLElement | null
const overflow = document.body.style.overflow
let background: { element: HTMLElement; inert: boolean }[] = []
function keydown(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); emit('close') }
  if (event.key !== 'Tab') return
  const targets = [...panel.value!.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea, a[href], [tabindex="0"]')]
  const first = targets[0], last = targets[targets.length - 1]
  if (!first) { event.preventDefault(); panel.value?.focus(); return }
  if (event.shiftKey && (document.activeElement === first || document.activeElement === panel.value)) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && (document.activeElement === last || document.activeElement === panel.value)) { event.preventDefault(); first.focus() }
}
onMounted(() => {
  background = [...document.querySelectorAll<HTMLElement>('header, footer, .api-keys > :not(.key-overlay)')].map(element => ({ element, inert: element.inert }))
  background.forEach(({ element }) => element.inert = true)
  document.body.style.overflow = 'hidden'; panel.value?.focus()
})
onBeforeUnmount(() => {
  background.forEach(({ element, inert }) => element.inert = inert)
  document.body.style.overflow = overflow
  if (previous?.isConnected) previous.focus()
})
</script>
<template>
  <div class="key-overlay" :class="{ 'is-drawer': drawer }" @keydown="keydown">
    <section ref="panel" class="key-panel" role="dialog" aria-modal="true" :aria-label="title" tabindex="-1">
      <div class="key-panel-head"><h2>{{ title }}</h2><button type="button" aria-label="关闭" title="关闭" :disabled="busy" @click="emit('close')">×</button></div>
      <slot />
    </section>
  </div>
</template>
