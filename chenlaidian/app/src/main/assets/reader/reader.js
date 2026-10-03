// 0929 一起读：套在 foliate-js 外面的一层薄壳。
// 职责只有三件：开书、把每次翻页（本页原文 + 位置）交给 Kotlin、把辰指的那句话划线并冒气泡。
// Kotlin → JS：window.reader.*；JS → Kotlin：window.Android.*（ReaderBridge）。在浏览器里直接打开也能跑（Android 不存在就只打 console）。
import { diag } from './diag.js'   // 0.118 PDF 诊断：排第一个，它的 console/报错钩子要在 foliate 之前装好
import './foliate-js/view.js'
import { Overlayer } from './foliate-js/overlayer.js'

const $ = s => document.querySelector(s)
const send = (fn, ...args) => {
    try {
        const b = globalThis.Android
        if (b && typeof b[fn] === 'function') b[fn](...args)
        else console.log('[bridge]', fn, ...args)
    } catch (e) { console.error(e) }
}

const S = {
    view: null,
    first: true,           // 开书后第一条 relocate 当 open 事件
    lastFraction: null,    // 上一次的全书进度，用来判断 next/prev
    held: null,            // 跨章翻页时暂扣的那条空白列 relocate（见 onRelocate）
    lastEmitted: null,     // 上一条真发出去的 relocate（同一页重复量到的不再发）
    pendingAnchor: null,   // 压着的 'anchor' relocate（见 onRelocate）
    anchorTimer: null,
    fontPx: 18,
    theme: { bg: '#ECE8E2', ink: '#45403A', accent: '#E8A87C' },
    anchors: new Map(),    // 章节 index → [{ key, text, note }]：辰划过的句子，换章回来要重画
    notes: new Map(),      // 划线 key → 辰的话（点划线再冒一次气泡）
    drawn: new WeakSet(),  // 已经把划线画上去的章节 doc
    tapped: new WeakSet(), // 已经挂了点按翻页的章节 doc
    seq: 0,
}

// ---------- 样式 ----------
// setStyles 接 [前, 后] 两段：前一段排在书自己的 CSS 前面（书能盖掉），后一段排在后面（压过书的字号/底色）
const bookCSS = () => [`
@namespace epub "http://www.idpf.org/2007/ops";
html { color-scheme: light; }
body { font-family: system-ui, -apple-system, "Noto Sans CJK SC", "Source Han Sans SC", "PingFang SC", "Microsoft YaHei", sans-serif; }
p, li, blockquote, dd { line-height: 1.75; text-align: justify; hanging-punctuation: allow-end last; widows: 2; }
[align="left"] { text-align: left; }
[align="right"] { text-align: right; }
[align="center"] { text-align: center; }
pre { white-space: pre-wrap !important; }
aside[epub|type~="endnote"], aside[epub|type~="footnote"], aside[epub|type~="note"], aside[epub|type~="rearnote"] { display: none; }
`, `
html { font-size: ${S.fontPx}px !important; background-color: ${S.theme.bg} !important; color: ${S.theme.ink}; }
body { background-color: transparent !important; color: ${S.theme.ink}; }
`]

const applyTheme = () => {
    document.body.style.background = S.theme.bg
    document.documentElement.style.setProperty('--bubble-bg', S.theme.ink)
    document.documentElement.style.setProperty('--bubble-ink', S.theme.bg)
    S.view?.renderer?.setStyles?.(bookCSS())
}

// ---------- 文字 ----------
const clean = s => (s ?? '')
    .replace(/[ \t\r\f\v\xa0]+/g, ' ')
    .replace(/ ?\n ?/g, '\n')
    .replace(/\n{2,}/g, '\n')
    .trim()

// 本页可见原文：分页模式用 relocate 给的 range；PDF（固定版式）没有 range，从 pdf.js 的文字层抠
const pageText = loc => {
    let t = ''
    try { t = loc?.range?.toString() ?? '' } catch (e) { console.warn(e) }
    if (!t && S.view?.isFixedLayout) {
        try {
            t = S.view.renderer.getContents()
                .map(({ doc }) => doc?.querySelector('.textLayer')?.textContent ?? '')
                .join('\n')
        } catch (e) { console.warn(e) }
    }
    t = clean(t)
    return t.length > 4000 ? t.slice(0, 4000) : t
}

