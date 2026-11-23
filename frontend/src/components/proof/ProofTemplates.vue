<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../../lib/api.js'
import { toast } from '../../lib/store.js'
import StatusBadge from '../StatusBadge.vue'

const props = defineProps({ tick: Number })
const emit = defineEmits(['changed'])

const templates = ref([])
const siteFilter = ref('ALL')
const loading = ref(true)
const showForm = ref(false)
const busy = ref(false)
const form = ref({
  code: 'TPL-NEW-001', siteCode: 'CN-01', language: 'zh-CN', ageBand: 'ADULT',
  protocolVersion: 'v2.0', title: '', publishedFrom: '', publishedTo: '',
  status: 'PUBLISHED', fieldSummary: 'DEMOG,SIGNATURE_DATE,CONSENT_PURPOSES,REGION', note: ''
})

async function load() {
  loading.value = true
  try {
    templates.value = await api.get('/proof/templates')
  } finally {
    loading.value = false
  }
}
onMounted(load)
watch(() => props.tick, load)

const sites = computed(() => ['ALL', ...new Set(templates.value.map((t) => t.siteCode))])
const rows = computed(() => templates.value.filter((t) => siteFilter.value === 'ALL' || t.siteCode === siteFilter.value))

function fmt(dt) {
  return dt ? new Date(dt).toISOString().replace('T', ' ').slice(0, 16) : '（开放区间）'
}

async function save() {
  busy.value = true
  try {
    await api.post('/proof/templates', {
      ...form.value,
      publishedFrom: form.value.publishedFrom ? new Date(form.value.publishedFrom).toISOString() : null,
      publishedTo: form.value.publishedTo ? new Date(form.value.publishedTo).toISOString() : null
    })
    toast(`模板区间 ${form.value.code} 已登记`, 'ok')
    showForm.value = false
    await load()
    emit('changed')
  } catch (e) {
    toast(e.message, 'bad')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="panel">
    <div class="panel-head">
      <h2>模板登记簿 · 发布区间 / 字段摘要 / 撤销状态</h2>
      <div class="spacer"></div>
      <label class="small faint">中心
        <select v-model="siteFilter" style="margin-left: 6px">
          <option v-for="s in sites" :key="s" :value="s">{{ s }}</option>
        </select>
      </label>
      <button class="sm" style="margin-left: 10px" @click="showForm = !showForm">{{ showForm ? '收起' : '登记新区间' }}</button>
    </div>

    <div v-if="showForm" class="panel-body stack" style="border-bottom: 1px solid var(--line)">
      <div class="grid cols-2">
        <label class="field"><span>模板代码</span><input v-model="form.code" /></label>
        <label class="field"><span>中心</span><input v-model="form.siteCode" /></label>
        <label class="field"><span>语言</span><input v-model="form.language" /></label>
        <label class="field"><span>年龄段</span>
          <select v-model="form.ageBand"><option>ADULT</option><option>ADOLESCENT</option><option>CHILD</option></select>
        </label>
        <label class="field"><span>方案修订</span><input v-model="form.protocolVersion" /></label>
        <label class="field"><span>状态</span>
          <select v-model="form.status"><option>PUBLISHED</option><option>SUPERSEDED</option><option>REVOKED</option></select>
        </label>
        <label class="field"><span>发布起始</span><input v-model="form.publishedFrom" type="datetime-local" /></label>
        <label class="field"><span>发布结束（可空）</span><input v-model="form.publishedTo" type="datetime-local" /></label>
      </div>
      <label class="field"><span>标题</span><input v-model="form.title" /></label>
      <label class="field"><span>字段摘要（逗号分隔，同时存哈希）</span><input v-model="form.fieldSummary" /></label>
      <label class="field"><span>备注</span><input v-model="form.note" /></label>
      <div class="row"><button class="primary" :disabled="busy" @click="save">保存区间（不覆盖历史）</button></div>
    </div>

    <div class="panel-body tight">
      <div v-if="loading" class="empty">Loading…</div>
      <table v-else>
        <thead><tr>
          <th>模板代码</th><th>中心</th><th>语言</th><th>年龄段</th><th>方案修订</th>
          <th>发布区间 [from, to)</th><th>状态</th><th>字段摘要（哈希已固化）</th>
        </tr></thead>
        <tbody>
          <tr v-for="t in rows" :key="t.code">
            <td class="mono-sm">{{ t.code }}<br /><span class="small faint">{{ t.title }}</span></td>
            <td class="mono-sm">{{ t.siteCode }}</td>
            <td>{{ t.language }}</td>
            <td>{{ t.ageBand }}</td>
            <td class="mono-sm">{{ t.protocolVersion }}</td>
            <td class="mono-sm small">{{ fmt(t.publishedFrom) }}<br />→ {{ fmt(t.publishedTo) }}</td>
            <td><StatusBadge :kind="t.status" compact /></td>
            <td class="small muted" style="max-width: 320px">
              {{ t.fieldSummary }}
              <div class="mono-sm faint" style="margin-top: 4px">sha256:{{ t.fieldSummaryHash.slice(0, 18) }}…</div>
            </td>
          </tr>
        </tbody>
      </table>
      <div class="notice small" style="margin-top: 10px">
        模板更替只关闭旧区间并打开新区间，撤销以 REVOKED 状态与撤销时间显式记录；编译器按签署当时所在的区间判定，
        离线签署早于发布且设备可信时间来自后续校时时输出“时间待核”，从不用上传时间回填签署时点。
      </div>
    </div>
  </section>
</template>
