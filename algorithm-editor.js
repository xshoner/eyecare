/* Browser-only configuration editor. Personal rows stay in memory; only weights are saved. */
(function () {
  'use strict';
  const M = window.EyeCareWhatIf;
  const KEY = 'eyecare_whatif_config_v2';
  const node = (tag, className, text) => {
    const el = document.createElement(tag);
    if (className) el.className = className;
    if (text !== undefined) el.textContent = text;
    return el;
  };
  function storedConfig() {
    try {
      const value = JSON.parse(localStorage.getItem(KEY) || 'null');
      return value?.version === M.VERSION && !M.validate(value.items) ? value : null;
    } catch { return null; }
  }
  function saveConfig(items) {
    const value = { version: M.VERSION, savedAt: new Date().toISOString(), items: M.copy(items) };
    localStorage.setItem(KEY, JSON.stringify(value));
    return value;
  }
  function downloadConfig(config) {
    const blob = new Blob([JSON.stringify(config, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url; link.download = `${M.VERSION}-config.json`;
    document.body.append(link); link.click(); link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  const printScore = value => value === null ? '—' : `${value}`;
  const printPercent = value => `${Math.round(value)}%`;
  const isScore = value => typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 100;
  const isNrs = value => Number.isInteger(value) && value >= 0 && value <= 10;
  const safeDate = value => Number.isFinite(Date.parse(value))
    ? new Date(value).toLocaleDateString('ko-KR') : '시각 없음';

  function mount(root, { rows = [], mode = 'synthetic' } = {}) {
    if (!root || !M) throw Error('What-if editor root is missing');
    let data = [...rows];
    let saved = storedConfig() || { version: M.VERSION, savedAt: null, items: M.base() };
    let draft = M.copy(saved.items);
    let notice = '';
    root.classList.add('algo-editor');
    const controls = node('div', 'algo-controls');
    const results = node('div', 'algo-results');
    root.replaceChildren(controls, results);

    function resultsRender() {
      results.replaceChildren();
      const real = mode === 'private' ? data.filter(row =>
        !String(row.algorithm_version || '').startsWith('synthetic-test-')) : data;
      const sample = [...real].sort((a, b) => String(b.observed_at || '').localeCompare(String(a.observed_at || ''))).slice(0, 12);
      const valid = M.normalized(draft);
      const summary = node('section', 'algo-panel');
      summary.append(node('p', 'algo-kicker', mode === 'private' ? '내 연결 자료 · 이 브라우저 안에서만' : '합성 예시 · 공개 페이지'));
      summary.append(node('h3', '', '저장 영역의 가상 평균 비교'));
      const subtitle = mode === 'private'
        ? `연결된 본인 관측 ${real.length}건 중 최근 ${sample.length}건을 살펴봅니다. 실제 관측값은 저장하거나 전송하지 않습니다.`
        : '고정된 합성 사례입니다. 실제 건강 기록이나 학습 성능을 뜻하지 않습니다.';
      summary.append(node('p', 'algo-muted', subtitle));
      if (valid.error) summary.append(node('p', 'algo-error', valid.error + ' 계산을 표시하지 않습니다.'));
      else summary.append(node('p', 'algo-muted', '표시 가중치는 포함 항목끼리 합계 100%로 정규화합니다. 결측은 0점으로 대체하지 않습니다.'));
      const wrapped = node('div', 'algo-table-wrap');
      const table = node('table', 'algo-table');
      const head = node('thead'); const hr = node('tr');
      ['관측 시각', '저장된 원점수', '과거 평균 가상값', '수정안 가상값', '가용 비중', 'NRS'].forEach(label => hr.append(node('th', '', label)));
      head.append(hr); table.append(head);
      const body = node('tbody');
      for (const row of sample) {
        const original = isScore(row.score) ? row.score : null;
        const historical = M.baseForVersion(row.algorithm_version);
        const base = historical ? M.simulate(row, historical) : { score: null };
        const proposed = M.simulate(row, draft);
        const tr = node('tr');
        [safeDate(row.observed_at), printScore(original), printScore(base.score),
          printScore(proposed.score), printPercent(proposed.coverage),
          isNrs(row.subjective_fatigue_nrs) ? `${row.subjective_fatigue_nrs}/10` : '—']
          .forEach(value => tr.append(node('td', '', value)));
        body.append(tr);
      }
      if (!sample.length) {
        const tr = node('tr'); const td = node('td', '', '표시할 관측이 없습니다. 연결이나 동기화 상태를 확인해 주세요.');
        td.colSpan = 6; tr.append(td); body.append(tr);
      }
      table.append(body); wrapped.append(table); summary.append(wrapped);
      summary.append(node('p', 'algo-fine', '0.10 앱 점수는 관측 시간에 누적한 값으로, 서버 영역 평균만으로 복원할 수 없습니다. 과거 평균 가상값도 0.7/0.5의 완전한 복원이 아닙니다. 가상값은 앱 점수나 DB 행을 변경하지 않습니다.'));
      const pairs = sample.filter(row => isScore(row.score) && isNrs(row.subjective_fatigue_nrs)).length;
      summary.append(node('p', 'algo-fine', `점수와 NRS가 함께 있는 최근 표본: ${pairs}건. 이 수치로 가중치를 학습하거나 성능을 주장하지 않습니다.`));
      results.append(summary);

      const latest = sample.find(row => M.simulate(row, draft).score !== null);
      const contribution = node('section', 'algo-panel');
      contribution.append(node('h3', '', '최근 가용 관측의 가상 기여도'));
      if (!latest || valid.error) contribution.append(node('p', 'algo-muted', '계산 가능한 영역·품질이 부족합니다. 미측정 값을 만들지 않습니다.'));
      else {
        const proposal = M.simulate(latest, draft);
        for (const item of proposal.contributions) {
          const row = node('div', 'algo-contribution');
          row.append(node('span', '', item.label), node('strong', '', item.points === null ? '미측정' : `${item.points.toFixed(1)}점`));
          const track = node('div', 'algo-track');
          const bar = node('div'); bar.style.width = `${Math.min(100, Math.max(0, item.points || 0))}%`;
          track.append(bar); row.append(track); contribution.append(row);
        }
        contribution.append(node('p', 'algo-fine', `가용 비중 ${printPercent(proposal.coverage)} · 저장 수치의 개별 품질은 제공되지 않아 추가한 숫자 지표는 품질 100%로 가정한 가상 계산입니다.`));
      }
      results.append(contribution);
    }

    function render() {
      controls.replaceChildren();
      const intro = node('section', 'algo-panel');
      intro.append(node('p', 'algo-kicker', `설정 버전 ${M.VERSION}`), node('h3', '', '가중치와 지표 구성'));
      intro.append(node('p', 'algo-muted', '0.7 설계 비중은 화면 26 · 환경 22 · 자세 8 · 사용 18 · 눈 26입니다. 사용자 선택값이며 학습·임상 검증 비중이 아닙니다.'));
      if (saved.savedAt) intro.append(node('p', 'algo-fine', `이 브라우저에 마지막 저장: ${safeDate(saved.savedAt)}`));
      const normalized = M.normalized(draft);
      intro.append(node('p', normalized.error ? 'algo-error' : 'algo-highlight',
        normalized.error || '포함된 가중치의 정규화 합계 100%'));
      const list = node('div', 'algo-metrics');
      draft.forEach((item, index) => {
        const definition = M.definitions.find(entry => entry.id === item.id);
        const label = definition?.label || item.label;
        const box = node('div', 'algo-metric');
        const header = node('div', 'algo-metric-head');
        const checkLabel = node('label', 'algo-check');
        const check = document.createElement('input'); check.type = 'checkbox'; check.checked = item.enabled;
        check.setAttribute('aria-label', `${label} 포함`);
        check.addEventListener('change', () => { draft[index].enabled = check.checked; render(); });
        checkLabel.append(check, node('strong', '', label));
        header.append(checkLabel);
        const share = node('output', 'algo-share', `${(normalized.weights[index]?.share || 0).toFixed(1)}%`);
        header.append(share); box.append(header);
        box.append(node('p', 'algo-fine', definition?.detail || `미구현 · 필요 데이터: ${item.dataNeeded} · 제안 산식: ${item.formula}`));
        const sliderLabel = node('label', 'algo-slider-label', '원시 가중치 ');
        const output = node('output', '', String(item.weight)); sliderLabel.append(output);
        const slider = document.createElement('input'); slider.type = 'range'; slider.min = '0'; slider.max = '100';
        slider.value = String(item.weight); slider.setAttribute('aria-label', `${label} 원시 가중치`);
        slider.addEventListener('input', () => {
          draft[index].weight = Number(slider.value); output.textContent = slider.value;
          const recalculated = M.normalized(draft);
          for (const [i, cell] of [...list.querySelectorAll('.algo-share')].entries())
            cell.textContent = `${(recalculated.weights[i]?.share || 0).toFixed(1)}%`;
          const status = intro.querySelector('.algo-status');
          status.className = `algo-status ${recalculated.error ? 'algo-error' : 'algo-highlight'}`;
          status.textContent = recalculated.error || '포함된 가중치의 정규화 합계 100%';
          resultsRender();
        });
        box.append(sliderLabel, slider);
        if (definition?.type === 'stored' || item.id.startsWith('custom:')) {
          const remove = node('button', 'algo-link', '목록에서 제거'); remove.type = 'button';
          remove.addEventListener('click', () => { draft.splice(index, 1); render(); }); box.append(remove);
        }
        list.append(box);
      });
      const status = intro.querySelector('.algo-highlight, .algo-error');
      status.classList.add('algo-status');
      intro.append(list);
      const actions = node('div', 'algo-actions');
      const button = (label, handler, cls = '') => { const el = node('button', cls, label); el.type = 'button'; el.addEventListener('click', handler); return el; };
      actions.append(button('이 브라우저에 저장', () => {
        const error = M.validate(draft);
        if (error) { notice = error; render(); return; }
        try { saved = saveConfig(draft); notice = '설정만 이 브라우저에 저장했습니다. 관측값과 앱 점수는 그대로입니다.'; }
        catch { notice = '브라우저 저장 공간을 사용할 수 없습니다. JSON으로 내보낼 수 있습니다.'; }
        render();
      }, 'algo-primary'));
      actions.append(button('취소', () => { draft = M.copy(saved.items); notice = '저장된 설정으로 되돌렸습니다.'; render(); }));
      actions.append(button('기준안 복원', () => { draft = M.base(); notice = '기준안이 편집 중인 설정에 적용됐습니다. 저장 버튼을 눌러 확정하세요.'; render(); }));
      actions.append(button('설정 JSON 내보내기', () => { downloadConfig({ version: M.VERSION,
        exportedAt: new Date().toISOString(), items: M.copy(draft) }); }));
      intro.append(actions);
      if (notice) intro.append(node('p', 'algo-fine', notice));
      controls.append(intro);

      const add = node('section', 'algo-panel'); add.append(node('h3', '', '저장된 지표 추가'));
      add.append(node('p', 'algo-muted', '추가 지표는 원래 영역에 이미 반영됐을 수 있습니다. 가상 계산에서만 별도로 가중합니다.'));
      const select = document.createElement('select'); select.setAttribute('aria-label', '추가할 저장 지표');
      for (const def of M.definitions.filter(entry => entry.type === 'stored' && !draft.some(item => item.id === entry.id))) {
        const option = node('option', '', `${def.label} · 보유 ${data.filter(row => typeof row[def.id] === 'number').length}건`);
        option.value = def.id; select.append(option);
      }
      if (!select.options.length) select.append(node('option', '', '추가 가능한 저장 지표 없음'));
      const addButton = node('button', '', '추가'); addButton.type = 'button'; addButton.disabled = !M.definitions.some(entry => entry.id === select.value);
      addButton.addEventListener('click', () => { const next = M.addStored(draft, select.value); if (next) { draft = next; render(); } });
      add.append(select, addButton);
      const details = node('details', 'algo-dictionary'); details.append(node('summary', '', '측정 지표 사전과 데이터 한계'));
      for (const item of M.dictionary) details.append(node('p', 'algo-fine', `${item.label} (${item.id}) · ${item.note}`));
      add.append(details); controls.append(add);

      const custom = node('section', 'algo-panel'); custom.append(node('h3', '', '새 지표 아이디어 기록'));
      custom.append(node('p', 'algo-muted', '센서·수집 코드가 없는 지표는 미구현으로 표시하며 가상점수에도 측정값을 만들지 않습니다.'));
      const form = node('form', 'algo-custom');
      const field = (label, name, placeholder) => { const wrap = node('label', '', label); const input = node('input');
        input.name = name; input.placeholder = placeholder; input.maxLength = name === 'label' ? 80 : 180;
        wrap.append(input); form.append(wrap); return input; };
      const name = field('지표 이름', 'label', '예: 눈 휴식 간격');
      const need = field('필요한 데이터', 'dataNeeded', '예: 휴식 시작·종료 시각');
      const formula = field('제안 산식', 'formula', '예: 하루 간격의 중앙값');
      const submit = node('button', '', '미구현 지표로 추가'); submit.type = 'submit'; form.append(submit);
      form.addEventListener('submit', event => { event.preventDefault();
        const next = M.addCustom(draft, { label: name.value, dataNeeded: need.value, formula: formula.value });
        notice = next ? '미구현 지표를 목록에 추가했습니다. 실제 측정값으로 계산하지 않습니다.' : '중복 이름 또는 입력 누락을 확인해 주세요.';
        if (next) draft = next; render(); });
      custom.append(form); controls.append(custom);
      resultsRender();
    }
    render();
    return { setRows(next) { data = [...next]; resultsRender(); }, getDraft() { return M.copy(draft); } };
  }
  window.EyeCareAlgorithmEditor = { mount };
})();