const fmtLang = x => !x ? '' : typeof x === 'string' ? x : (x[Object.keys(x)[0]] ?? '')
const fmtOne = c => typeof c === 'string' ? c : fmtLang(c?.name)
const fmtContrib = c => !c ? '' : Array.isArray(c) ? c.map(fmtOne).filter(Boolean).join('、') : fmtOne(c)

// 空白字符（找句子时全部忽略：辰引用时的换行/空格和书里排版的不一定一样）
const isWs = c => c <= 32 || c === 0xa0 || c === 0x3000 || (c >= 0x2000 && c <= 0x200b) || c === 0x2028 || c === 0x2029 || c === 0xfeff
const squeeze = s => {
    let out = ''
    for (let i = 0; i < s.length; i++) if (!isWs(s.charCodeAt(i))) out += s[i]
    return out
}

// 在一章的 DOM 里找辰引的那句，返回 Range；找不到返回 null
// 整句对不上时退一步：前 16 个字 / 后 16 个字对上也算（辰可能只引了半句或抄错了标点）
const findText = (doc, text) => {
    const q = squeeze(text ?? '')
    if (!q || !doc?.body) return null
    const walker = doc.createTreeWalker(doc.body, NodeFilter.SHOW_TEXT, {
        acceptNode: n => {
            const tag = n.parentNode?.tagName?.toLowerCase?.()
            return tag === 'script' || tag === 'style' ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT
        },
    })
    const nodes = [], pos = []
    let norm = ''
    for (let n = walker.nextNode(); n; n = walker.nextNode()) {
        const i = nodes.push(n) - 1
        const v = n.nodeValue ?? ''
        for (let j = 0; j < v.length; j++) {
            if (isWs(v.charCodeAt(j))) continue
            norm += v[j]
            pos.push([i, j])
        }
    }
    const tries = [q]
    if (q.length > 16) tries.push(q.slice(0, 16), q.slice(-16))
    for (const t of tries) {
        const idx = norm.indexOf(t)
        if (idx < 0) continue
        const [si, so] = pos[idx]
        const [ei, eo] = pos[idx + t.length - 1]
        const range = doc.createRange()
        range.setStart(nodes[si], so)
        range.setEnd(nodes[ei], eo + 1)
        return range
    }
    return null
}

// ---------- 气泡 ----------
const hideBubble = () => { const b = $('#bubble'); if (b) b.style.display = 'none' }

const showBubble = (range, doc, note) => {
    const b = $('#bubble')
    if (!b || !note) return
    b.textContent = note
    b.classList.remove('docked')
    b.style.display = 'block'
    let placed = false
    try {
        const rects = Array.from(range?.getClientRects?.() ?? []).filter(r => r.width > 0 || r.height > 0)
        // 章节在 iframe 里，iframe 又在分页容器里横向滚动：转成整页坐标 = iframe 的位置 + iframe 内坐标
        const fr = doc?.defaultView?.frameElement?.getBoundingClientRect()
        if (rects.length && fr) {
            const first = rects[0], last = rects[rects.length - 1]
            const x0 = fr.left + first.left, x1 = fr.left + last.right
            if (x1 > 0 && x0 < innerWidth) {   // 这句就在当前屏上
                const bw = b.offsetWidth, bh = b.offsetHeight
                let top = fr.top + last.bottom + 8
                if (top + bh > innerHeight - 8) top = fr.top + first.top - bh - 8
                if (top < 8) top = 8
                let left = fr.left + last.left
                left = Math.max(8, Math.min(left, innerWidth - bw - 8))
                b.style.top = top + 'px'
                b.style.left = left + 'px'
                b.style.bottom = 'auto'
                placed = true
            }
        }
    } catch (e) { console.warn(e) }
    if (!placed) {   // 不在当前屏（她已经翻过去了）：停在底部中间，话还是给她看
        b.classList.add('docked')
        b.style.top = 'auto'
        b.style.left = '50%'
        b.style.bottom = '56px'
    }
}

$('#bubble')?.addEventListener('click', e => { e.stopPropagation(); hideBubble() })

// ---------- 划线 ----------
const currentContent = () => {
    try { return S.view?.renderer?.getContents?.().find(x => x.overlayer && x.doc) ?? null } catch (e) { return null }
}

// 章节重新载入（翻回来）时把这一章记过的划线重画：'load' 那会儿 overlayer 还没挂上，只能在 relocate 里做
const redrawAnchors = index => {
    const view = S.view
    if (!view || view.isFixedLayout) return
    const list = S.anchors.get(index)
    if (!list?.length) return
    const c = currentContent()
    if (!c || c.index !== index || S.drawn.has(c.doc)) return
    S.drawn.add(c.doc)
    for (const a of list) {
        const r = findText(c.doc, a.text)
        if (r) c.overlayer.add(a.key, r, Overlayer.highlight, { color: S.theme.accent })
    }
}

