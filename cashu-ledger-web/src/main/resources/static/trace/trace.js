/*
 * Transaction-graph explorer (design §5.5 / §5.3.2). Renders the secret-free
 * /api/v1/trace/visualisation payload with Cytoscape + dagre, anchored on an event or
 * proof, and drills down into a node via /api/v1/trace/events/{id}. All trace endpoints
 * are NIP-98 gated; this module signs requests with a NIP-07 browser extension
 * (window.nostr) when present and degrades gracefully — with no signer the graph cannot
 * load and the banner explains why. The drill-down only ever shows what the server
 * returns, so secret fields appear solely for an operator the server authorises (SC-009).
 */
(function () {
    'use strict';

    const API_KEY = 'cashu-ledger-api-base';
    const TERMINAL_KINDS = new Set(['melt', 'melt_failed', 'mint_failed', 'event_pruned']);
    const KIND_SHAPE = {
        mint: 'round-rectangle', swap: 'ellipse', send: 'diamond', receive: 'diamond',
        melt: 'hexagon', melt_failed: 'octagon', mint_failed: 'octagon',
        mint_quote_requested: 'tag', melt_quote_requested: 'tag', event_pruned: 'octagon'
    };

    let cy = null;
    let authPubkey = null;

    const el = (id) => document.getElementById(id);
    const apiBase = () => localStorage.getItem(API_KEY) || (document.body.dataset.apiBase || '/proxy');

    function apiUrl(path) {
        const base = apiBase().replace(/\/$/, '');
        return base + path;
    }

    function absoluteUrl(path) {
        return new URL(apiUrl(path), window.location.href).href;
    }

    function base64url(text) {
        return btoa(text).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
    }

    // Builds a NIP-98 (kind 27235) Authorization header for a request, or null when no
    // browser signer is available. The signed event's "u" tag must equal the request URL.
    async function authHeader(method, url) {
        if (!window.nostr || typeof window.nostr.signEvent !== 'function') {
            return null;
        }
        const unsigned = {
            kind: 27235,
            created_at: Math.floor(Date.now() / 1000),
            tags: [['u', url], ['method', method]],
            content: ''
        };
        const signed = await window.nostr.signEvent(unsigned);
        return 'Nostr ' + base64url(JSON.stringify(signed));
    }

    async function traceFetch(path) {
        const url = absoluteUrl(path);
        const header = await authHeader('GET', url);
        const headers = header ? { Authorization: header } : {};
        return fetch(apiUrl(path), { headers, cache: 'no-store' });
    }

    function setBanner(text, tone) {
        const banner = el('trace-auth-banner');
        if (!banner) return;
        banner.textContent = text;
        const colours = { ok: 'rgba(34,197,94,0.14)', warn: 'rgba(234,179,8,0.12)', err: 'rgba(239,68,68,0.14)' };
        banner.style.background = colours[tone] || colours.warn;
    }

    async function detectAuthority() {
        if (window.nostr && typeof window.nostr.getPublicKey === 'function') {
            try {
                authPubkey = await window.nostr.getPublicKey();
                setBanner('Authenticated via Nostr extension (' + authPubkey.slice(0, 12) + '…). '
                    + 'Fields shown depend on your operator access level.', 'ok');
                return;
            } catch (e) {
                setBanner('Nostr extension present but authorisation was declined. The graph is gated.', 'err');
                return;
            }
        }
        setBanner('No Nostr (NIP-07) extension found. Operator authentication is required to view the '
            + 'trace graph; install a signer and grant access.', 'warn');
    }

    function mintHue(mintUrl) {
        let hash = 0;
        for (let i = 0; i < mintUrl.length; i++) {
            hash = (hash * 31 + mintUrl.charCodeAt(i)) & 0xffffffff;
        }
        return Math.abs(hash) % 360;
    }

    // Flattens the per-mint visualisation payload into Cytoscape elements, honouring the
    // client-side kind and (kind-based) activity filters and the per-mint/overlay view.
    function toElements(graph, opts) {
        const elements = [];
        const perMint = opts.view === 'per-mint';
        const kindFilter = opts.kinds;
        const activeOnly = opts.activity === 'active';
        const terminalOnly = opts.activity === 'terminal';
        const kept = new Set();

        graph.mints.forEach((sub) => {
            const hue = mintHue(sub.mintUrl);
            if (perMint) {
                elements.push({ data: { id: 'mint:' + sub.mintUrl, label: sub.mintUrl }, classes: 'mint-group' });
            }
            sub.nodes.forEach((node) => {
                const terminalKind = TERMINAL_KINDS.has(node.kind);
                if (kindFilter.size && !kindFilter.has(node.kind)) return;
                if (activeOnly && terminalKind) return;
                if (terminalOnly && !terminalKind) return;
                kept.add(node.eventId);
                elements.push({
                    data: {
                        id: node.eventId,
                        label: node.kind,
                        kind: node.kind,
                        hue: hue,
                        terminal: terminalKind ? 1 : 0,
                        parent: perMint ? 'mint:' + sub.mintUrl : undefined
                    }
                });
            });
            sub.edges.forEach((edge) => {
                if (!kept.has(edge.fromEventId) || !kept.has(edge.toEventId)) return;
                elements.push({
                    data: {
                        id: 'e:' + edge.fromEventId + ':' + edge.toEventId + ':' + edge.role,
                        source: edge.fromEventId, target: edge.toEventId,
                        label: edge.amount != null ? String(edge.amount) : edge.role,
                        role: edge.role, transfer: 0, doubleConsume: edge.doubleConsume ? 1 : 0
                    }
                });
            });
        });

        (graph.transfers || []).forEach((link) => {
            if (!kept.has(link.fromEventId) || !kept.has(link.toEventId)) return;
            elements.push({
                data: {
                    id: 't:' + link.fromEventId + ':' + link.toEventId,
                    source: link.fromEventId, target: link.toEventId,
                    label: 'transfer', role: 'transfer', transfer: 1,
                    missing: link.counterpartMissing ? 1 : 0
                }
            });
        });
        return elements;
    }

    function styleSheet() {
        return [
            { selector: 'node', style: {
                'label': 'data(label)', 'font-size': '9px', 'color': '#e5e7eb',
                'text-valign': 'center', 'text-halign': 'center', 'width': 34, 'height': 34,
                'background-color': 'mapData(hue, 0, 360, hsl(0,60%,50%), hsl(360,60%,50%))',
                'shape': 'ellipse', 'border-width': 1, 'border-color': '#1f2937' } },
            { selector: 'node[terminal = 1]', style: { 'background-opacity': 0.35, 'border-style': 'dashed' } },
            { selector: 'node:selected', style: { 'border-width': 3, 'border-color': '#38bdf8' } },
            { selector: '$node > node', style: { 'padding': 12 } },
            { selector: '.mint-group', style: {
                'background-opacity': 0.05, 'border-color': '#334155', 'shape': 'round-rectangle',
                'font-size': '8px', 'text-valign': 'top', 'color': '#94a3b8' } },
            { selector: 'edge', style: {
                'label': 'data(label)', 'font-size': '8px', 'color': '#94a3b8', 'width': 1.5,
                'line-color': '#475569', 'target-arrow-color': '#475569', 'target-arrow-shape': 'triangle',
                'curve-style': 'bezier' } },
            { selector: 'edge[transfer = 1]', style: { 'line-style': 'dashed', 'line-color': '#38bdf8',
                'target-arrow-color': '#38bdf8' } },
            { selector: 'edge[missing = 1]', style: { 'line-color': '#ef4444', 'target-arrow-color': '#ef4444' } },
            { selector: 'edge[doubleConsume = 1]', style: { 'line-color': '#ef4444', 'width': 2.5 } }
        ];
    }

    function applyKindShapes(graph) {
        graph.cy.nodes().forEach((n) => {
            const shape = KIND_SHAPE[n.data('kind')];
            if (shape) n.style('shape', shape);
        });
    }

    async function render() {
        const type = el('trace-anchor-type').value;
        const anchor = el('trace-anchor-id').value.trim();
        if (!anchor) return;
        const params = new URLSearchParams();
        params.set(type, anchor);
        if (type === 'y') {
            if (el('trace-mint').value.trim()) params.set('mintUrl', el('trace-mint').value.trim());
            if (el('trace-keyset').value.trim()) params.set('keysetId', el('trace-keyset').value.trim());
        }
        params.set('direction', el('trace-direction').value);
        params.set('depth', el('trace-depth').value);
        params.set('limit', el('trace-maxnodes').value);

        const detail = el('trace-detail');
        detail.innerHTML = '<div style="color:var(--muted);font-size:13px;">Loading graph…</div>';
        let res;
        try {
            res = await traceFetch('/trace/visualisation?' + params.toString());
        } catch (e) {
            detail.innerHTML = '<div style="color:#ef4444;">Request failed: ' + e + '</div>';
            return;
        }
        if (res.status === 401) {
            detail.innerHTML = '<div style="color:#ef4444;">Unauthorised. Operator authentication required.</div>';
            return;
        }
        if (!res.ok) {
            detail.innerHTML = '<div style="color:#ef4444;">Error (status ' + res.status + ').</div>';
            return;
        }
        const graph = await res.json();
        const opts = {
            view: el('trace-view').value,
            activity: el('trace-activity').value,
            kinds: new Set(el('trace-kinds').value.split(',').map((s) => s.trim()).filter(Boolean))
        };
        drawGraph(toElements(graph, opts));
        const trunc = el('trace-truncated');
        trunc.style.display = graph.truncated ? 'block' : 'none';
        if (graph.truncated) {
            trunc.textContent = 'Result truncated at the max-node limit — raise it or narrow the anchor to see more.';
        }
        detail.innerHTML = '<div style="color:var(--muted);font-size:13px;">Click a node to inspect it.</div>';
    }

    function drawGraph(elements) {
        if (cy) cy.destroy();
        cy = cytoscape({
            container: el('trace-graph'),
            elements: elements,
            style: styleSheet(),
            layout: { name: 'dagre', rankDir: 'LR', nodeSep: 24, rankSep: 60 }
        });
        applyKindShapes({ cy: cy });
        cy.on('tap', 'node', (evt) => {
            const id = evt.target.data('id');
            if (id && id.indexOf('mint:') !== 0) showDetail(id);
        });
    }

    async function showDetail(eventId) {
        const detail = el('trace-detail');
        detail.innerHTML = '<div style="color:var(--muted);font-size:13px;">Loading event…</div>';
        let res;
        try {
            res = await traceFetch('/trace/events/' + encodeURIComponent(eventId));
        } catch (e) {
            detail.innerHTML = '<div style="color:#ef4444;">Request failed: ' + e + '</div>';
            return;
        }
        if (!res.ok) {
            detail.innerHTML = '<div style="color:#ef4444;">Could not load event (status ' + res.status + ').</div>';
            return;
        }
        const event = await res.json();
        const mode = escapeHtml(event.returnedPrivacyMode || 'minimal');
        const kind = escapeHtml(event.kind || 'event');
        const anchorBtn = '<button class="ghost-btn" id="trace-anchor-here">Anchor here</button>';
        const note = mode === 'full'
            ? '<span style="color:#22c55e;">full payload — secrets visible to your access level</span>'
            : '<span style="color:var(--muted);">' + mode + ' payload — secrets withheld at your access level</span>';
        detail.innerHTML = '<div class="row" style="justify-content:space-between;align-items:center;">'
            + '<strong>' + kind + '</strong>' + anchorBtn + '</div>'
            + '<div style="font-size:12px;margin:6px 0;">' + note + '</div>'
            + '<pre style="white-space:pre-wrap;font-size:11px;">' + escapeHtml(JSON.stringify(event, null, 2)) + '</pre>';
        const btn = el('trace-anchor-here');
        if (btn) {
            btn.addEventListener('click', () => {
                el('trace-anchor-type').value = 'eventId';
                el('trace-anchor-id').value = eventId;
                render();
            });
        }
    }

    function escapeHtml(text) {
        return text.replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));
    }

    // Live ingestion notifications. EventSource cannot carry the NIP-98 header, so the
    // stream only connects in deployments that authenticate it by session/cookie; failures
    // degrade silently rather than disrupting the rendered graph.
    function connectStream() {
        let source;
        try {
            source = new EventSource(apiUrl('/trace/stream'));
        } catch (e) {
            return;
        }
        source.addEventListener('trace', () => {
            const trunc = el('trace-truncated');
            if (trunc) {
                trunc.style.display = 'block';
                trunc.textContent = 'New events ingested — re-render to include them.';
            }
        });
        source.onerror = () => source.close();
    }

    function bindControls() {
        el('trace-render-btn').addEventListener('click', render);
        el('trace-depth').addEventListener('input', (e) => el('trace-depth-val').textContent = e.target.value);
        el('trace-maxnodes').addEventListener('input', (e) => el('trace-maxnodes-val').textContent = e.target.value);
        el('trace-anchor-id').addEventListener('keydown', (e) => { if (e.key === 'Enter') render(); });
    }

    document.addEventListener('DOMContentLoaded', () => {
        if (!el('trace-render-btn')) return;
        bindControls();
        detectAuthority();
        connectStream();
    });
})();
