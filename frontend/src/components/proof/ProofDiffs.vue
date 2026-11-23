<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../../lib/api.js'
import { toast } from '../../lib/store.js'
import StatusBadge from '../StatusBadge.vue'

const props = defineProps({ tick: Number })
const emit = defineEmits(['changed'])

const diffs = ref([])
const batches = ref([])
const witnesses = ref([])
const loading = ref(true)
const kindFilter = ref('ALL')
const foldSite = ref('CN-01')
const annotateForm = ref({ batchCode: '', signatureNo: '', state: 'REVOKED', reason: '' })

async function load() {
  loading.value = true
  try {
    const [d, b, w] = await Promise.all([
      api.get('/proof/diffs'), api.get('/proof/batches'), api.get('/proof/witnesses')
    ])
    diffs.value = d
    batches.value = b
    witnesses.value = w
  } finally {
    loading.value = false
  }
}
onMounted(load)
watch(() => props.tick, load)

const kinds = computed(() => ['ALL', ...new Set(diffs.value.map((d) => d.kind))])
const rows = computed(() => diffs.value.filter((d) => kindFilter.value === 'ALL' || d.kind === kindFilter.value))
const sites = computed(() => [...new Set(witnesses.value.map((w) => w.site))])

function short(h) { return h ? h.slice(0, 14) + '…' : '—' }
function fmt(dt) { return dt ? new Date(dt).toISOString().replace('T', ' ').slice(0, 16) : '—' }

async function fold() {
  try {
    const b = await api.post('/proof/batches/fold', { siteCode: foldSite.value, bucketDay: null })
    toast(`承诺批次 ${b.code} 已封存，叶子 ${b.leafCount} 个，根哈希 ${b.rootHash.slice(0, 16)}…`, 'ok')
    await load()
    emit('changed')
  } catch (e) { toast(e.message, 'bad') }
}

async function annotate() {
  try {
    await api.post('/proof/batches/annotate', { ...annotateForm.value })
    toast('叶子状态附加索引已写入；封存根哈希保持不变', 'ok')
    annotateForm.value = { ...annotateForm.value, reason: '' }
    await load()
  } catch (e) { toast(e.message, 'bad') }
}
</script>

<template>
  <div class="stack">
    <section class="panel">
      <div class="panel-head">
        <h2>篡改差异器 · 附件版本 / 哈希 / 证书链比较</h2>
        <div class="spacer"></div>
        <label class="small faint">差异类型
          <select v-model="kindFilter" style="margin-left: 6px">
            <option v-for="k in kinds" :key="k" :value="k">{{ k }}</option>
          </select>
        </label>
      </div>
      <div class="panel-body tight">
        <div v-if="loading" class="empty">Loading…</div>
        <table v-else>
          <thead><tr>
            <th>签署编号</th><th>分类</th><th>版本对</th><th>哈希变化</th><th>证书链连续</th>
            <th>补录说明</th><th>前/现哈希</th><th>说明</th>
          </tr></thead>
          <tbody>
            <tr v-for="d in rows" :key="d.id">
              <td class="mono-sm">{{ d.signatureNo }}<br /><span class="small faint">{{ d.subjectPseudonyms || '' }}</span></td>
              <td><StatusBadge :kind="d.kind" compact /></td>
              <td class="mono-sm">{{ d.versionPair }}</td>
              <td><span :class="d.hashChanged ? 'badge bad' : 'badge ok'">{{ d.hashChanged ? '已变化' : '相同' }}</span></td>
              <td><span :class="d.chainContinuous ? 'badge ok' : 'badge bad'">{{ d.chainContinuous ? '连续' : '断裂' }}</span></td>
              <td><span :class="d.supplementNotePresent ? 'badge ok' : 'badge warn'">{{ d.supplementNotePresent ? '有' : '无' }}</span></td>
              <td class="mono-sm faint small">{{ short(d.priorHash) }}<br />{{ short(d.currentHash) }}</td>
              <td class="small muted" style="max-width: 380px">{{ d.detail }}</td>
            </tr>
          </tbody>
        </table>
        <div class="notice small" style="margin-top: 10px">
          同一监护关系证明被两个伪标识在重叠签署窗口引用时，差异器生成“身份关联异常”而不会合并材料；哈希变化但证书链连续且有护理补录说明时，
          原材料保留、新版本列为待复核补充；相同哈希重传只计一次（重复上传）。
        </div>
      </div>
    </section>

    <section class="panel">
      <div class="panel-head">
        <h2>可追加承诺树 · 按中心/按日折叠的存在性证明</h2>
        <div class="spacer"></div>
        <label class="small faint">中心
          <select v-model="foldSite" style="margin-left: 6px">
            <option v-for="s in sites" :key="s" :value="s">{{ s }}</option>
          </select>
        </label>
        <button class="sm primary" style="margin-left: 10px" @click="fold">折叠未入树材料</button>
      </div>
      <div class="panel-body tight">
        <table>
          <thead><tr>
            <th>批次</th><th>中心 / 日桶</th><th>叶子数</th><th>封存根哈希</th><th>叶子状态附加索引</th>
          </tr></thead>
          <tbody>
            <tr v-for="b in batches" :key="b.code">
              <td class="mono-sm">{{ b.code }}</td>
              <td class="mono-sm small">{{ b.siteCode }} · D{{ b.bucketDay }}</td>
              <td class="num">{{ b.leafCount }}</td>
              <td class="mono-sm faint">{{ short(b.rootHash) }}</td>
              <td class="small muted" style="max-width: 420px; white-space: pre-wrap">{{ b.leafStateIndex || '—' }}</td>
            </tr>
          </tbody>
        </table>
        <div class="grid cols-2" style="margin-top: 12px">
          <label class="field"><span>批次代码</span>
            <input v-model="annotateForm.batchCode" :placeholder="batches[0]?.code || 'CBT-…'" />
          </label>
          <label class="field"><span>签署编号</span><input v-model="annotateForm.signatureNo" placeholder="SIG-…" /></label>
          <label class="field"><span>叶子状态（撤销/重传的额外索引，不改根）</span>
            <select v-model="annotateForm.state">
              <option>REVOKED</option><option>REPLACED_PENDING</option><option>ACTIVE</option>
            </select>
          </label>
          <label class="field"><span>原因</span><input v-model="annotateForm.reason" /></label>
        </div>
        <div style="margin-top: 8px"><button class="sm" @click="annotate">追加叶子状态索引</button></div>
      </div>
    </section>
  </div>
</template>
