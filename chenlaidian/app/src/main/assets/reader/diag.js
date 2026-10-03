// 0.118 一起读 PDF 诊断。她手机上 PDF 打开后整片米色、连白页框都没有，桌面上同一本书却画得好好的——不猜，让手机自己报卡在哪一步。
// 收三样：报错/告警原文（onerror / unhandledrejection / console.error·warn / pdf.js 主线程的 "Warning:"）、
//         开书后 1s/3s/8s 的布局快照、foliate pdf.js 每页渲染打点（render→drawn→canvas→text→done）。
// 全攒在内存里；只有 PDF/固定版式开书才经 Android.onDiag 交给 Kotlin（ReaderDiag 负责上传，一次开书最多两回）。epub/txt 什么都不发。
// 诊断代码自己出错一律吞掉：不能因为看病把病人弄死。

const MAX_LOGS = 40      // 超了丢中间的：头几条往往是根因，后几条是现状
const MAX_MARKS = 80
const MAX_TASKS = 4      // 最近几次 page.render
const MAX_LEN = 300
const SNAPS = [['1s', 1000], ['3s', 3000], ['8s', 8000]]

const now = () => Math.round(performance.now())   // 全部时间都是"页面加载后多少毫秒"，open.at 记了开书那一刻
const clip = s => s.length > MAX_LEN ? s.slice(0, MAX_LEN) + '…' : s
const str = x => {
    try {
        if (x instanceof Error) {
            const at = String(x.stack ?? '').split('\n').slice(1, 3).map(l => l.trim()).join(' | ')
            return `${x.name}: ${x.message}` + (at ? ' | ' + at : '')
        }
        if (typeof x === 'string') return x
        if (x && typeof x === 'object' && typeof x.message === 'string') return x.message
        return JSON.stringify(x) ?? String(x)
    } catch (e) { return String(x) }
}
const rect = el => {
    try {
        const r = el?.getBoundingClientRect?.()
        return r ? [r.x, r.y, r.width, r.height].map(Math.round) : null
    } catch (e) { return 'err' }
}

const D = {
    kind: 'reader_diag',
    env: null,
    open: null,      // { at, url, book, fixed, sections, init }
    logs: [],        // { t, k, m, n? }
    marks: [],       // { t, k, ...信息 }
    snaps: [],
    dropped: 0,
}
let view = null      // 当前 foliate-view（opened 时给）
let active = false   // 这次开书要不要往 Kotlin 交
let finalSent = false
let lateTimer = null
let snapTimers = []
let tasks = []       // [{ p, t, task, canvas }]：pdf.js RenderTask + 它正在画的那张 canvas（还没放进 iframe 之前）
let pdfDoc = null

const trim = arr => {
    if (arr.length < (arr === D.logs ? MAX_LOGS : MAX_MARKS)) return
    arr.splice(arr.length >> 1, 1)
    D.dropped++
}

// 8 秒那包交出去以后还有新东西（翻页的渲染打点、迟到的报错）：3 秒攒一次再交，Kotlin 离开阅读页时补传
const late = () => {
    if (!active || !finalSent || lateTimer) return
    lateTimer = setTimeout(() => { lateTimer = null; deliver('late') }, 3000)
}

const log = (k, args) => {
    try {
        const m = clip(Array.from(args, str).join(' '))
        const last = D.logs[D.logs.length - 1]
        if (last && last.k === k && last.m === m) { last.n = (last.n ?? 1) + 1; return }
        trim(D.logs)
        D.logs.push({ t: now(), k, m })
        late()
    } catch (e) { /* 吞 */ }
}

for (const k of ['error', 'warn']) {
    const orig = console[k]
    console[k] = function (...a) { log(k, a); return orig.apply(this, a) }
}
{
    // pdf.js 的 warn() 打的是 console.log("Warning: …")；只认这个前缀，别的 log 不收（worker 线程里的这边拿不到）
    const orig = console.log
    console.log = function (...a) {
        if (typeof a[0] === 'string' && a[0].startsWith('Warning:')) log('pdfwarn', a)
        return orig.apply(this, a)
    }
}
// 捕获阶段：资源（img/script/link）加载失败只有这里拿得到
addEventListener('error', e => {
    const el = e.target
    if (el && el !== window && el.tagName) log('resource', [`${el.tagName.toLowerCase()} ${String(el.src || el.href || '').slice(0, 120)}`])
    else log('onerror', [`${e.message} @ ${String(e.filename ?? '').split('/').pop()}:${e.lineno}:${e.colno}`, e.error ?? ''])
}, true)
addEventListener('unhandledrejection', e => log('rejection', [e.reason]))
// index.html 的 CSP 很紧：WebView 和桌面 Chrome 认法不一样时，被拦的东西只在这里冒头
document.addEventListener('securitypolicyviolation', e => log('csp', [`${e.violatedDirective} ${String(e.blockedURI).slice(0, 80)}`]))
document.addEventListener('visibilitychange', () => mark('visibility', { v: document.visibilityState }))

