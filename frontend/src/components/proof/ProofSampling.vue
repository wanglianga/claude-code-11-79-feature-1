<script setup>
import { onMounted, ref, watch } from 'vue'
import { api } from '../../lib/api.js'
import { toast } from '../../lib/store.js'

const props = defineProps({ tick: Number })
const emit = defineEmits(['changed'])

const plans = ref([])
const results = ref([])
const loading = ref(true)
const showPlan = ref(false)
const planForm = ref({
  siteCode: 'CN-01', deviceModel: 'TAB-CN01-A2', deviceBatch: '',
  anomalyPattern: 'ROUTINE_PROOF_AUDIT', confidenceLevel: 0.95
})
const resultForm = ref({
  planCode: '', reviewedCount: 2, failedCount: 0,
  distortionsObserved: '', attestationFailure: false, note: ''
})

async function load() {
  loading.value = true
  try {
    const m = await api.get('/proof/plans')
    plans.value = m.plans
    results.value = m.results
    if (!resultForm.value.planCode && plans.value[0]) resultForm.value.planCode = plans.value[0].code
  } finally {
    loading.value = false
  }
}
onMounted(load)
watch(() => props.tick, load)

const PATTERNS = [
  'ROUTINE_PROOF_AUDIT', 'HASH_SUBSTITUTION', 'CERT_CHAIN_BREAK',
  'IDENTITY_LINK_ANOMALY', 'TIMESTAMP_BACKFILL', 'TEMPLATE_MISALIGNMENT', 'BATCH_ATTESTATION_FAILURE'
]

function fmt(dt) { return dt ? new Date(dt).toISOString().replace('T', ' ').slice(0, 16) : '—' }

async function createPlan() {
  try {
    const p = await api.post('/proof/plans', { ...planForm.value })
    toast(`计划 ${p.code} 已发布：总体 ${p.populationSize}，最少复核样本 ${p.sampleSize}，可区分 ${p.expectedDistortions}`, 'ok')
    showPlan.value = false
    resultForm.value.planCode = p.code
    await load()
    emit('changed')
  } catch (e) { toast(e.message, 'bad') }
}

async function recordResult() {
  try {
    const r = await api.post('/proof/plans/results', {
      ...resultForm.value,
      distortionsObserved: resultForm.value.distortionsObserved
        .split(',').map((s) => s.trim()).filter(Boolean)
    })
    toast(`抽检结果 ${r.code} 已记录`, r.attestationFailure ? 'warn' : 'ok')
    await load()
    emit('changed')
  } catch (e) { toast(e.message, 'bad') }
}
</script>

