(function () {
  'use strict';
  const BASE = 'https://fdwmhfruvdyrxidwuvqx.supabase.co';
  const KEY = 'sb_publishable_23y3Jc2Jj1Ko5FiKChB1oA_9sriwmby';
  const GRANT_KEY = 'eyecare_browser_grant_v1';
  const A = window.EyeCareAnalysis;
  const $ = id => document.getElementById(id);
  let working = false;
  let editor = null;
  let lastRendered = null;
  let showPersonal = true;

  function canonicalUuid(value) {
    return typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
  }
  function stored(key) {
    try { return JSON.parse(localStorage.getItem(key) || 'null'); } catch (_) { return null; }
  }
  function persist(key, value) {
    localStorage.setItem(key, JSON.stringify(value));
  }
  async function request(method, path, body, token) {
    const headers = { apikey: KEY };
    if (token) headers.Authorization = `Bearer ${token}`;
    if (body !== undefined) {
      headers['Content-Type'] = 'application/json';
      if (path.startsWith('/rest/v1/')) headers.Prefer = 'return=minimal';
    }
    const response = await fetch(BASE + path, {
      method, headers, body: body === undefined ? undefined : JSON.stringify(body), cache: 'no-store'
    });
    if (!response.ok) {
      const error = new Error(`HTTP ${response.status}`);
      error.status = response.status;
      throw error;
    }
    const raw = await response.text();
    return raw ? JSON.parse(raw) : null;
  }
  const browserSession = window.EyeCareBrowserSession.create({
    request, storage: localStorage, locks: navigator.locks
  });
  const ensureSession = browserSession.ensure;
  async function rest(path) {
    let session = await ensureSession();
    try { return await request('GET', path, undefined, session.access_token); }
    catch (error) {
      if (error.status !== 401) throw error;
      session = await ensureSession(true);
      return request('GET', path, undefined, session.access_token);
    }
  }
  function setStatus(state, message) {
    const labels = { checking: '연결 확인 중', waiting: '연결 대기', linked: '연결됨',
      expired: '연결 만료', revoked: '연결 철회', identity_changed: '브라우저 ID 불일치',
      session_recovery_needed: '세션 복구 필요', storage_unavailable: '저장소 사용 불가',
      offline: '네트워크 오류', error: '서버 오류' };
    $('connection-state').textContent = labels[state] || state;
    $('connection-state').dataset.state = state;
    $('status-message').textContent = message;
  }
  function renderQr(id) {
    if (!canonicalUuid(id)) throw new Error('Invalid browser ID');
    const code = window.qrcode(0, 'M');
    code.addData(id.toLowerCase());
    code.make();
    $('pair-qr').innerHTML = code.createSvgTag({ cellSize: 5, margin: 8, scalable: true });
    $('browser-id').textContent = id.toLowerCase();
    $('current-browser-id').textContent = id.toLowerCase();
  }
  function textNode(tag, value, className) {
    const node = document.createElement(tag);
    node.textContent = value;
    if (className) node.className = className;
    return node;
  }
  const dateLabel = value => new Date(value).toLocaleString('ko-KR', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' });
  const svgNs = 'http://www.w3.org/2000/svg';
  function svgNode(tag, attrs = {}) {
    const node = document.createElementNS(svgNs, tag);
    for (const [key, value] of Object.entries(attrs)) node.setAttribute(key, String(value));
    return node;
  }
  function svgText(x, y, value, cssClass, anchor = 'middle') {
    const node = svgNode('text', { x, y, 'text-anchor': anchor, class: cssClass });
    node.textContent = value;
    return node;
  }
  function basePlot(width = 820, height = 280) {
    const svg = svgNode('svg', { viewBox: `0 0 ${width} ${height}`, 'preserveAspectRatio': 'xMidYMid meet' });
    svg.classList.add('plot-svg');
    return svg;
  }
  function drawTimeline(rows) {
    const root = $('timeline');
    root.replaceChildren();
    const dated = A.newestVersionRows(rows);
    const scored = dated.filter(A.comparableScore);
    if (!scored.length) {
      root.append(textNode('p', '점수가 있는 실제 관측이 아직 없습니다.', 'empty-inline'));
      $('peak-label').textContent = '';
      return;
    }
    const svg = basePlot(), left = 60, right = 790, top = 20, bottom = 218;
    for (const value of [0, 25, 50, 75, 100]) {
      const y = bottom - value / 100 * (bottom - top);
      svg.append(svgNode('line', { x1: left, x2: right, y1: y, y2: y, class: 'grid-line' }));
      svg.append(svgText(left - 12, y + 4, String(value), 'axis-label', 'end'));
    }
    const times = dated.map(row => Date.parse(row.observed_at));
    const min = Math.min(...times), max = Math.max(...times);
    const xFor = time => min === max ? (left + right) / 2 : left + (time - min) / (max - min) * (right - left);
    const ticks = min === max ? [min] : Array.from({ length: 5 }, (_, i) => min + (max - min) * i / 4);
    ticks.forEach(t => svg.append(svgText(xFor(t), 247, dateLabel(t), 'axis-label')));
    svg.append(svgText(18, 14, '점수', 'axis-title', 'start'));
    svg.append(svgText(790, 271, '관측 시각', 'axis-title', 'end'));
    let segment = [];
    function flush() {
      if (segment.length > 1) svg.append(svgNode('polyline', {
        points: segment.map(p => `${p.x},${p.y}`).join(' '), class: 'score-line'
      }));
      segment = [];
    }
    dated.forEach(row => {
      if (!A.comparableScore(row)) { flush(); return; }
      segment.push({ x: xFor(Date.parse(row.observed_at)), y: bottom - row.score / 100 * (bottom - top) });
    });
    flush();
    const highest = A.peak(scored);
    scored.forEach(row => {
      const x = xFor(Date.parse(row.observed_at)), y = bottom - row.score / 100 * (bottom - top);
      if (row === highest) svg.append(svgNode('circle', { cx: x, cy: y, r: 11, class: 'peak-halo' }));
      const point = svgNode('circle', { cx: x, cy: y, r: row === highest ? 6 : 4.5,
        class: `score-dot ${row.confidence === 'adequate' ? 'adequate' : 'partial'}` });
      const title = svgNode('title');
      title.textContent = `${dateLabel(row.observed_at)} · ${row.score}점 · ${row.confidence || '품질 정보 없음'}`;
      point.append(title);
      svg.append(point);
    });
    root.append(svg);
    $('peak-label').textContent = `${highest.algorithm_version} · 이 버전 최대 ${highest.score}점 · ${dateLabel(highest.observed_at)}`;
  }
  function drawDistance(rows) {
    const root = $('distance-timeline'); root.replaceChildren();
    const summary = A.distanceSummary(rows);
    $('distance-typical').textContent = summary.typicalCm === null ? '측정 근거 부족'
      : `주 거리 약 ${Math.round(summary.typicalCm)} cm`;
    const latest = [...summary.measured].sort((a, b) => Date.parse(b.observed_at) - Date.parse(a.observed_at))[0];
    $('distance-quality').textContent = summary.windowMinutes === 0
      ? '서버에 거리 집계가 있는 새 보고서가 아직 없습니다. 이전 보고서의 거리를 0으로 채우지 않습니다.'
      : `유효 관측 ${summary.observedMinutes.toFixed(1)} / ${summary.windowMinutes.toFixed(1)}분 · 시간 coverage ${Math.round(summary.coverage * 100)}% · 거리 보고서 ${summary.measured.length}개` +
        (latest ? ` · 최근 결합 품질 ${Math.round(latest.distance_estimate_quality * 100)}% · ${latest.distance_calibrated ? '사용자 기준 보정' : '미보정 추정'}` : ' · 유효 거리 부족');
    const points = rows.filter(row => !Number.isNaN(Date.parse(row.observed_at)) &&
      typeof row.distance_window_minutes === 'number').slice(-100);
    if (!summary.measured.length || !points.length) {
      root.append(textNode('p', '표시할 거리 추정이 아직 없습니다.', 'empty-inline')); return;
    }
    const svg = basePlot(), left = 60, right = 790, top = 20, bottom = 218;
    const y = cm => bottom - (cm - 15) / 65 * (bottom - top);
    for (const cm of [15, 30, 50, 80]) {
      svg.append(svgNode('line', { x1: left, x2: right, y1: y(cm), y2: y(cm),
        class: cm === 30 ? 'distance-guide' : 'grid-line' }));
      svg.append(svgText(left - 12, y(cm) + 4, String(cm), 'axis-label', 'end'));
    }
    const times = points.map(row => Date.parse(row.observed_at));
    const min = Math.min(...times), max = Math.max(...times);
    const x = time => min === max ? (left + right) / 2 : left + (time - min) / (max - min) * (right - left);
    (min === max ? [min] : [min, (min + max) / 2, max]).forEach(t =>
      svg.append(svgText(x(t), 247, dateLabel(t), 'axis-label')));
    svg.append(svgText(18, 14, 'cm', 'axis-title', 'start'));
    let segment = [];
    function flush() {
      if (segment.length > 1) svg.append(svgNode('polyline', {
        points: segment.map(p => `${p.x},${p.y}`).join(' '), class: 'distance-line'
      }));
      segment = [];
    }
    points.forEach(row => {
      const valid = summary.measured.includes(row);
      if (!valid) { flush(); return; }
      const cx = x(Date.parse(row.observed_at)), cy = y(row.distance_estimate_cm);
      segment.push({ x: cx, y: cy });
      const dot = svgNode('circle', { cx, cy, r: 5, class: 'distance-dot' });
      const title = svgNode('title');
      title.textContent = `${dateLabel(row.observed_at)} · 약 ${Math.round(row.distance_estimate_cm)} cm · 결합 품질 ${Math.round(row.distance_estimate_quality * 100)}% · 시간 coverage ${Math.round((row.distance_coverage ?? 0) * 100)}% · ${row.distance_calibrated ? '보정' : '미보정'}`;
      dot.append(title); svg.append(dot);
    });
    flush(); root.append(svg);
  }
  function drawNrs(rows) {
    const pairs = A.paired(A.newestVersionRows(rows));
    $('nrs-count').textContent = `같은 보고서에서 점수와 NRS가 모두 있는 관측 ${pairs.length}건. 서로 다른 척도이므로 임상 정확도로 해석하지 않습니다.`;
    const root = $('nrs-chart');
    root.replaceChildren();
    if (!pairs.length) { root.append(textNode('p', '비교할 응답이 아직 없습니다.', 'empty-inline')); return; }
    const svg = basePlot(), left = 58, right = 784, top = 18, bottom = 216;
    for (const value of [0, 25, 50, 75, 100]) {
      const x = left + value / 100 * (right - left);
      svg.append(svgNode('line', { x1: x, x2: x, y1: top, y2: bottom, class: 'grid-line' }));
      svg.append(svgText(x, 244, String(value), 'axis-label'));
    }
    for (const value of [0, 5, 10]) {
      const y = bottom - value / 10 * (bottom - top);
      svg.append(svgNode('line', { x1: left, x2: right, y1: y, y2: y, class: 'grid-line' }));
      svg.append(svgText(left - 12, y + 4, String(value), 'axis-label', 'end'));
    }
    svg.append(svgText(18, 14, 'NRS', 'axis-title', 'start'));
    svg.append(svgText(right, 270, '앱 점수', 'axis-title', 'end'));
    pairs.forEach(row => {
      const point = svgNode('circle', { cx: left + row.score / 100 * (right - left),
        cy: bottom - row.subjective_fatigue_nrs / 10 * (bottom - top), r: 5, class: 'nrs-dot' });
      const title = svgNode('title');
      title.textContent = `${dateLabel(row.observed_at)} · 점수 ${row.score} · NRS ${row.subjective_fatigue_nrs}`;
      point.append(title); svg.append(point);
    });
    root.append(svg);
  }
  function renderContributions(rows) {
    const root = $('contributions'); root.replaceChildren();
    const last = [...rows].reverse().find(A.comparableScore);
    if (!last) { root.append(textNode('p', '기여도를 재구성할 실제 점수가 없습니다.', 'empty-inline')); return; }
    for (const item of A.contributions(last)) {
      const line = document.createElement('div'); line.className = 'contribution';
      const heading = document.createElement('div'); heading.className = 'contribution-heading';
      heading.append(textNode('strong', item.label), textNode('span', item.reconstructedPoints === null ? '관측 없음'
        : `${item.reconstructedPoints.toFixed(1)}${item.kind === 'rate' ? '%p/관측분' : '점 가상 재구성'} · 품질 ${Math.round(item.quality * 100)}%`));
      const track = document.createElement('div'); track.className = 'track';
      const fill = document.createElement('div'); fill.style.width = `${item.score ?? 0}%`;
      track.append(fill); line.append(heading, track); root.append(line);
    }
    $('contribution-note').textContent = last.algorithm_version === 'efs-exposure-0.11'
      ? `${dateLabel(last.observed_at)} 관측 기준. 저장 점수는 현재 유효 입력의 고정 가중 기여도입니다. 빠진 영역을 0으로 채우거나 나머지 영역을 100%로 늘리지 않았습니다. 오늘 누적 부담은 별도 로컬 지표로 서버에 저장되지 않습니다.`
      : last.algorithm_version === 'efs-exposure-0.10'
      ? `${dateLabel(last.observed_at)} 관측 기준. 영역의 분당 부담률 중 관측된 부분만 가상 재구성했습니다. 하루 누적 관측 시간·교호작용의 전체 적분은 서버에 없어 저장 점수를 역산할 수 없습니다. 결측은 낮은 부담이 아닙니다.`
      : `${dateLabel(last.observed_at)} 관측 기준. ${last.algorithm_version}의 품질 가중 평균을 가상 재구성했습니다. 과거 원점수·버전은 변경하지 않습니다.`;
  }
  function renderQuality(rows) {
    const root = $('quality-summary'); root.replaceChildren();
    if (!rows.length) return;
    const versions = new Map(), confidence = new Map();
    let estimated = 0, missing = 0;
    for (const row of rows) {
      versions.set(row.algorithm_version || '알 수 없음', (versions.get(row.algorithm_version || '알 수 없음') || 0) + 1);
      confidence.set(row.confidence || '알 수 없음', (confidence.get(row.confidence || '알 수 없음') || 0) + 1);
      estimated += Array.isArray(row.estimated_fields) ? row.estimated_fields.length : 0;
      missing += A.domains.filter(domain => row[`${domain.id}_score`] === null).length;
    }
    root.append(textNode('p', `알고리즘 버전: ${[...versions].map(([name, n]) => `${name} ${n}건`).join(' · ')}`));
    root.append(textNode('p', `신뢰도: ${[...confidence].map(([name, n]) => `${name} ${n}건`).join(' · ')}`));
    root.append(textNode('p', `영역 점수 결측 ${missing}개 · 추정 표시 필드 ${estimated}개 (관측 전체 기준)`));
    root.append(textNode('p', `비교 가능한 점수 ${rows.filter(A.comparableScore).length}개 · 저장 점수 ${rows.filter(row => A.isScore(row.score)).length}개. 0.10은 화면·사용과 추가 영역 근거가 있는 저장 점수만, 0.7/0.5는 당시 가용 비중 30% 이상만 비교에 사용합니다. 시계열은 최신 한 산식 버전만 연결합니다.`));
  }
  function renderTable(rows, responses) {
    const body = $('observations-body'); body.replaceChildren();
    for (const row of [...rows].reverse().slice(0, 100)) {
      const tr = document.createElement('tr');
      const personal = A.personalized(row, rows, responses);
      for (const value of [dateLabel(row.observed_at), A.isScore(row.score) ? `${row.score}/100${A.comparableScore(row) ? '' : ' · 근거 부족'}` : '결측',
        !showPersonal ? '표시 끔 · 원점수 유지' : personal.score === null
          ? `대기 (${personal.samples}/5)` : `${personal.score}/100 · 이전 ${personal.samples}개`,
        Number.isInteger(row.subjective_fatigue_nrs) ? `${row.subjective_fatigue_nrs}/10${row.subjective_source === 'response' ? ' · 최신' : ' · 이전 방식'}` : '미응답',
        row.confidence || '정보 없음', row.algorithm_version || '정보 없음']) tr.append(textNode('td', value));
      body.append(tr);
    }
  }
  function renderData(allRows, responses, expiresAt) {
    lastRendered = { allRows, responses, expiresAt };
    const joined = A.attachResponses(allRows, responses);
    const { real, synthetic } = A.partition(joined);
    const pairs = A.paired(real);
    const newest = [...real].reverse().find(A.comparableScore);
    const personal = newest ? A.personalized(newest, real, responses) : null;
    const validation = A.validation(real, responses);
    $('empty-state').hidden = real.length !== 0;
    const cards = [
      ['표식 없는 관측 후보', `${real.length}건`], ['점수 관측', `${real.filter(A.comparableScore).length}건`],
      ['NRS 짝지음', `${pairs.length}건`], ['연결 만료', new Date(expiresAt).toLocaleDateString('ko-KR')]
    ];
    $('summary').replaceChildren(...cards.map(([label, value]) => {
      const item = document.createElement('div'); item.className = 'summary-card';
      item.append(textNode('span', label), textNode('strong', value)); return item;
    }));
    $('personal-summary').textContent = !showPersonal
      ? '개인 참고 점수 표시를 껐습니다. 원점수 해석과 원자료는 그대로 유지됩니다.'
      : newest ? personal.score === null
      ? `최근 원점수 ${newest.score}/100 · 개인 기준 참고값은 이전의 유효 응답 5개부터 계산합니다 (현재 ${personal.samples}개).`
      : `최근 원점수 ${newest.score}/100 · 개인 기준 참고 ${personal.score}/100 · 이전 응답 ${personal.samples}개만 사용했습니다.`
      : '비교할 수 있는 점수가 아직 없습니다.';
    const change = validation.rawMae === null ? null : validation.personalMae - validation.rawMae;
    $('personal-validation').textContent = `같은 산식 ${validation.version || '없음'} · 비교 가능 ${validation.sameVersionReports}건 · 적격 응답 ${validation.eligibleLabels}건 · 과거 응답만 사용한 순차 검증 ${validation.evaluated}쌍` +
      (validation.firstEvaluatedAt ? ` · 기간 ${dateLabel(validation.firstEvaluatedAt)} ~ ${dateLabel(validation.lastEvaluatedAt)}` : '') +
      (change === null ? ' · 오차 미수집' : ` · NRS×10 대비 평균 절대 차이 원점수 ${validation.rawMae.toFixed(1)} / 개인 참고 ${validation.personalMae.toFixed(1)}점` +
        (change > .05 ? ` · 차이 ${change.toFixed(1)}점 증가 (개선 없음)` : change < -.05 ? ` · 차이 ${(-change).toFixed(1)}점 감소` : ' · 차이 변화 없음')) +
      (validation.sufficientEvidence ? ' · 본인 체감과의 일치도이며 임상 정확도가 아닙니다.' : ' · 표본 부족: 보정 시작 조건과 성능 입증은 다릅니다.');
    drawTimeline(real); drawDistance(real); drawNrs(real); renderContributions(real); renderQuality(real); renderTable(real, responses);
    $('synthetic-panel').hidden = synthetic.length === 0;
    $('synthetic-count').textContent = `${synthetic.length}건 · 실제 분석에서 제외`;
    if (!editor) editor = window.EyeCareAlgorithmEditor.mount($('algorithm-root'), { rows: real, mode: 'private' });
    else editor.setRows(real);
  }
  async function observations(ownerId) {
    if (!canonicalUuid(ownerId)) throw new Error('Invalid owner ID');
    const columns = ['id','report_id','observed_at','algorithm_version','score','confidence','subjective_fatigue_nrs',
      'estimated_fields','screen_change','continuous_minutes',
      'distance_estimate_cm','distance_estimate_quality','distance_coverage',
      'distance_calibrated','distance_valid_minutes','distance_window_minutes',
      ...A.domains.flatMap(d => [`${d.id}_score`,`${d.id}_quality`,`${d.id}_missing_code`])].join(',');
    const rows = [];
    for (let offset = 0; offset < 5000; offset += 500) {
      const page = await rest(`/rest/v1/research_observations?owner_id=eq.${ownerId}&select=${columns}&order=observed_at.asc&limit=500&offset=${offset}`);
      rows.push(...page);
      if (page.length < 500) break;
    }
    return rows;
  }
  async function subjectiveResponses(ownerId) {
    if (!canonicalUuid(ownerId)) throw new Error('Invalid owner ID');
    const rows = [];
    for (let offset = 0; offset < 5000; offset += 500) {
      const page = await rest(`/rest/v1/research_subjective_responses?owner_id=eq.${ownerId}&select=id,report_id,subjective_fatigue_nrs,answered_at,received_at,previous_response_id&order=received_at.asc,id.asc&limit=500&offset=${offset}`);
      rows.push(...page);
      if (page.length < 500) break;
    }
    return rows;
  }
  async function sync() {
    if (working) { setStatus('checking', '연결 확인이 진행 중입니다. 잠시 기다려 주세요.'); return; }
    working = true;
    $('check-pair').disabled = true; $('refresh-data').disabled = true;
    $('check-pair').textContent = '확인 중…';
    try {
      setStatus('checking', '연결 상태를 확인하고 있습니다.');
      const session = await ensureSession();
      renderQr(session.user_id);
      $('current-browser-id').textContent = session.user_id;
      const grants = await rest(`/rest/v1/research_browser_grants?browser_id=eq.${session.user_id}&select=owner_id,browser_id,expires_at,revoked_at`);
      const grant = grants[0] || null;
      const prior = stored(GRANT_KEY);
      const state = grant ? A.grantState(prior, grant)
        : prior?.browser_id && prior.browser_id !== session.user_id ? 'identity_changed'
          : A.grantState(prior?.browser_id === session.user_id ? prior : null, null);
      $('pair-panel').hidden = state === 'linked';
      $('analysis').hidden = state !== 'linked';
      if (state !== 'linked') {
        const message = state === 'expired' ? '이 브라우저의 30일 연결이 만료됐습니다. 휴대폰에서 현재 웹 ID를 다시 연결해 주세요.'
          : state === 'revoked' ? '이 브라우저의 연결이 철회됐습니다. 휴대폰에서 현재 웹 ID를 다시 연결해 주세요.'
            : state === 'identity_changed' ? '휴대폰이 연결한 이전 웹 ID와 지금 보이는 ID가 다릅니다. 휴대폰의 웹 연결 메뉴에서 현재 ID를 다시 승인해 주세요.'
              : '휴대폰의 웹 연결 메뉴에서 현재 웹 ID의 QR을 스캔하고 승인해 주세요. 승인 후 자동으로 갱신됩니다.';
        setStatus(state, message);
        return;
      }
      persist(GRANT_KEY, { browser_id: session.user_id, expires_at: grant.expires_at });
      const rows = await observations(grant.owner_id);
      const responses = await subjectiveResponses(grant.owner_id);
      renderData(rows, responses, grant.expires_at);
      setStatus('linked', rows.length ? '이 브라우저에 승인된 연구 집계만 표시합니다.' : '연결됐습니다. 아직 자동 동기화된 기록이 없습니다.');
    } catch (error) {
      $('analysis').hidden = true;
      if (error.code === 'session_recovery_needed' || error.code === 'identity_mismatch') {
        $('pair-panel').hidden = true;
        try { $('current-browser-id').textContent = browserSession.saved()?.user_id || '확인 불가'; }
        catch (_) { $('current-browser-id').textContent = '확인 불가'; }
        setStatus('session_recovery_needed', '기존 웹 ID를 보존했습니다. 세션을 복구할 수 없어 새 계정을 자동 생성하지 않았습니다. 같은 브라우저의 저장소·쿠키 설정을 확인해 주세요.');
      } else if (error.code === 'storage_unavailable') {
        $('pair-panel').hidden = true;
        setStatus('storage_unavailable', '브라우저 저장소를 사용할 수 없어 연결 ID를 안전하게 유지할 수 없습니다. 사이트 저장소 설정을 확인해 주세요.');
      } else {
        setStatus(error.status ? 'error' : 'offline', error.status
          ? '서버 응답 ' + error.status + '. 연결을 확인하지 못했습니다. 다시 시도해 주세요.'
          : '네트워크 연결을 확인하지 못했습니다. 온라인 상태에서 다시 시도해 주세요.');
      }
    } finally {
      working = false;
      $('check-pair').disabled = false; $('refresh-data').disabled = false;
      $('check-pair').textContent = '연결 다시 확인';
    }
  }
  $('check-pair').addEventListener('click', sync);
  $('personal-toggle').addEventListener('change', event => {
    showPersonal = event.target.checked;
    if (lastRendered) renderData(lastRendered.allRows, lastRendered.responses, lastRendered.expiresAt);
  });
  $('refresh-data').addEventListener('click', sync);
  function download(name, text) {
    // The BOM lets spreadsheet apps read Korean text as UTF-8. Nothing is uploaded.
    const url = URL.createObjectURL(new Blob(['\uFEFF', text], { type: 'text/csv;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url; link.download = name; document.body.append(link); link.click(); link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  const stamp = () => new Date().toISOString().slice(0, 10).replace(/-/g, '');
  $('export-observations').addEventListener('click', () => {
    if (!lastRendered) return;
    download(`eyecare-observations-${stamp()}.csv`, A.observationsCsv(lastRendered.allRows, lastRendered.responses));
  });
  $('export-responses').addEventListener('click', () => {
    if (!lastRendered) return;
    download(`eyecare-nrs-responses-${stamp()}.csv`, A.responsesCsv(lastRendered.responses));
  });
  window.addEventListener('online', sync);
  window.addEventListener('storage', event => { if (event.key === window.EyeCareBrowserSession.AUTH_KEY) sync(); });
  document.addEventListener('visibilitychange', () => { if (!document.hidden) sync(); });
  setInterval(() => { if (!document.hidden) sync(); }, 15000);
  sync();
})();