// 辰指着一句话说：在当前章节里找到就划线 + 冒气泡；不在当前章节就静默（返回 false）
const highlight = (text, note) => {
    const view = S.view
    if (!view || view.isFixedLayout || !text) return false
    const c = currentContent()
    if (!c) return false
    const range = findText(c.doc, text)
    if (!range) return false
    const key = 'chen:' + (++S.seq)
    const list = S.anchors.get(c.index) ?? []
    list.push({ key, text, note: note ?? '' })
    S.anchors.set(c.index, list)
    S.notes.set(key, note ?? '')
    S.drawn.add(c.doc)
    c.overlayer.add(key, range, Overlayer.highlight, { color: S.theme.accent })
    showBubble(range, c.doc, note)
    return true
}

// ---------- 点按翻页 ----------
// 左三分之一上一页、右三分之一下一页、中间叫 Kotlin 收放顶栏。滑动翻页 EPUB 是 paginator 自带的，固定版式（PDF）的在下面。
const onTap = (e, doc) => {
    if (e.defaultPrevented) return                         // 链接 view.js 已经接了
    if (e.target?.closest?.('a[href]')) return
    try {
        const sel = doc.getSelection?.()
        if (sel && sel.type === 'Range' && sel.toString()) return   // 她在选字
    } catch (err) { /* ignore */ }
    const c = currentContent()
    try { if (c?.overlayer?.hitTest(e)[0]) return } catch (err) { /* ignore */ }   // 点的是划线：view.js 会发 show-annotation
    const bubbleShowing = $('#bubble')?.style.display === 'block'
    hideBubble()
    if (bubbleShowing) return                              // 这一下只是收气泡
    const fr = doc === document ? { left: 0 } : (doc.defaultView?.frameElement?.getBoundingClientRect() ?? { left: 0 })
    const x = fr.left + e.clientX
    const w = innerWidth
    if (x < w / 3) S.view?.goLeft()
    else if (x > w * 2 / 3) S.view?.goRight()
    else send('onTap')
}
document.addEventListener('click', e => onTap(e, document))

// ---------- 滑动翻页（只管固定版式） ----------
// 0.120 PDF 走的 fixed-layout.js 一行触摸处理都没有，她只能点。EPUB 的 paginator 自己会滑，再挂一份就是一滑翻两页，
// 所以只挂固定版式：每个页框 iframe 的 doc（onLoad 里）+ 主 document（页框外的留白）。
// 判定照 Readest（usePagination.ts）：松手时 横向位移 > 纵向、> 30px、速度 > 0.2px/ms 才翻，不跟手。
// 用 screenX/Y：固定版式 EPUB 的页框是 transform 缩放的，iframe 里的 clientX 跟手指真走的距离对不上
const swipe = { doc: null, x: 0, y: 0, t: 0, eatClickUntil: 0 }

const listenSwipe = doc => {
    const opts = { passive: true }
    doc.addEventListener('touchstart', e => {
        swipe.eatClickUntil = 0                                  // 新的一下开始了，上一下补发的 click 不会再来
        const t = S.view?.isFixedLayout && e.touches.length === 1 ? e.changedTouches[0] : null   // 两指不算
        swipe.doc = t ? doc : null
        if (t) Object.assign(swipe, { x: t.screenX, y: t.screenY, t: e.timeStamp })
    }, opts)
    doc.addEventListener('touchcancel', () => { swipe.doc = null }, opts)   // 长按出选字、系统收走手势
    doc.addEventListener('touchend', e => {
        const t = e.changedTouches[0]
        if (swipe.doc !== doc || !t) return                      // 起点不在这个 doc：各 doc 的 timeStamp 起点不同，不能混算
        swipe.doc = null
        const dx = t.screenX - swipe.x, dy = t.screenY - swipe.y
        // 手指挪开过就不是点按：浏览器万一还补发一次 click，别让它再翻一页或收放顶栏（Readest 也吞这一下）
        if (Math.hypot(dx, dy) >= 15) swipe.eatClickUntil = Date.now() + 750
        if (Math.abs(dx) <= Math.abs(dy) || Math.abs(dx) <= 30 || Math.abs(dx) / (e.timeStamp - swipe.t || 1) <= 0.2) return
        try {
            const sel = doc.getSelection?.()
            if (sel && sel.type === 'Range' && sel.toString()) return   // 她在选字
        } catch (err) { /* ignore */ }
        // 左滑 = 把右边那页拉过来 = 点右三分之一（goRight）；RTL 的书 view 自己把 goRight 换成上一页，跟点按一致
        if (dx < 0) S.view?.goRight()
        else S.view?.goLeft()
    }, opts)
    // capture 阶段吞：赶在 view.js 的链接处理和 onTap 前面
    doc.addEventListener('click', e => {
        if (Date.now() >= swipe.eatClickUntil) return
        swipe.eatClickUntil = 0
        e.preventDefault()
        e.stopImmediatePropagation()
    }, true)
}
listenSwipe(document)

