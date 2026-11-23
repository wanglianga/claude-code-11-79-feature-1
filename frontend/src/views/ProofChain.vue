<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../lib/api.js'
import { toast } from '../lib/store.js'
import ProofTemplates from '../components/proof/ProofTemplates.vue'
import ProofMaterials from '../components/proof/ProofMaterials.vue'
import ProofDiffs from '../components/proof/ProofDiffs.vue'
import ProofSampling from '../components/proof/ProofSampling.vue'
import ProofConclusions from '../components/proof/ProofConclusions.vue'

const tab = ref('materials')
const summary = ref(null)
const witnesses = ref([])
const refreshTick = ref(0)

async function loadSummary() {
  summary.value = await api.get('/proof/summary')
}

async function reloadAll() {
  await loadSummary()
  refreshTick.value++
}

onMounted(loadSummary)

async function compileAll() {
  try {
    const rows = await api.post('/proof/compile')
    toast(`一致性编译器已重算 ${rows.length} 份材料的证明状态`, 'ok')
    await reloadAll()
  } catch (e) {
    toast(e.message, 'bad')
  }
}

async function diffAll() {
  try {
    const rows = await api.post('/proof/diff')
    toast(`篡改差异器输出 ${rows.length} 条差异结论`, 'ok')
    await reloadAll()
  } catch (e) {
    toast(e.message, 'bad')
  }
}

function statusCount(s) {
  return summary.value?.byStatus?.[s] || 0
}
</script>

<template>
  <div>
    <div class="page-head">
      <h1>同意材料证明链工作台</h1>
      <p class="lede">
        独立于资格求值的“已同意”标记，本工作台只核验同意事实的<strong>来源完整性</strong>：模板发布区间、本地域电子签名证书、
        终端完整性声明、三个时钟（签署/见证/上传）、附件哈希、护士复核与监护关系证明。身份明文不离开驻留域；
        缺少本地见证的材料只会“待核”，不会被自动降格为撤回或有效同意。
      </p>
    </div>

    <div class="grid" style="grid-template-columns: repeat(5, 1fr); gap: 10px; margin-bottom: 16px">
      <div class="panel"><div class="panel-body stat">
        <div class="small faint">材料总数</div>
        <div class="num" style="font-size: 22px">{{ summary?.total ?? '—' }}</div>
      </div></div>
      <div class="panel"><div class="panel-body stat">
        <div class="small faint">可验证签署</div>
        <div class="num ok" style="font-size: 22px">{{ statusCount('VERIFIED_SIGNATURE') }}</div>
      </div></div>
      <div class="panel"><div class="panel-body stat">
        <div class="small faint">材料内容破损</div>
        <div class="num bad" style="font-size: 22px">{{ statusCount('MATERIAL_CORRUPTED') }}</div>
      </div></div>
      <div class="panel"><div class="panel-body stat">
        <div class="small faint">身份/时间待核</div>
        <div class="num" style="font-size: 22px; color: var(--pend)">{{ statusCount('IDENTITY_OR_TIME_PENDING') }}</div>
      </div></div>
      <div class="panel"><div class="panel-body stat">
        <div class="small faint">待编译 / 未决差异 / 承诺批次</div>
        <div class="num faint" style="font-size: 22px">
          {{ summary?.notCompiled ?? 0 }} / {{ summary?.openDiffs ?? 0 }} / {{ summary?.batches ?? 0 }}
        </div>
      </div></div>
    </div>

    <div class="row" style="margin-bottom: 12px">
      <button class="primary" @click="compileAll">重新运行一致性编译器</button>
      <button @click="diffAll">重新运行篡改差异器</button>
    </div>

    <div class="tabs">
      <button class="tab" :class="{ active: tab === 'templates' }" @click="tab = 'templates'">模板时间线</button>
      <button class="tab" :class="{ active: tab === 'materials' }" @click="tab = 'materials'">材料证明链</button>
      <button class="tab" :class="{ active: tab === 'diffs' }" @click="tab = 'diffs'">版本差异</button>
      <button class="tab" :class="{ active: tab === 'sampling' }" @click="tab = 'sampling'">抽检覆盖</button>
      <button class="tab" :class="{ active: tab === 'conclusions' }" @click="tab = 'conclusions'">本地域核验结论</button>
    </div>

    <ProofTemplates v-if="tab === 'templates'" :tick="refreshTick" @changed="reloadAll" />
    <ProofMaterials v-else-if="tab === 'materials'" :tick="refreshTick" @changed="reloadAll" />
    <ProofDiffs v-else-if="tab === 'diffs'" :tick="refreshTick" @changed="reloadAll" />
    <ProofSampling v-else-if="tab === 'sampling'" :tick="refreshTick" @changed="reloadAll" />
    <ProofConclusions v-else :tick="refreshTick" @changed="reloadAll" />
  </div>
</template>