<template>
  <div class="stack">
    <section class="panel">
      <div class="panel-head">
        <h2>抽检计划器 · 按中心 / 终端型号 / 异常模式的最少复核样本</h2>
        <div class="spacer"></div>
        <button class="sm primary" @click="showPlan = !showPlan">{{ showPlan ? '收起' : '新建抽检计划' }}</button>
      </div>

      <div v-if="showPlan" class="panel-body stack" style="border-bottom: 1px solid var(--line)">
        <div class="grid cols-2">
          <label class="field"><span>中心</span><input v-model="planForm.siteCode" /></label>
          <label class="field"><span>终端型号（* 为全部）</span><input v-model="planForm.deviceModel" /></label>
          <label class="field"><span>终端批次（可空）</span><input v-model="planForm.deviceBatch" /></label>
          <label class="field"><span>异常模式</span>
            <select v-model="planForm.anomalyPattern">
              <option v-for="p in PATTERNS" :key="p" :value="p">{{ p }}</option>
            </select>
          </label>
          <label class="field"><span>置信水平</span>
            <input v-model.number="planForm.confidenceLevel" type="number" step="0.01" min="0.5" max="0.999" />
          </label>
        </div>
        <div><button class="primary" @click="createPlan">计算最少样本并发布</button></div>
      </div>

      <div class="panel-body tight">
        <div v-if="loading" class="empty">Loading…</div>
        <table v-else>
          <thead><tr>
            <th>计划</th><th>中心 / 终端</th><th>异常模式</th><th>总体</th><th>最少样本</th>
            <th>预期可区分失真类型</th><th>状态</th><th>扩围范围 / 被谁取代</th>
          </tr></thead>
          <tbody>
            <tr v-for="p in plans" :key="p.code" :class="{ muted: !p.active }">
              <td class="mono-sm">{{ p.code }}</td>
              <td class="mono-sm small">{{ p.siteCode }}<br />{{ p.deviceModel }} / {{ p.deviceBatch || '—' }}</td>
              <td class="chip">{{ p.anomalyPattern }}</td>
              <td class="num">{{ p.populationSize }}</td>
              <td class="num"><strong>{{ p.sampleSize }}</strong></td>
              <td class="small muted" style="max-width: 300px">{{ p.expectedDistortions }}</td>
              <td><span :class="p.active ? 'badge ok' : 'badge warn'">{{ p.active ? '生效中' : '已被取代' }}</span></td>
              <td class="small muted" style="max-width: 280px">
                {{ p.uncertaintyScope || '—' }}<span v-if="p.supersededBy"><br />→ {{ p.supersededBy }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section class="panel">
      <div class="panel-head"><h2>录入抽检结果（终端批次完整性声明失效时自动扩围并保留旧结论版本）</h2></div>
      <div class="panel-body stack">
        <div class="grid cols-2">
          <label class="field"><span>计划</span>
            <select v-model="resultForm.planCode">
              <option v-for="p in plans.filter((x) => x.active)" :key="p.code" :value="p.code">{{ p.code }}</option>
            </select>
          </label>
          <label class="field"><span>复核数 / 失败数</span>
            <div class="row">
              <input v-model.number="resultForm.reviewedCount" type="number" min="0" />
              <input v-model.number="resultForm.failedCount" type="number" min="0" />
            </div>
          </label>
          <label class="field"><span>观察到的失真类型（逗号分隔）</span>
            <input v-model="resultForm.distortionsObserved" placeholder="ATTESTATION_FAILURE,HASH_SUBSTITUTION" />
          </label>
          <label class="field"><span>终端批次完整性声明失效？</span>
            <select v-model="resultForm.attestationFailure">
              <option :value="false">否</option>
              <option :value="true">是 —— 扩大该批材料的证明不确定范围</option>
            </select>
          </label>
          <label class="field" style="grid-column: 1 / -1"><span>备注</span><input v-model="resultForm.note" /></label>
        </div>
        <div><button class="primary" @click="recordResult">记录抽检结果</button></div>
      </div>
    </section>

    <section class="panel">
      <div class="panel-head"><h2>抽检结果历史</h2></div>
      <div class="panel-body tight">
        <table>
          <thead><tr>
            <th>结果</th><th>计划</th><th>中心 / 批次</th><th>复核/失败</th><th>失效</th>
            <th>扩围签署编号</th><th>保留的旧结论版本</th><th>摘要</th><th>时间</th>
          </tr></thead>
          <tbody>
            <tr v-for="r in results" :key="r.code">
              <td class="mono-sm">{{ r.code }}</td>
              <td class="mono-sm small">{{ r.planCode }}</td>
              <td class="mono-sm small">{{ r.siteCode }}<br />{{ r.deviceBatch || '—' }}</td>
              <td class="num">{{ r.reviewedCount }} / {{ r.failedCount }}</td>
              <td><span :class="r.attestationFailure ? 'badge bad' : 'badge ok'">{{ r.attestationFailure ? '是' : '否' }}</span></td>
              <td class="small muted" style="max-width: 220px">{{ r.widenedSignatureNos || '—' }}</td>
              <td class="small mono-sm" style="max-width: 220px">{{ r.retainedConclusionRefs || '—' }}</td>
              <td class="small muted" style="max-width: 300px">{{ r.summary }}</td>
              <td class="mono-sm faint">{{ fmt(r.runAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>
