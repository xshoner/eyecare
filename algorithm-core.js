/* Pure, versioned what-if math. It never changes a stored observation or fits labels. */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.EyeCareWhatIf = api;
})(typeof window === 'undefined' ? globalThis : window, function () {
  const VERSION = 'whatif-domain-v2';
  const definitions = Object.freeze([
    { id: 'screen', label: '화면 자극', type: 'domain', weight: 26, detail: '빛 특성, 화면 변화·장면 전환, 깜빡임 위험' },
    { id: 'environment', label: '주변 환경', type: 'domain', weight: 22, detail: '실측 조도와 밝기 설정의 상대 위험, 온도·움직임' },
    { id: 'posture', label: '자세·움직임', type: 'domain', weight: 8, detail: '기울기, 흔들림, 상대적 머리 움직임' },
    { id: 'usage', label: '사용 습관', type: 'domain', weight: 18, detail: '연속 사용, 근거리 관측, 전환·잠금해제, 짧은 휴식' },
    { id: 'eyes', label: '눈 반응', type: 'domain', weight: 26, detail: '깜빡임, 눈 뜸·감김, 찡그림' },
    { id: 'screen_change', label: '화면 변화량', type: 'stored', weight: 10,
      detail: '저장된 수치 · 0.02~0.12 선형 위험도. 화면 영역과 중복될 수 있음', low: .02, high: .12, bound: 1 },
    { id: 'continuous_minutes', label: '연속 사용 시간', type: 'stored', weight: 10,
      detail: '저장된 분 · 10~120분 선형 위험도. 사용 영역과 중복될 수 있음', low: 10, high: 120, bound: 1440 }
  ]);
  const map = new Map(definitions.map(item => [item.id, item]));
  const dictionary = Object.freeze([
    { id: 'screen_change', label: '화면 변화량', note: '저장 수치. 가상 추가 가능. 화면 영역과 중복 가능.' },
    { id: 'ambient_lux', label: '주변 조도', note: '저장 수치이지만 화면 발광과의 대비가 필요해 단독 재계산 불가.' },
    { id: 'continuous_minutes', label: '연속 사용 시간', note: '저장 수치. 가상 추가 가능. 사용 영역과 중복 가능.' },
    { id: 'today_minutes', label: '오늘 사용 시간', note: '저장 수치. 앱의 조건부 상황 보정 근거이며 기본점수 지표가 아님.' },
    { id: 'blink_per_min', label: '분당 깜빡임', note: '개인 기준선과 신호 품질이 서버에 없어 원 점수 재계산 불가.' },
    { id: 'eye_open_mean', label: '평균 눈 개방도', note: '개인 기준선과 신호 품질이 서버에 없어 원 점수 재계산 불가.' },
    { id: 'dark_stimulus_minutes', label: '어두운 환경의 화면 자극', note: '상황 보정 근거. 추정값일 수 있어 기본점수로 추가하지 않음.' },
    { id: 'calibrated_distance_cm', label: '보정된 거리', note: '현재 수집 자료에 값이 없으며 앱에 보정 측정 구현이 없음.' },
    { id: 'distance_estimate_cm', label: '추정 거리', note: '0.7 앱 내부 신호이며 현재 연구 DB 스키마에는 없어 웹에서 계산할 수 없습니다.' }
  ]);
  const base = () => definitions.filter(item => item.type === 'domain')
    .map(item => ({ id: item.id, enabled: true, weight: item.weight }));
  const historicalBase = () => [20, 10, 15, 20, 35].map((weight, i) =>
    ({ id: definitions[i].id, enabled: true, weight }));
  const baseForVersion = version => version === 'efs-hypothesis-0.7' || version === 'synthetic-test-whatif' ? base()
    : version === 'efs-hypothesis-0.5' ? historicalBase() : null;
  const copy = value => JSON.parse(JSON.stringify(value));
  const range = (value, min, max) => typeof value === 'number' && Number.isFinite(value) && value >= min && value <= max;

  function validate(items) {
    if (!Array.isArray(items) || items.length > 16) return '지표 구성을 확인해 주세요.';
    const ids = new Set(), labels = new Set(definitions.map(item => item.label.toLocaleLowerCase()));
    for (const item of items) {
      if (!item || typeof item.id !== 'string' || ids.has(item.id)) return '중복 지표가 있습니다.';
      ids.add(item.id);
      if (!range(item.weight, 0, 100) || typeof item.enabled !== 'boolean') return '가중치는 0~100이어야 합니다.';
      if (item.id.startsWith('custom:')) {
        const label = String(item.label || '').trim();
        if (!label || !String(item.dataNeeded || '').trim() || !String(item.formula || '').trim())
          return '사용자 정의 지표의 이름·필요 데이터·산식을 적어 주세요.';
        if (labels.has(label.toLocaleLowerCase())) return '같은 이름의 지표가 있습니다.';
        labels.add(label.toLocaleLowerCase());
      } else if (!map.has(item.id)) return '지원하지 않는 지표가 있습니다.';
    }
    if (!items.some(item => item.enabled && item.weight > 0)) return '포함된 지표에 양수 가중치가 필요합니다.';
    return null;
  }

  function normalized(items) {
    const error = validate(items);
    if (error) return { error, weights: [] };
    const active = items.filter(item => item.enabled && item.weight > 0);
    const total = active.reduce((sum, item) => sum + item.weight, 0);
    return { error: null, weights: items.map(item => ({ ...item,
      share: item.enabled && item.weight > 0 ? item.weight / total * 100 : 0 })) };
  }

  function measured(row, id) {
    const definition = map.get(id);
    if (!definition) return null; // Custom entries require a future data and sensor contract.
    if (definition.type === 'domain') {
      const score = row[`${id}_score`], quality = row[`${id}_quality`];
      return range(score, 0, 100) && range(quality, 0, 1) && quality > 0 ? { risk: score, quality } : null;
    }
    const value = row[id];
    if (Array.isArray(row.estimated_fields) && row.estimated_fields.includes(id)) return null;
    if (!range(value, 0, definition.bound)) return null;
    const risk = Math.max(0, Math.min(100, (value - definition.low) / (definition.high - definition.low) * 100));
    return { risk, quality: 1 }; // The server has no per-field quality. A what-if assumption only.
  }

  function simulate(row, items) {
    const result = normalized(items);
    if (result.error) return { error: result.error, score: null, coverage: 0, contributions: [] };
    const parts = result.weights.filter(item => item.share > 0).map(item => {
      const value = measured(row, item.id);
      return { id: item.id, label: map.get(item.id)?.label || item.label,
        share: item.share, risk: value?.risk ?? null, quality: value?.quality ?? null,
        effective: value ? item.share * value.quality : 0 };
    });
    const effective = parts.reduce((sum, item) => sum + item.effective, 0);
    const coverage = parts.filter(item => item.risk !== null).reduce((sum, item) => sum + item.share, 0);
    const score = effective > 0 ? Math.round(parts.reduce((sum, item) => sum + item.risk * item.effective, 0) / effective) : null;
    return { error: null, score, coverage, contributions: parts.map(item => ({ ...item,
      points: effective > 0 && item.risk !== null ? item.risk * item.effective / effective : null })) };
  }

  function addStored(items, id) {
    const definition = map.get(id);
    if (!definition || definition.type !== 'stored' || items.some(item => item.id === id)) return null;
    return [...copy(items), { id, enabled: true, weight: definition.weight }];
  }
  function addCustom(items, { label, dataNeeded, formula }) {
    const clean = String(label || '').trim().slice(0, 80);
    if (!clean || !String(dataNeeded || '').trim() || !String(formula || '').trim() ||
        definitions.some(item => item.label.toLocaleLowerCase() === clean.toLocaleLowerCase()) ||
        items.some(item => item.id.startsWith('custom:') && item.label.toLocaleLowerCase() === clean.toLocaleLowerCase())) return null;
    return [...copy(items), { id: `custom:${Date.now()}:${Math.random().toString(36).slice(2, 8)}`,
      label: clean, dataNeeded: String(dataNeeded).trim().slice(0, 180),
      formula: String(formula).trim().slice(0, 180), enabled: false, weight: 0 }];
  }
  return { VERSION, definitions, dictionary, base, historicalBase, baseForVersion, copy, validate, normalized, simulate,
    addStored, addCustom };
});