// ---------- 事件 ----------
const emitRelocate = payload => {
    S.lastEmitted = payload
    send('onRelocate', JSON.stringify(payload))
}

// 真正决定发不发、算方向、合并跨章的那一步（onRelocate 和延后的 'anchor' 都走这里）
const deliver = (payload, reason, sf) => {
    const view = S.view
    if (!view) return
    // 同一页被重复量了一遍（换章后 fonts.ready / ResizeObserver 各触发一次；开书时 init 的 navigation 把第一页再报一遍）：不发、不动气泡、不算翻页
    const dupOf = p => !!p && p.cfi === payload.cfi && p.text === payload.text
    if (dupOf(S.held?.payload) || dupOf(S.lastEmitted)) return
    const { fraction } = payload
    if (S.first) { payload.event = 'open'; S.first = false }
    else if (reason === 'page' || reason === 'snap' || reason === 'scroll') {
        payload.event = 'page'
        if (S.lastFraction != null) payload.dir = fraction > S.lastFraction ? 'next' : fraction < S.lastFraction ? 'prev' : ''
    }
    S.lastFraction = fraction
    hideBubble()
    // 跨章翻页时 paginator 先滚到本章末尾的空白列（section 内 fraction 落到 <0 或 >=1，reason=page），
    // 再载入下一章定位到开头（reason=navigation）。把这两条合成一条 page：空白列那条先扣着，等下一条来了一起发。
    const boundary = payload.event === 'page' && !view.isFixedLayout && Number.isFinite(sf) && (sf < 0 || sf >= 1)
    if (S.held) {
        const h = S.held
        S.held = null
        clearTimeout(h.timer)
        if (reason === 'navigation') { payload.event = 'page'; payload.reason = 'page'; payload.dir = h.payload.dir }
        else emitRelocate(h.payload)
    }
    if (boundary) {
        const timer = setTimeout(() => { if (S.held?.payload === payload) { S.held = null; emitRelocate(payload) } }, 4000)
        S.held = { payload, timer }
        return
    }
    emitRelocate(payload)
}

// 挂在 renderer（paginator）上而不是 view 上：只有 renderer 那层的 relocate 带 reason。
// view 自己的监听先跑过了，所以 view.lastLocation 已经是这一次的（fraction / tocItem / cfi / range）。
const onRelocate = e => {
    const view = S.view
    if (!view) return
    const { reason, index } = e.detail
    const loc = view.lastLocation ?? {}
    const payload = {
        event: 'jump', dir: '',
        reason: reason ?? '',
        cfi: loc.cfi ?? '',
        fraction: Number.isFinite(loc.fraction) ? loc.fraction : 0,
        chapter: loc.tocItem?.label ?? '',
        index: index ?? -1,
        text: pageText(loc),
    }
    redrawAnchors(index)
    const sf = e.detail.fraction
    if (view.isFixedLayout) diag.mark('relocate', { i: index, reason: reason ?? '' })   // 0.118
    if (reason === 'anchor') {
        // 'anchor' 大多是换章过程中的过渡量（旧锚点套在新文档上量出来的一页，随后 navigation 就到）；
        // 真的重排（改字号/转屏）后面没有别的 relocate。所以压 300ms：期间来了别的就丢掉它
        clearTimeout(S.anchorTimer)
        S.pendingAnchor = payload
        S.anchorTimer = setTimeout(() => {
            const p = S.pendingAnchor
            S.pendingAnchor = null
            if (p) deliver(p, 'anchor', sf)
        }, 300)
        return
    }
    if (S.pendingAnchor) { clearTimeout(S.anchorTimer); S.pendingAnchor = null }
    deliver(payload, reason, sf)
}

