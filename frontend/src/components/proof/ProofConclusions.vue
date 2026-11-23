<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../../lib/api.js'
import StatusBadge from '../StatusBadge.vue'

const props = defineProps({ tick: Number })

const conclusions = ref([])
const graph = ref(null)
const loading = ref(true)
const expanded = ref(null)
const statusFilter = ref('ALL')

async function load() {
  loading.value = true
  try {
    const [c, g] = await Promise.all([api.get('/proof/conclusions'), api.get('/proof/graph')])
    conclusions.value = c
    graph.value = g
  } finally {
    loading.value = false
  }
}
onMounted(load)
watch(() => props.tick, load)

const rows = computed(() => conclusions.value.filter(
  (c) => statusFilter.value === 'ALL' || c.status === statusFilter.value))

function toggle(no) {
  expanded.value = expanded.value === no ? null : no
}
function short(h) { return h ? h.slice(0, 16) + '…' : '—' }
function fmt(dt) { return dt ? new Date(dt).toISOString().replace('T', ' ').slice(0, 16) : '—' }

const relationColor = { GUARDIAN: 'warn', DEVICE: 'local', NURSE: 'ok' }
</script>

<template>
  <div class="stack">
    <section class="panel">
      <div class="panel-head">
        <h2>本地域核验结论 · 三状态及各自依赖的本地证据</h2>
        <div class="spacer"></div>
        <label class="small faint">状态
          <select v-model="statusFilter" style="margin-left: 6px">
            <option value="ALL">全部</option>
            <option>VERIFIED_SIGNATURE</option>
            <option>MATERIAL_CORRUPTED</option>
            <option>IDENTITY_OR_TIME_PENDING</option>
          </select>
        </label>
      </div>
      <div class="panel-body tight">
        <div v-if="loading" class="empty">Loading…</div>
        <table v-else>
          <thead><tr>
            <th>签署编号</th><th>结论版本</th><th>证明状态</th><th>原因码</th>
            <th>编译时间 / 编译者</th><th>结论摘要</th><th></th>
          </tr></thead>
          <tbody>
            <template v-for="c in rows" :key="c.id">
              <tr>
                <td class="mono-sm">{{ c.signatureNo }}</td>
                <td>v{{ c.versionNo }}</td>
                <td><StatusBadge :kind="c.status" compact /></td>
                <td class="small" style="max-width: 320px">{{ c.reasonCodes }}</td>
                <td class="mono-sm faint small">{{ fmt(c.compiledAt) }}<br />{{ c.compiledBy }}</td>
                <td class="mono-sm faint">{{ short(c.conclusionDigest) }}</td>
                <td><button class="sm ghost" @click="toggle(c.signatureNo)">
                  {{ expanded === c.signatureNo ? '隐藏证据' : '本地证据' }}
                </button></td>
              </tr>
              <tr v-if="expanded === c.signatureNo">
                <td colspan="7">
                  <pre class="evidence-box">{{ c.evidenceRefs }}</pre>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
        <div class="notice small" style="margin-top: 10px">
          所有证据仅含伪标识、本地身份句柄、证书指纹与哈希——身份明文不离开驻留域。缺少本地见证的材料一律为“身份/时间待核”，
          系统不会把它自动降格为撤回，也不会当作有效同意；结论以追加版本方式演进，旧版本只标记 superseded 而不删除。
        </div>
      </div>
    </section>

    <section class="panel">
      <div class="panel-head">
        <h2>逐份交叉签名与见证关系图 · 监护 / 终端 / 护士断裂追踪</h2>
        <div class="spacer"></div>
        <span class="small faint">节点 {{ graph?.nodes?.length || 0 }} · 边 {{ graph?.links?.length || 0 }}
          · 断裂边 <span :class="graph?.brokenEdges ? 'bad' : 'faint'">{{ graph?.brokenEdges || 0 }}</span>
        </span>
      </div>
      <div class="panel-body tight">
        <table>
          <thead><tr><th>签署（源）</th><th>关系</th><th>证据目标（句柄/指纹）</th><th>状态</th><th>断裂原因</th></tr></thead>
          <tbody>
            <tr v-for="(l, i) in graph?.links || []" :key="i">
              <td class="mono-sm">{{ l.source }}</td>
              <td><span class="chip" :class="relationColor[l.relation]">{{ l.relation }}</span></td>
              <td class="mono-sm small">{{ l.target }}</td>
              <td><span :class="l.broken ? 'badge bad' : 'badge ok'">{{ l.broken ? '断裂' : '完整' }}</span></td>
              <td class="small muted">{{ l.reason || '—' }}</td>
            </tr>
          </tbody>
        </table>
        <div class="notice small" style="margin-top: 10px">
          关系图方案对每份材料保存监护、终端与护士三类交叉签名边，能精细追踪断裂点；代价是批量审计更重、本地存储更大。
          采用承诺树方案的中心改用按日/按中心折叠的根哈希与叶子状态索引做批量存在性证明。
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.evidence-box {
  background: var(--bg);
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  padding: 10px;
  font-family: var(--mono);
  font-size: 11.5px;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
}
</style>
