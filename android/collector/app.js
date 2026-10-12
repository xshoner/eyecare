const $ = id => document.getElementById(id);
let token = location.hash.slice(1) || sessionStorage.getItem('eyecare-token') || '';
if (token) sessionStorage.setItem('eyecare-token',token);
history.replaceState(null,'',location.pathname);
let data;
let initialEngineSelected=false;
const labels = {unspecified:'미선택',shorts:'쇼츠',video:'영상',game:'게임',reading:'읽기',mixed:'혼합'};
const qualityLabels = {baseline_wait:"기준 표본 대기",sample_gap:"1.5초 초과 표본 간격",end_tail:"종료 직전 미관측",pending:"다음 표본 대기",continuity_reset:"상태 전환 전 미관측",waiting:'분석 준비·기준 표본 대기',missing_frames:'새 표본 없음',dark_or_protected:'어둡거나 보호된 표본',hidden:'공유 앱이 보이지 않음',invalid:'표본 읽기 실패',resized:'크기 변경 후 대기',unknown:'이전 버전: 원인 미기록'};
const pct = v => v == null ? '—' : `${(v*100).toFixed(1)}%`;
const mins = v => `${(v/60000).toFixed(1)}분`;
const esc = v => String(v??'—').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
async function api(url,options={}) { const r=await fetch(url,{...options,headers:{Authorization:`Bearer ${token}`,...options.headers}}); if(!r.ok)throw new Error((await r.json()).error); return r; }
function totals(rows) {
 const sum=k=>rows.reduce((n,r)=>n+r[k],0), elapsed=sum('elapsed'),valid=sum('valid');
 return {elapsed,valid,high:sum('high'),coverage:elapsed?valid/elapsed:null,highFraction:valid?sum('high')/valid:null};
}
function table(headers,rows) { return `<table><thead><tr>${headers.map(h=>`<th>${esc(h)}</th>`).join('')}</tr></thead><tbody>${rows.map(row=>`<tr>${row.map(v=>`<td>${esc(v)}</td>`).join('')}</tr>`).join('')}</tbody></table>`; }
function options(id,values,label=v=>v) { const select=$(id),prior=select.value; select.replaceChildren(new Option('전체',''),...values.map(v=>new Option(label(v),v))); if(values.includes(prior))select.value=prior; }
function render() {
 if(!data)return;
 const rows=data.sessions.filter(s=>(!$('engine').value||s.engine===$('engine').value)&&(!$('device').value||s.device===$('device').value)&&(!$('content').value||s.content===$('content').value));
 const t=totals(rows);
 $('metrics').innerHTML=[['기록',`${rows.length}개`],['전체 분석 세션',mins(t.elapsed)],['분석 커버리지',pct(t.coverage)],['변화가 큰 구간 / 유효',pct(t.highFraction)]].map(([k,v])=>`<div class="metric"><small>${k}</small><strong>${v}</strong></div>`).join('');
 const groups=new Map();for(const s of rows){const key=`${s.engine}|${s.content}`;if(!groups.has(key))groups.set(key,[]);groups.get(key).push(s);}
 $('groups').innerHTML=table(['엔진','콘텐츠','기록','유효 분석','커버리지','변화 구간 / 유효'],[...groups.values()].map(g=>{const a=totals(g);return[g[0].engine,labels[g[0].content],g.length,mins(a.valid),pct(a.coverage),pct(a.highFraction)];}));
 const missing={};for(const s of rows){for(const [k,v]of Object.entries(s.excludedMs))missing[k]=(missing[k]??0)+v;missing.unknown=(missing.unknown??0)+Math.max(0,s.elapsed-s.valid-Object.values(s.excludedMs).reduce((a,b)=>a+b,0));}
 $('quality').innerHTML=Object.entries(missing).filter(([,v])=>v>0).sort((a,b)=>b[1]-a[1]).map(([k,v])=>`<p>${esc(qualityLabels[k]??k)} · ${(v<1000?v+"ms":(v/1000).toFixed(2)+"초")}</p>`).join('')||'<p class="muted">집계할 누락 시간이 없습니다.</p>';
 const paired=rows.filter(s=>s.before!==null&&s.discomfort!==null),delta=paired.length?paired.reduce((n,s)=>n+s.discomfort-s.before,0)/paired.length:null;
 const processingCount=rows.reduce((n,s)=>n+s.processingCount,0),processing=rows.reduce((n,s)=>n+s.processingTotalMs,0);
 $('response').innerHTML=`<p>전후 응답 ${paired.length}개 / ${rows.length}개 기록</p><h3>평균 응답 차이 ${delta===null?'—':delta.toFixed(2)}</h3><p class="muted">표본 처리 평균 ${processingCount?(processing/processingCount).toFixed(2)+'ms':'—'}</p><p class="hint muted">처리 시간은 픽셀 읽기와 특징 계산만 포함합니다. 전체 배터리·GPU 비용은 포함하지 않습니다.</p>`;
 const timely=rows.filter(s=>{const f=s.feedback?.afterUse;const d=f?f.at-s.started-s.elapsed:-1;return f&&!f.skipped&&d>=0&&d<=120000&&(s.restStartedAt==null||f.at<=s.restStartedAt);});
 const timedPairs=timely.filter(s=>s.before!==null);
 const answered=rows.filter(s=>s.feedback?.afterUse&&!s.feedback.afterUse.skipped);
 $('response').innerHTML+='<p>종료 2분 이내 응답 '+timely.length+'건 · 전후 쌍 '+timedPairs.length+'건 · 지연/시각 불일치 '+(answered.length-timely.length)+'건</p><p>휴식 시작 이후 응답 '+rows.filter(s=>s.feedback?.afterRest&&!s.feedback.afterRest.skipped).length+'건 · 건너뛰기 '+rows.filter(s=>s.feedback?.afterUse?.skipped).length+'건</p><p class="hint">위 기존 전후 응답은 시점 미상입니다. 2분은 집계 구분값이며 피로 판단 기준이 아닙니다.</p>';
 $('quality').innerHTML+=rows.map(s=>{
 const windows=Array.from({length:Math.ceil(s.elapsed/10000)},(_,i)=>{const b=s.bins.find(b=>b[0]===i*10);const w=s.qualityWindows?.find(w=>w[0]===i*10);const full=Math.min(10000,s.elapsed-i*10000);return [i*10+'초',((b?.[1]??0)/1000).toFixed(2)+' / '+(full/1000).toFixed(2)+'초',(b?.[1]??0)===full?'전체 분석':b?'부분 분석':'표본 없음',Object.entries(w?.[1]??{}).map(([k,v])=>(qualityLabels[k]??k)+': '+v+'ms').join(', ')||(s.diagnosticsVersion?'제외 없음':'구간별 사유 미기록')];});
 return '<details><summary>'+esc(new Date(s.started).toLocaleString())+' · 구간별 품질</summary>'+table(['구간 시작','유효 / 전체','분석 범위','제외 사유'],windows)+(s.diagnosticsVersion?'<p>캡처 계측: '+esc(JSON.stringify(s.captureCounters))+'</p><p>표본 간격 분포: '+esc(JSON.stringify(s.intervalBuckets))+'</p>':'')+'</details>';
 }).join('');
 $('sessions').innerHTML=table(['시각','기기 / Android API','엔진','콘텐츠','전체 / 유효','변화 구간','불편감 전 → 사용 후 / 휴식 이후','최대 표본 간격'],rows.map(s=>[new Date(s.started).toLocaleString(),`${s.device} / ${s.sdk||'미기록'}`,s.engine,labels[s.content],`${mins(s.elapsed)} / ${mins(s.valid)}`,mins(s.high),`${s.before??'—'} → ${s.feedback?.afterUse?.score??s.discomfort??'—'} / ${s.feedback?.afterRest?.score??'—'}`,(s.engine==='exposure-0.2'||s.diagnosticsVersion)?`${s.maxIntervalMs}ms`:'미기록']));
 const issue=[];if(new Set(rows.map(s=>s.engine)).size>1)issue.push('여러 엔진 버전의 관측 결과가 합산되어 있습니다. 보정 비교는 같은 엔진으로 제한하세요.');if(rows.some(s=>s.valid<s.elapsed))issue.push('부분 분석 기록이 포함됩니다. 변화 비율은 관측된 구간에만 해당합니다.');
 if(data.conflicts.length)issue.push(`같은 수정 번호에서 내용이 다른 기록 ${data.conflicts.length}개를 제외했습니다. 원본 보고서를 확인하세요.`);
 for(const e of data.errors)issue.push(`${e.file}: ${e.message}`);
 $('issues').innerHTML=issue.length?`<div class="card notice">${issue.map(esc).join('<br>')}</div>`:'';
 $('updated').textContent=`중복 ${data.duplicates}건 제외 · 이전 수정본 ${data.superseded}건 제외 · ${new Date(data.generatedAt).toLocaleTimeString()} 갱신`;
}
async function refresh() {
 try {data=await(await api('/api/summary')).json();for(const id of ['engine','device','content'])options(id,[...new Set(data.sessions.map(s=>s[id]))].sort(),id==='content'?v=>labels[v]:v=>v);if(!initialEngineSelected){const engines=[...new Set(data.sessions.map(s=>s.engine))].sort();$('engine').value=engines.at(-1)??'';initialEngineSelected=true;}render();if(!$('status').dataset.upload)$('status').textContent=data.total.count?`${data.total.count}개 기록을 집계했습니다.`:'첫 보고서를 기다리고 있습니다.';}
 catch(e){$('status').textContent=e.message;}
}
$('upload').onclick=async()=>{
 const files=[...$('files').files];if(!files.length){$('status').textContent='보고서 파일을 선택해 주세요.';return;}
 $('upload').disabled=true;$('status').dataset.upload='1';let success=0;const errors=[];
 for(const file of files){try{if(file.size>10*1024*1024)throw new Error('최대 10MB');await api('/api/import',{method:'POST',headers:{'Content-Type':'application/json'},body:file});success++;}catch(e){errors.push(`${file.name}: ${e.message}`);}}
 $('status').textContent=`${success}개 파일 가져오기 완료${errors.length?' · '+errors.join(' / '):''}`;$('upload').disabled=false;await refresh();setTimeout(()=>delete $('status').dataset.upload,10000);
};
$('csv').onclick=async()=>{try{const r=await api('/api/sessions.csv');const url=URL.createObjectURL(await r.blob());const a=document.createElement('a');a.href=url;a.download='eyecare-sessions.csv';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}catch(e){$('status').textContent=e.message;}};
for(const id of ['engine','device','content'])$(id).onchange=render;
refresh();setInterval(refresh,5000);
