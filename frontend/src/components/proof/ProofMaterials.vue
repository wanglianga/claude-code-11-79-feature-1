<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../../lib/api.js'
import { toast } from '../../lib/store.js'
import StatusBadge from '../StatusBadge.vue'

const props = defineProps({ tick: Number })
const emit = defineEmits(['changed'])

const rows = ref([])
const batches = ref([])
const loading = ref(true)
const siteFilter = ref('ALL')
const statusFilter = ref('ALL')
const selectedNo = ref(null)
const detail = ref(null)
const uploadForm = ref({ hash: '', certChainThumbprints: '', supplementNote: '' })
const proof = ref(null)

async function load() {
  loading.value = true
  try {
    const [w, b] = await Promise.all([api.get('/proof/witnesses'), api.get('/proof/batches')])
    rows.value = w
    batches.value = b
    if (selectedNo.value) await open(selectedNo.value)
  } finally {
    loading.value = false
  }
}
onMounted(load)
watch(() => props.tick, load)

const sites = computed(() => ['ALL', ...new Set(rows.value.map((r) => r.site))])
const filtered = computed(() => rows.value.filter((r) =>
  (siteFilter.value === 'ALL' || r.site === siteFilter.value) &&
  (statusFilter.value === 'ALL' || r.status === statusFilter.value)))

function fmt(dt) {
  return dt ? new Date(dt).toISOString().replace('T', ' ').slice(0, 16) : '—'
}
function short(h) {
  return h ? h.slice(0, 14) + '…' : '—'
}

async function open(no) {
  selectedNo.value = no
  detail.value = await api.get(`/proof/witnesses/${no}`)
  proof.value = detail.value.commitmentBatchCode
    ? await api.get(`/proof/batches/${detail.value.commitmentBatchCode}/proof?signatureNo=${no}`)
    : null
}

function close() {
  selectedNo.value = null
  detail.value = null
  proof.value = null
}

async function recompile(no) {
  try {
    await api.post(`/proof/compile?signatureNo=${no}`)
    toast(`${no} 已重新编译证明状态`, 'ok')
    await load()
    emit('changed')
  } catch (e) { toast(e.message, 'bad') }
}

async function buildGraph(no) {
  try {
    await api.post(`/proof/graph/build?signatureNo=${no}`)
    toast(`${no} 的交叉签名见证关系图已生成`, 'ok')
    await open(no)
  } catch (e) { toast(e.message, 'bad') }
}

async function uploadVersion(no) {
  try {
    const f = uploadForm.value
    await api.post(`/proof/witnesses/${no}/versions`, {
      hash: f.hash,
      certChainThumbprints: f.certChainThumbprints || detail.value.versions[0].certChainThumbprints,
      supplementNote: f.supplementNote || null
    })
    toast('附件新版本已追加（原版本不可变保留）', 'ok')
    uploadForm.value = { hash: '', certChainThumbprints: '', supplementNote: '' }
    await api.post(`/proof/compile?signatureNo=${no}`)
    await api.post(`/proof/diff?signatureNo=${no}`)
    await load()
    emit('changed')
  } catch (e) { toast(e.message, 'bad') }
}

const batchOf = (no) => batches.value.find((b) => (b.leafIndex || '').split('\n').some((l) => l.startsWith(no + ':')))
</script>

