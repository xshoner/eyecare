const domains = [
  { id: 'screen', label: '화면 자극', weight: 20, value: 48 },
  { id: 'environment', label: '주변 환경', weight: 10, value: 36 },
  { id: 'posture', label: '자세·움직임', weight: 15, value: 22 },
  { id: 'usage', label: '사용 습관', weight: 20, value: 53 },
  { id: 'eyes', label: '눈 반응', weight: 35, value: 44 }
];
const controlRoot = document.getElementById('controls');
domains.forEach(domain => {
  const element = document.createElement('div');
  element.className = 'control';
  element.innerHTML = `<div class="control-head"><label for="risk-${domain.id}">${domain.label} <small class="control-meta">명목 ${domain.weight}%</small></label><span id="value-${domain.id}">${domain.value}</span></div><input type="range" min="0" max="100" value="${domain.value}" id="risk-${domain.id}" aria-label="${domain.label} 위험도"><label class="control-meta" for="quality-${domain.id}">관측 품질 <span id="quality-value-${domain.id}">100%</span></label><input type="range" min="0" max="100" value="100" step="5" id="quality-${domain.id}" aria-label="${domain.label} 관측 품질"><label class="check control-meta"><input type="checkbox" id="observed-${domain.id}" checked> 관측됨</label>`;
  controlRoot.append(element);
});

const $ = id => document.getElementById(id);
const bands = score => score < 25 ? '눈 피로도가 양호해요. 건강한 눈을 만들어가요.'
  : score < 50 ? '눈 피로도는 보통 정도예요. 너무 오래 보지 않기를 권장드려요.'
  : score < 75 ? '눈이 피곤해지고 있어요. 눈을 쉬게 해주세요.'
  : '눈이 너무 피곤해요. 오늘은 그만 쉬어주세요.';
function compute() {
  const active = domains.filter(domain => $(`observed-${domain.id}`).checked && Number($(`quality-${domain.id}`).value) > 0);
  const weight = active.reduce((sum, domain) => sum + domain.weight * Number($(`quality-${domain.id}`).value) / 100, 0);
  const base = weight ? Math.round(active.reduce((sum, domain) =>
    sum + domain.weight * Number($(`quality-${domain.id}`).value) / 100 * Number($(`risk-${domain.id}`).value), 0) / weight) : 0;
  const dark = Number($('dark-minutes').value);
  const today = Number($('daily-minutes').value);
  const distance = $('distance-calibrated').checked ? Number($('distance-cm').value) : null;
  const adjustments = [];
  if (dark >= 5) adjustments.push({ label: '어두움 × 밝고 변화가 큰 화면', points: dark >= 10 ? 8 : 4 });
  if (distance !== null && distance < 25) adjustments.push({ label: '실험용 보정 거리', points: distance < 20 ? 6 : 4 });
  if (today >= 240) adjustments.push({ label: '당일 누적 사용', points: today >= 480 ? 8 : today >= 360 ? 6 : 3 });
  let remaining = 12;
  const applied = adjustments.map(item => {
    const points = Math.min(item.points, remaining);
    remaining -= points;
    return { ...item, points };
  }).filter(item => item.points > 0);
  const correction = applied.reduce((sum, item) => sum + item.points, 0);
  const available = active.length >= 3 && weight >= 15;
  const total = Math.min(100, base + correction);
  $('total-score').textContent = available ? total : '—';
  $('base-score').textContent = available ? base : '—';
  $('adjustment-score').textContent = available ? `+${correction}` : '—';
  $('score-sentence').textContent = available ? bands(total) : '관측 근거가 부족해 점수를 표시하지 않습니다.';
  $('dark-output').textContent = `${dark}분`;
  $('daily-output').textContent = `${today}분`;
  $('distance-output').textContent = `${$('distance-cm').value} cm`;
  $('distance-row').hidden = !$('distance-calibrated').checked;
  $('contributions').replaceChildren(...domains.map(domain => {
    const measured = active.includes(domain);
    const risk = Number($(`risk-${domain.id}`).value);
    const quality = Number($(`quality-${domain.id}`).value) / 100;
    const contribution = measured && weight ? domain.weight * quality / weight * risk : null;
    $(`quality-value-${domain.id}`).textContent = `${Math.round(quality * 100)}%`;
    const element = document.createElement('div');
    element.className = 'contribution';
    element.innerHTML = `<div class="contribution-title"><span>${domain.label} · ${domain.weight}%</span><span>${measured ? `${contribution.toFixed(1)}점 기여` : '미측정'}</span></div><div class="track"><div style="width:${measured ? risk : 0}%"></div></div>`;
    $(`value-${domain.id}`).textContent = `${risk}${measured ? '' : ' · 미측정'}`;
    return element;
  }));
  $('adjustments').replaceChildren(...(applied.length ? applied : [{ label: '적용된 상황 보정 없음', points: 0 }]).map(item => {
    const row = document.createElement('div');
    row.textContent = `${item.points ? `+${item.points}점 · ` : ''}${item.label}`;
    return row;
  }));
}
document.querySelectorAll('input').forEach(input => input.addEventListener('input', compute));
compute();

// Fixed synthetic series; no personal or device records are loaded.
const synthetic = Array.from({ length: 48 }, (_, index) => {
  const wave = .018 + Math.abs(Math.sin(index * .78)) * .025;
  const burst = index >= 26 && index <= 30 ? (index === 28 ? .085 : .046) : 0;
  return Math.min(.13, wave + burst);
});
const peakIndex = synthetic.indexOf(Math.max(...synthetic));
const ceiling = Math.max(.08, ...synthetic);
const trend = $('trend-chart');
synthetic.forEach((value, index) => {
  const bar = document.createElement('div');
  bar.className = `trend-bar${index === peakIndex ? ' peak' : ''}`;
  bar.style.height = `${Math.max(3, value / ceiling * 100)}%`;
  bar.title = `시작 후 ${index * 10}초 · 화면 변화 ${value.toFixed(3)}`;
  trend.append(bar);
});
$('trend-peak').textContent = `최대 변화 구간 · 시작 후 ${Math.floor(peakIndex / 6)}분 ${(peakIndex % 6) * 10}초`;