const mark = (k, info) => {
    try {
        trim(D.marks)
        const m = { t: now(), k, ...info }
        // 第一次排版时容器有多大（AndroidView 可能先量 0 再给尺寸）
        if (k === 'render') { m.fxl = rect(view?.renderer); m.inner = [innerWidth, innerHeight] }
        D.marks.push(m)
        late()
    } catch (e) { /* 吞 */ }
}

// 采一张 canvas 有没有画上东西：缩到 48×48 再读，免得大图 getImageData 卡顿
const ink = cv => {
    try {
        if (!cv.width || !cv.height) return 'empty'
        const n = 48
        const c = document.createElement('canvas')
        c.width = n; c.height = n
        const x = c.getContext('2d', { willReadFrequently: true })
        x.drawImage(cv, 0, 0, n, n)
        const d = x.getImageData(0, 0, n, n).data
        let dark = 0, transparent = 0, white = 0
        for (let i = 0; i < d.length; i += 4) {
            if (d[i + 3] < 10) transparent++
            else if (d[i] + d[i + 1] + d[i + 2] < 600) dark++
            else if (d[i] + d[i + 1] + d[i + 2] > 750) white++
        }
        return { samples: n * n, dark, transparent, white }
    } catch (e) { return 'err: ' + clip(str(e)) }
}

const contentInfo = doc => {
    if (!doc) return { docOk: false }
    const o = { docOk: true }
    try {
        o.url = String(doc.URL).slice(0, 12)     // blob:https:/ = 页框文档；about:blank = 还没载入
        o.ready = doc.readyState
        const fe = doc.defaultView?.frameElement
        o.frame = rect(fe)
        if (fe) {
            o.frameDisplay = fe.style.display
            o.frameVisibility = getComputedStyle(fe).visibility
            o.frameTransform = fe.style.transform
            o.frameSize = [fe.style.width, fe.style.height]
            o.wrap = rect(fe.parentElement)
            o.wrapDisplay = fe.parentElement?.style.display
        }
        const de = doc.documentElement
        o.htmlTransform = de?.style.transform
        o.scaleFactor = de?.style.getPropertyValue('--scale-factor')
        const cvs = doc.querySelectorAll('#canvas canvas')
        o.canvasN = cvs.length
        const cv = cvs[0]
        if (cv) {
            o.canvas = [cv.width, cv.height]
            o.canvasCSS = [cv.style.width, cv.style.height]
            o.canvasRect = rect(cv)
            o.ink = ink(cv)
        }
        o.textLayer = (doc.querySelector('.textLayer')?.textContent ?? '').length   // 只报字数，不报原文
    } catch (e) { o.err = clip(str(e)) }
    return o
}

const fontsInfo = () => {
    try {
        const c = { status: document.fonts.status, n: 0 }
        document.fonts.forEach(f => { c.n++; c[f.status] = (c[f.status] ?? 0) + 1 })
        return c
    } catch (e) { return 'err: ' + clip(str(e)) }
}

// pdf.js 的渲染任务画到哪了：ready=false 还在等 worker 给第一批指令；idx<len 且不动 = 主线程在等字体/图片或者 rAF 没来
const taskState = task => {
    const it = task?._internalRenderTask
    const ol = it?.operatorList
    return {
        idx: it?.operatorListIdx ?? null, len: ol?.fnArray?.length ?? null, last: ol?.lastChunk ?? null,
        ready: it?.graphicsReady ?? null, running: it?.running ?? null, cancelled: it?.cancelled ?? null,
    }
}
// 还没画完的那张 canvas 也采一下墨：卡住时能看出画了多少（全白 = 只铺了底色，一条指令都没执行）
const tasksInfo = () => tasks.map(e => ({
    p: e.p, t: e.t, end: e.end ?? null, err: e.err ?? null,
    ...(e.task ? taskState(e.task) : e.final),
    ink: e.canvas ? ink(e.canvas) : null,
}))

// rAF 在 400ms 里来了几次：0 = 页面压根没在出帧（pdf.js 每画一段都靠 rAF 续命，rAF 不来整页就停在半路、canvas 永远进不了页框）
const countRaf = ms => new Promise(resolve => {
    let n = 0, done = false
    const tick = () => { if (done) return; n++; requestAnimationFrame(tick) }
    requestAnimationFrame(tick)
    setTimeout(() => { done = true; resolve(n) }, ms)
})

