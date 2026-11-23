import { describe, it, expect, beforeAll } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createWebHashHistory } from 'vue-router'
import http from 'node:http'
import Overview from './views/Overview.vue'
import Acceptance from './views/Acceptance.vue'
import Eligibility from './views/Eligibility.vue'
import Deliveries from './views/Deliveries.vue'
import Watermarks from './views/Watermarks.vue'
import ConsentProtocol from './views/ConsentProtocol.vue'
import ProofChain from './views/ProofChain.vue'
import { setAuth } from './lib/api.js'

const HOST = process.env.API_HOST || '127.0.0.1'
const PORT = Number(process.env.API_PORT || 8080)
let live = false

// A Node-level request that bypasses happy-dom's CORS restrictions, shaped like Response.
function nodeRequest(path, init = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request(
      { host: HOST, port: PORT, path, method: init.method || 'GET', headers: init.headers || {} },
      (res) => {
        let data = ''
        res.setEncoding('utf8')
        res.on('data', (c) => (data += c))
        res.on('end', () =>
          resolve({
            ok: res.statusCode >= 200 && res.statusCode < 300,
            status: res.statusCode,
            text: async () => data,
            json: async () => JSON.parse(data)
          })
        )
      }
    )
    req.on('error', reject)
    if (init.body) req.write(init.body)
    req.end()
  })
}

beforeAll(async () => {
  try {
    const res = await nodeRequest('/actuator/health')
    live = res.ok
  } catch {
    live = false
  }
  globalThis.fetch = (input, init) => {
    const url = typeof input === 'string' ? input : input.url
    if (url.startsWith('/api')) return nodeRequest(url, init)
    return nodeRequest(url.startsWith('http') ? url.replace(`http://${HOST}:${PORT}`, '') : url, init)
  }
  setAuth({ username: 'admin', password: 'demo123', roles: ['ROLE_ADMIN'] })
})

function mountView(component) {
  const router = createRouter({ history: createWebHashHistory(), routes: [{ path: '/', component }] })
  return mount(component, { global: { plugins: [router] } })
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms))

describe('SPA renders against the live API', () => {
  it('is talking to a live backend', () => {
    expect(live).toBe(true)
  })

  it('Overview shows the study and the passing acceptance summary', async () => {
    const w = mountView(Overview)
    await wait(1200)
    const text = w.text()
    expect(text).toContain('APEX-3')
    expect(text).toContain('PSN-A1')
    expect(w.html()).toContain('15/15')
  })

  it('Acceptance lists subject A/B/C/D outcomes', async () => {
    const w = mountView(Acceptance)
    await wait(1000)
    const text = w.text()
    for (const code of ['A1', 'B1', 'C1', 'D2']) expect(text).toContain(code)
    expect(text).toContain('The dual-domain rules')
  })

  it('Eligibility pivot shows global vs local planes and subject D pending', async () => {
    const w = mountView(Eligibility)
    await wait(1500)
    const html = w.html()
    expect(html).toContain('PSN-D4')
    expect(html).toContain('Global plane')
    expect(html).toContain('Local plane')
    expect(html).toContain('Pending')
  })

  it('Consent page renders the ledger and protocol catalogue tabs', async () => {
    const w = mountView(ConsentProtocol)
    await wait(1200)
    expect(w.text()).toContain('Consent ledger')
    expect(w.text()).toContain('Protocol catalogue')
  })

  it('Deliveries page loads without error', async () => {
    const w = mountView(Deliveries)
    await wait(1200)
    expect(w.text()).toContain('Snapshots')
  })

  it('Watermarks page shows the CN-02 unconfirmed gap', async () => {
    const w = mountView(Watermarks)
    await wait(1200)
    expect(w.html()).toMatch(/CN-02/)
  })

  it('Proof chain workbench renders the three proof statuses and the five tabs', async () => {
    const w = mountView(ProofChain)
    await wait(1600)
    const text = w.text()
    expect(text).toContain('同意材料证明链工作台')
    expect(text).toContain('模板时间线')
    expect(text).toContain('材料证明链')
    expect(text).toContain('版本差异')
    expect(text).toContain('抽检覆盖')
    expect(text).toContain('本地域核验结论')
    // the default tab lists witnesses and the seeded mandatory scenarios
    expect(text).toContain('SIG-E5-001')
    expect(w.html()).toContain('IDENTITY_OR_TIME_PENDING')
  })

  it('Proof chain sampling tab shows plans and the widened batch result', async () => {
    const w = mountView(ProofChain)
    await wait(1200)
    w.findAll('button.tab').find((b) => b.text() === '抽检覆盖').trigger('click')
    await wait(1200)
    const text = w.text()
    expect(text).toContain('BATCH_ATTESTATION_FAILURE')
    expect(text).toContain('TAB-CN02-B7')
  })
})