const onLoad = ({ detail: { doc, index } }) => {
    if (S.view?.isFixedLayout) diag.mark('load', { i: index, url: String(doc?.URL ?? '').slice(0, 12) })   // 0.118 页框 iframe 载入了
    if (!doc || S.tapped.has(doc)) return
    S.tapped.add(doc)
    doc.addEventListener('click', e => onTap(e, doc))
    if (S.view?.isFixedLayout) listenSwipe(doc)   // 0.120 EPUB 的章节 doc 不挂（paginator 自己挂了）
}

const onShowAnnotation = ({ detail: { value, range } }) => {
    const note = S.notes.get(value)
    if (note == null) return
    showBubble(range, currentContent()?.doc, note)
}

// ---------- 开关书 ----------
const close = () => {
    diag.close()
    if (S.held) { clearTimeout(S.held.timer); S.held = null }
    clearTimeout(S.anchorTimer); S.pendingAnchor = null
    hideBubble()
    try { S.view?.close() } catch (e) { console.warn(e) }
    S.view?.remove()
    S.view = null
    S.anchors = new Map()
    S.notes = new Map()
    S.first = true
    S.lastFraction = null
    S.lastEmitted = null
}

// url：https://reader.chen/book/<id>（Kotlin 拦截给文件）；cfi：上次读到的位置（空=从头）；fontPx / theme 开书时一并带来
// wantDiag：0.118 Kotlin 说这本是 PDF，开书前就开始收诊断（固定版式的书不带这个标记也会在解析完后开始收）
const open = async (url, cfi, fontPx, theme, wantDiag) => {
    close()
    if (wantDiag) diag.begin(url)
    if (fontPx) S.fontPx = Math.max(10, Math.min(40, Number(fontPx) || 18))
    if (theme && typeof theme === 'object') Object.assign(S.theme, theme)
    applyTheme()
    const view = document.createElement('foliate-view')
    document.body.append(view)
    S.view = view
    view.addEventListener('load', onLoad)
    view.addEventListener('show-annotation', onShowAnnotation)
    view.addEventListener('external-link', e => e.preventDefault())   // 书里的外链不跳
    try {
        await view.open(url)
    } catch (e) {
        console.error(e)
        send('onError', '打不开这本书：' + (e?.message ?? e))
        diag.failed(e)
        return
    }
    diag.opened(view)
    const { book, renderer } = view
    // 书里缺资源（图/字体没打包全）时别整章挂掉：照 foliate 示例的做法换成空
    book.transformTarget?.addEventListener('data', ({ detail }) => {
        detail.data = Promise.resolve(detail.data).catch(err => { console.warn(err); return '' })
    })
    renderer.setAttribute('flow', 'paginated')
    renderer.setAttribute('animated', '')
    renderer.setAttribute('margin', '40px')      // 上下留白（顶栏盖在上面这一条里）
    renderer.setAttribute('gap', '10%')          // 左右各 5%
    renderer.setAttribute('max-column-count', '1')
    renderer.setStyles?.(bookCSS())
    renderer.addEventListener('relocate', onRelocate)
    send('onBookOpened', JSON.stringify({
        title: fmtLang(book.metadata?.title),
        author: fmtContrib(book.metadata?.author),
        sections: book.sections?.length ?? 0,
        toc: book.toc?.length ?? 0,
        fixed: !!view.isFixedLayout,
    }))
    try {
        await view.init({ lastLocation: cfi || null, showTextStart: true })
    } catch (e) {
        console.warn('上次位置定不到 从头开始', e)
        try { await view.goToTextStart() } catch (e2) { send('onError', '定位失败：' + (e2?.message ?? e2)) }
    }
    diag.inited()
}

globalThis.reader = {
    open,
    close,
    next: () => S.view?.next(),
    prev: () => S.view?.prev(),
    goTo: target => S.view?.goTo(target),
    setFontSize: px => { S.fontPx = Math.max(10, Math.min(40, Number(px) || 18)); S.view?.renderer?.setStyles?.(bookCSS()) },
    setTheme: t => { if (t && typeof t === 'object') Object.assign(S.theme, t); applyTheme() },
    highlight,
}

S.findText = findText
globalThis.__reader = S   // 排查用：chrome://inspect 里能看内部状态

addEventListener('error', e => send('onError', (e.message ?? String(e)).slice(0, 200)))
addEventListener('unhandledrejection', e => send('onError', String(e.reason?.message ?? e.reason ?? e).slice(0, 200)))

applyTheme()
send('onReady')