// 给 worker 发个最轻的往返（GetPageLabels 不缓存）：卡住 = worker 忙死了或者没了
const ping = async () => {
    if (!pdfDoc) return null
    const t = performance.now()
    try {
        const r = await Promise.race([
            pdfDoc.getPageLabels().then(() => 'ok'),
            new Promise(res => setTimeout(() => res('timeout'), 1500)),
        ])
        return r === 'ok' ? Math.round(performance.now() - t) : r
    } catch (e) { return 'err: ' + clip(str(e)) }
}

const snap = async label => {
    const s = { label, t: now(), vis: document.visibilityState, focus: document.hasFocus(), dpr: devicePixelRatio, inner: [innerWidth, innerHeight] }
    try {
        s.body = rect(document.body)
        s.view = rect(view)
        const r = view?.renderer
        s.renderer = r ? { tag: r.localName, rect: rect(r), display: getComputedStyle(r).display } : null
        s.fixed = !!view?.isFixedLayout
        s.contents = (r?.getContents?.() ?? []).map(({ doc }) => contentInfo(doc))
        const loc = view?.lastLocation
        s.lastLocation = loc ? { cfi: loc.cfi, fraction: loc.fraction } : null
        s.fonts = fontsInfo()
        s.tasks = tasksInfo()
        s.fakeWorker = !!globalThis.pdfjsWorker   // pdf.js 起不来真 worker 时会在主线程装一个
    } catch (e) { s.err = clip(str(e)) }
    try {
        const m = performance.memory   // Chromium 才有；看是不是内存吃紧
        s.heap = m ? [m.usedJSHeapSize, m.jsHeapSizeLimit].map(x => Math.round(x / 1048576)) : null
    } catch (e) { /* 吞 */ }
    s.raf = await countRaf(400)
    s.ping = await ping()
    return s
}

const deliver = reason => {
    if (!active) return
    try {
        const b = globalThis.Android
        const json = JSON.stringify(D)
        if (b && typeof b.onDiag === 'function') b.onDiag(json, reason)
        else console.info('[diag]', reason, json.length)
    } catch (e) { /* 吞 */ }
}

const env = () => ({
    ua: navigator.userAgent,
    dpr: devicePixelRatio,
    inner: [innerWidth, innerHeight],
    screen: [screen.width, screen.height],
    cores: navigator.hardwareConcurrency ?? null,
    mem: navigator.deviceMemory ?? null,
    offscreen: typeof OffscreenCanvas !== 'undefined',
    chrome: typeof globalThis.chrome,
})

export const diag = {
    mark,
    // Kotlin 说这本是 PDF：开书前就开始报（万一 PDF 在 view.open 里就挂了也有东西看）
    begin(url) {
        try {
            active = true
            finalSent = false
            snapTimers.forEach(clearTimeout); snapTimers = []
            D.snaps = []; D.marks = []; tasks = []; pdfDoc = null
            D.env = env()
            D.open = { at: now(), url: String(url ?? '').replace(/^.*\/book\//, 'book/') }
            deliver('start')
        } catch (e) { /* 吞 */ }
    },
    // 书解析完了：固定版式才往下走；1s/3s/8s 拍快照，8s 那包 Kotlin 收到就上传
    opened(v) {
        try {
            if (!v?.isFixedLayout && !active) return
            if (!active) this.begin('')
            view = v
            Object.assign(D.open, { book: now(), fixed: !!v.isFixedLayout, sections: v.book?.sections?.length ?? 0 })
            deliver('opened')
            for (const [label, ms] of SNAPS) {
                snapTimers.push(setTimeout(async () => {
                    try { D.snaps.push(await snap(label)) } catch (e) { log('diag', [e]) }
                    if (label === '8s') finalSent = true
                    deliver(label)
                }, ms))
            }
        } catch (e) { /* 吞 */ }
    },
    inited() { if (D.open) D.open.init = now() },
    // view.open 自己就失败了：手上有什么交什么
    failed(e) {
        if (!active) return
        log('openfail', [e])
        finalSent = true
        deliver('fail')
    },
    close() {
        snapTimers.forEach(clearTimeout); snapTimers = []
        clearTimeout(lateTimer); lateTimer = null
        view = null
        active = false
    },
}

// foliate-js/pdf.js 里的打点从这里进（它不 import 我们，免得改了 vendor 的依赖关系）
globalThis.__readerDiag = {
    mark,
    task(p, task, canvas) {
        try {
            const ent = { p, t: now(), task, canvas }
            tasks.push(ent)
            if (tasks.length > MAX_TASKS) tasks.shift()
            // 画完（或失败）就放手：只留最终状态，不再攥着 RenderTask 和整张 canvas（一张就好几 MB）
            const done = err => {
                ent.end = now()
                if (err) ent.err = clip(str(err))
                ent.final = taskState(task)
                ent.task = null
                ent.canvas = null
            }
            task.promise.then(() => done(), done)
        } catch (e) { /* 吞 */ }
    },
    pdf(doc) { pdfDoc = doc },
}