<template>
  <section class="panel">
    <div class="panel-head">
      <h2>签署见证账 · 材料证明链</h2>
      <div class="spacer"></div>
      <label class="small faint">中心
        <select v-model="siteFilter" style="margin-left: 6px">
          <option v-for="s in sites" :key="s" :value="s">{{ s }}</option>
        </select>
      </label>
      <label class="small faint" style="margin-left: 10px">证明状态
        <select v-model="statusFilter" style="margin-left: 6px">
          <option value="ALL">全部</option>
          <option>VERIFIED_SIGNATURE</option>
          <option>MATERIAL_CORRUPTED</option>
          <option>IDENTITY_OR_TIME_PENDING</option>
          <option>NOT_COMPILED</option>
        </select>
      </label>
    </div>

    <div class="panel-body tight">
      <div v-if="loading" class="empty">Loading…</div>
      <table v-else>
        <thead><tr>
          <th>签署编号</th><th>伪标识 / 中心</th><th>模板</th><th>签署 / 上传（本地时钟）</th>
          <th>终端 / 批次</th><th>附件哈希</th><th>本地见证</th><th>证明状态</th><th></th>
        </tr></thead>
        <tbody>
          <tr v-for="r in filtered" :key="r.signatureNo" :class="{ selected: selectedNo === r.signatureNo }">
            <td class="mono-sm">{{ r.signatureNo }}</td>
            <td class="mono-sm">{{ r.subjectPseudonym }}<br /><span class="faint">{{ r.site }}</span></td>
            <td class="mono-sm small">{{ r.templateCode }}</td>
            <td class="mono-sm small">
              签 {{ fmt(r.signedAt) }}<br />
              传 {{ fmt(r.uploadedAt) }}
              <span v-if="r.trustedTimeResynced" class="badge pend" style="margin-left: 4px">校时</span>
            </td>
            <td class="mono-sm small">{{ r.deviceModel }}<br /><span class="faint">{{ r.deviceBatch }} · {{ r.attestationState }}</span></td>
            <td class="mono-sm faint">{{ short(r.attachmentHash) }}</td>
            <td>
              <span :class="r.localWitnessPresent ? 'badge ok' : 'badge pend'">
                {{ r.localWitnessPresent ? '有' : '无（待核）' }}
              </span>
            </td>
            <td><StatusBadge :kind="r.status" compact /></td>
            <td><button class="sm ghost" @click="open(r.signatureNo)">证据链</button></td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- detail drawer -->
    <div v-if="detail" class="panel-body stack" style="border-top: 1px solid var(--line); background: var(--panel-2)">
      <div class="row">
        <h2>{{ detail.signatureNo }} 的本地证据链</h2>
        <div class="spacer"></div>
        <button class="sm" @click="recompile(detail.signatureNo)">重新编译</button>
        <button class="sm" @click="buildGraph(detail.signatureNo)">生成见证关系图</button>
        <button class="sm ghost" @click="close">关闭</button>
      </div>

      <div class="grid cols-2">
        <div>
          <h3 style="margin-bottom: 6px">当前核验结论</h3>
          <StatusBadge :kind="detail.status" />
          <div class="small muted" style="margin-top: 6px">原因码：{{ detail.reasonCodes || '—' }}</div>
          <pre class="evidence-box">{{ detail.evidenceRefs }}</pre>
        </div>
        <div>
          <h3 style="margin-bottom: 6px">附件版本（只追加）</h3>
          <table>
            <thead><tr><th>版本</th><th>哈希</th><th>证书链</th><th>上传者 / 补录说明</th></tr></thead>
            <tbody>
              <tr v-for="v in detail.versions" :key="v.id">
                <td>v{{ v.versionNo }}</td>
                <td class="mono-sm faint">{{ short(v.hash) }}</td>
                <td class="mono-sm faint">{{ short(v.signerCertThumbprint) }}<br />{{ v.certChainThumbprints }}</td>
                <td class="small">{{ v.uploadedByRef }}<br /><span class="muted">{{ v.supplementNote || '—' }}</span></td>
              </tr>
            </tbody>
          </table>
          <div class="stack" style="margin-top: 8px">
            <label class="field"><span>新附件哈希（SHA-256）</span><input v-model="uploadForm.hash" placeholder="64 位十六进制" /></label>
            <label class="field"><span>证书链（留空沿用 v1）</span><input v-model="uploadForm.certChainThumbprints" /></label>
            <label class="field"><span>护理补录说明（哈希变化且链连续时构成合法补充）</span><input v-model="uploadForm.supplementNote" /></label>
            <div><button class="sm primary" @click="uploadVersion(detail.signatureNo)">追加附件版本并重算</button></div>
          </div>
        </div>
      </div>

      <div class="grid cols-2">
        <div>
          <h3 style="margin-bottom: 6px">结论版本（追加保留）</h3>
          <table>
            <thead><tr><th>版本</th><th>状态</th><th>原因码</th><th>摘要</th></tr></thead>
            <tbody>
              <tr v-for="c in detail.conclusions" :key="c.id" :class="{ muted: c.superseded }">
                <td>v{{ c.versionNo }}{{ c.superseded ? '（旧版保留）' : '' }}</td>
                <td><StatusBadge :kind="c.status" compact /></td>
                <td class="small">{{ c.reasonCodes }}</td>
                <td class="mono-sm faint">{{ short(c.conclusionDigest) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div>
          <h3 style="margin-bottom: 6px">差异结论与见证关系边</h3>
          <table v-if="detail.diffs.length">
            <thead><tr><th>类型</th><th>版本对</th><th>说明</th></tr></thead>
            <tbody>
              <tr v-for="d in detail.diffs" :key="d.id" :class="{ muted: d.superseded }">
                <td><StatusBadge :kind="d.kind" compact /></td>
                <td class="mono-sm">{{ d.versionPair }}</td>
                <td class="small muted">{{ d.detail }}</td>
              </tr>
            </tbody>
          </table>
          <div v-else class="empty small">无差异记录</div>
          <table v-if="detail.graphEdges.length" style="margin-top: 8px">
            <thead><tr><th>关系</th><th>目标</th><th>断裂</th></tr></thead>
            <tbody>
              <tr v-for="e in detail.graphEdges" :key="e.id">
                <td class="chip">{{ e.relation }}</td>
                <td class="mono-sm small">{{ e.targetRef }}</td>
                <td><span :class="e.broken ? 'badge bad' : 'badge ok'">{{ e.broken ? e.breakReason : '完整' }}</span></td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div v-if="proof">
        <h3 style="margin-bottom: 6px">承诺树包含证明（{{ detail.commitmentBatchCode }}）</h3>
        <div class="row wrap small">
          <span class="chip">leaf #{{ proof.leafIndex }}</span>
          <span :class="proof.rootMatches ? 'badge ok' : 'badge bad'">
            根哈希{{ proof.rootMatches ? '一致' : '不一致' }}
          </span>
          <span class="mono-sm faint">root: {{ short(proof.rootHash) }}</span>
          <span class="mono-sm faint">siblings: {{ (proof.siblings || []).length }} 条</span>
        </div>
        <pre class="evidence-box">{{ proof.leafStateIndex || '（无叶子状态附加索引）' }}</pre>
      </div>
      <div v-else class="notice small">该材料采用逐份交叉签名见证关系图方案（或尚未折叠入承诺树）。</div>
    </div>
  </section>
</template>

<style scoped>
.selected { background: var(--panel-2); }
.evidence-box {
  background: var(--bg);
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  padding: 10px;
  font-family: var(--mono);
  font-size: 11.5px;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 260px;
  overflow: auto;
  margin: 6px 0 0;
}
</style>
