import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';

export const MAX_BYTES = 10 * 1024 * 1024;
const reasons = new Set(['user_stopped','break_requested','screen_off','limit','system_stopped','thermal','interrupted','capture_error','start_failed']);
const contents = new Set(['unspecified','shorts','video','game','reading','mixed']);
const exclusions = new Set(['waiting','missing_frames','dark_or_protected','hidden','invalid','resized','baseline_wait','continuity_reset','sample_gap','pending','end_tail']);
function counts(raw, name) {
  check(raw != null && typeof raw === 'object' && !Array.isArray(raw) && Object.keys(raw).length <= 64, `잘못된 계측: ${name}`);
  return Object.fromEntries(Object.entries(raw).sort(([a],[b])=>a.localeCompare(b)).map(([k,v])=>{check(/^[a-z][a-z0-9_]{0,39}$/.test(k),'잘못된 계측 키');return [k,number(v,name,0,Number.MAX_SAFE_INTEGER,true)];}));
}
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
function check(ok, message) { if (!ok) throw new Error(message); }
function number(value, name, min = 0, max = Number.MAX_SAFE_INTEGER, integer = false) {
  check(typeof value === 'number' && Number.isFinite(value) && value >= min && value <= max && (!integer || Number.isInteger(value)), `잘못된 수치: ${name}`);
  return value;
}
function text(value, name, max = 120) { check(typeof value === 'string' && value.length <= max && !/[\u0000-\u001f]/.test(value), `잘못된 문자열: ${name}`); return value; }
function score(value, name) { return value === null ? null : number(value, name, 0, 4, true); }
export function validateReport(raw) {
  check(raw && raw.format === 'neurolens-eyecare' && [1,2,3].includes(raw.schemaVersion), '지원하지 않는 보고서 형식 또는 버전');
  check(uuid.test(raw.reportId), '보고서 ID가 올바르지 않습니다');
  number(raw.exportedAt, 'exportedAt', 0, 8640000000000000, true);
  check(Array.isArray(raw.sessions) && raw.sessions.length > 0 && raw.sessions.length <= 100, '보고서는 1~100개 기록을 포함해야 합니다');
  const ids = new Set();
  const sessions = raw.sessions.map(s => {
    check(s && uuid.test(s.id) && !ids.has(s.id), '중복 또는 잘못된 세션 ID'); ids.add(s.id);
    const r = {
      id: s.id, started: number(s.started, 'started', 0, 8640000000000000, true),
      elapsed: number(s.elapsed, 'elapsed', 0, 86400000, true), valid: number(s.valid, 'valid', 0, 86400000, true),
      high: number(s.high, 'high', 0, 86400000, true), frames: number(s.frames, 'frames', 0, 1000000, true),
      mean: number(s.mean, 'mean', 0, 1), engine: text(s.engine, 'engine', 40),
      reason: text(s.reason, 'reason', 40), discomfort: score(s.discomfort, 'discomfort'),
      before: score(s.before ?? null, 'before'), content: s.content ?? 'unspecified',
      device: text(s.device ?? 'unknown', 'device'), sdk: number(s.sdk ?? 0, 'sdk', 0, 1000, true),
      appVersion: text(s.appVersion ?? '0.1.0-alpha', 'appVersion', 40), revision: number(s.revision ?? 0, 'revision', 0, 1000000, true),
      excludedMs: {}, meanIntervalMs: number(s.meanIntervalMs ?? 0, 'meanIntervalMs', 0, 86400000),
      maxIntervalMs: number(s.maxIntervalMs ?? 0, 'maxIntervalMs', 0, 86400000, true),
      meanGlobalChange: number(s.meanGlobalChange ?? 0, 'meanGlobalChange', 0, 1),
      meanChangedArea: number(s.meanChangedArea ?? 0, 'meanChangedArea', 0, 1),
      processingCount: number(s.processingCount ?? 0, 'processingCount', 0, 1000000, true),
      processingTotalMs: number(s.processingTotalMs ?? 0, 'processingTotalMs', 0, 86400000),
      processingMaxMs: number(s.processingMaxMs ?? 0, 'processingMaxMs', 0, 86400000),
      bins: [], diagnosticsVersion: s.diagnosticsVersion ?? 0, qualityWindows: [],
      counters: {}, intervalBuckets: {}, captureCounters: {}, feedback: {}, restStartedAt: s.restStartedAt ?? null
    };
    check(contents.has(r.content) && reasons.has(r.reason), '잘못된 콘텐츠 또는 종료 사유');
    check(r.high <= r.valid && r.valid <= r.elapsed, '시간 관계가 올바르지 않습니다');
    check(Array.isArray(s.bins) && s.bins.length <= 8640, '잘못된 구간 배열');
    let last = -10, binValid = 0, binHigh = 0;
    for (const b of s.bins) {
      check(Array.isArray(b) && b.length === 4, '잘못된 구간');
      const sec = number(b[0], 'bin time', 0, 86400, true);
      const valid = number(b[1], 'bin valid', 1, 10000, true);
      const change = number(b[2], 'bin change', 0, 1);
      const high = number(b[3], 'bin high', 0, valid, true);
      check(sec % 10 === 0 && sec > last && sec * 1000 < r.elapsed && valid <= Math.min(10000, r.elapsed - sec * 1000), '구간 순서 또는 길이가 올바르지 않습니다');
      r.bins.push([sec, valid, change, high]); last = sec; binValid += valid; binHigh += high;
    }
    check(binValid === r.valid && binHigh === r.high, '구간 합계가 요약과 일치하지 않습니다');
    check(s.excludedMs == null || (typeof s.excludedMs === 'object' && !Array.isArray(s.excludedMs)), '잘못된 누락 사유');
    for (const [key,value] of Object.entries(s.excludedMs ?? {}).sort(([a],[b])=>a.localeCompare(b))) {
      check(exclusions.has(key), '지원하지 않는 누락 사유'); r.excludedMs[key] = number(value, key, 0, r.elapsed, true);
    }
    check([0,1].includes(r.diagnosticsVersion), '지원하지 않는 진단 버전');
    if (r.engine === 'exposure-0.2.1') check(r.diagnosticsVersion === 1, '새 엔진의 진단 정보가 없습니다');
    if (r.engine === 'exposure-0.2' || r.diagnosticsVersion === 1) check(Object.values(r.excludedMs).reduce((a,b) => a+b, 0) + r.valid === r.elapsed, '유효·제외 시간 합계가 전체 시간과 다릅니다');
    if (r.diagnosticsVersion === 1) {
      r.counters=counts(s.counters,'counters');r.intervalBuckets=counts(s.intervalBuckets,'intervalBuckets');r.captureCounters=counts(s.captureCounters,'captureCounters');
      check(Array.isArray(s.qualityWindows) && s.qualityWindows.length<=8640,'잘못된 품질 구간');
      let lastWindow=-10;const reasonsTotal={};const byWindow=new Map();
      for(const w of s.qualityWindows){
        check(Array.isArray(w)&&w.length===2,'잘못된 품질 구간');const sec=number(w[0],'quality time',0,86400,true);
        check(sec%10===0&&sec>lastWindow&&sec*1000<r.elapsed,'품질 구간 순서 오류');lastWindow=sec;
        const ex=counts(w[1],'window exclusions');for(const [k,v]of Object.entries(ex)){check(exclusions.has(k),'지원하지 않는 제외 사유');reasonsTotal[k]=(reasonsTotal[k]??0)+v;}
        byWindow.set(sec,Object.values(ex).reduce((a,b)=>a+b,0));r.qualityWindows.push([sec,ex]);
      }
      for(const key of new Set([...Object.keys(reasonsTotal),...Object.keys(r.excludedMs)]))check((reasonsTotal[key]??0)===(r.excludedMs[key]??0),'구간별 제외 합계 오류');
      const validByWindow=new Map(r.bins.map(b=>[b[0],b[1]]));
      for(let sec=0;sec*1000<r.elapsed;sec+=10)check((validByWindow.get(sec)??0)+(byWindow.get(sec)??0)===Math.min(10000,r.elapsed-sec*1000),'구간별 전체 시간 오류');
    }
    if(r.restStartedAt!==null)number(r.restStartedAt,'restStartedAt',0,8640000000000000,true);
    check(s.feedback==null || (typeof s.feedback==='object'&&!Array.isArray(s.feedback)), '잘못된 응답');
    for(const [stage,f]of Object.entries(s.feedback??{}).sort(([a],[b])=>a.localeCompare(b))){
      check(['afterUse','afterRest'].includes(stage)&&f&&typeof f.skipped==='boolean','잘못된 응답 단계');
      check(stage!=='afterRest'||r.restStartedAt!==null,'휴식 시작 정보가 없습니다');
      const value=score(f.score,'feedback score');check(f.skipped===(value===null),'응답 건너뛰기 상태 오류');
      r.feedback[stage]={score:value,at:number(f.at,'feedback at',0,8640000000000000,true),skipped:f.skipped};
    }
    check(r.processingMaxMs <= r.processingTotalMs && (r.processingCount > 0 || r.processingTotalMs === 0), '잘못된 처리 시간');
    return r;
  });
  const careReports = [];
  const careEvents = [];
  if (raw.schemaVersion === 3) {
    check(Array.isArray(raw.careReports) && raw.careReports.length <= 1000, 'Invalid care reports');
    check(Array.isArray(raw.careEvents) && raw.careEvents.length <= 10000, 'Invalid care events');
    const reportIds = new Set();
    for (const item of raw.careReports) {
      check(item && uuid.test(item.id) && !reportIds.has(item.id) && ids.has(item.sessionId), 'Invalid care report identity');
      reportIds.add(item.id);
      const inputs = item.inputs;
      check(Array.isArray(inputs) && inputs.length <= 30 && inputs.every(v => typeof v === 'string' && /^[A-Z][0-9]{2}$/.test(v)), 'Invalid score inputs');
      careReports.push({
        id: item.id, sessionId: item.sessionId,
        createdAt: number(item.createdAt, 'report createdAt', 0, 8640000000000000, true),
        windowStartAt: number(item.windowStartAt, 'windowStartAt', 0, 8640000000000000, true),
        intervalMin: number(item.intervalMin, 'intervalMin', 10, 120, true),
        score: number(item.score, 'EFS', 0, 100, true),
        level: text(item.level, 'level', 30), confidence: text(item.confidence, 'confidence', 50),
        inputs, usageScore: number(item.usageScore, 'usageScore', 0, 100, true),
        screenScore: item.screenScore == null ? null : number(item.screenScore, 'screenScore', 0, 100, true),
        screenCoverage: number(item.screenCoverage, 'screenCoverage', 0, 1),
        eyeDetectionRatio: item.eyeDetectionRatio == null ? null : number(item.eyeDetectionRatio, 'eyeDetectionRatio', 0, 1),
        cameraProcessedFps: number(item.cameraProcessedFps ?? 0, 'cameraProcessedFps', 0, 120),
        eyeOpenMean: item.eyeOpenMean == null ? null : number(item.eyeOpenMean, 'eyeOpenMean', 0, 1),
        gazeState: text(item.gazeState ?? 'unavailable', 'gazeState', 40),
        nrs: item.nrs == null ? null : number(item.nrs, 'NRS', 0, 10, true),
        nrsAt: item.nrsAt == null ? null : number(item.nrsAt, 'nrsAt', 0, 8640000000000000, true),
        version: text(item.version, 'score version', 60)
      });
      check(item.windowStartAt <= item.createdAt && (item.nrs == null) === (item.nrsAt == null), 'Invalid report timeline or response');
    }
    const eventIds = new Set();
    for (const item of raw.careEvents) {
      check(item && uuid.test(item.id) && !eventIds.has(item.id) && ids.has(item.sessionId), 'Invalid care event identity');
      eventIds.add(item.id);
      careEvents.push({ id: item.id, sessionId: item.sessionId,
        at: number(item.at, 'event at', 0, 8640000000000000, true),
        type: text(item.type, 'event type', 40), detail: text(item.detail, 'event detail', 120) });
    }
  }
  return { format: raw.format, schemaVersion: raw.schemaVersion, reportId: raw.reportId, exportedAt: raw.exportedAt, sessions, careReports, careEvents };
}

export function summarize(rows) {
  let elapsed = 0, valid = 0, high = 0, weighted = 0, processingCount = 0, processingTotal = 0;
  const excludedMs = {}, deltas = [], timedDeltas = [];
  let delayedResponseCount=0, skippedResponseCount=0, restResponseCount=0;
  for (const s of rows) {
    elapsed += s.elapsed; valid += s.valid; high += s.high; weighted += s.mean * s.valid;
    processingCount += s.processingCount; processingTotal += s.processingTotalMs;
    if (s.before !== null && s.discomfort !== null) deltas.push(s.discomfort - s.before);
    const f=s.feedback?.afterUse;
    if(f?.skipped)skippedResponseCount++;
    if(f && !f.skipped){
      const delay=f.at-s.started-s.elapsed;
      if(delay>=0&&delay<=120000&&(s.restStartedAt==null||f.at<=s.restStartedAt)){if(s.before!==null)timedDeltas.push(f.score-s.before);}
      else delayedResponseCount++;
    }
    if(s.feedback?.afterRest && !s.feedback.afterRest.skipped)restResponseCount++;
    for (const [key,value] of Object.entries(s.excludedMs)) excludedMs[key] = (excludedMs[key] ?? 0) + value;
  }
  return { count: rows.length, elapsedMs: elapsed, validMs: valid, highMs: high,
    coverage: elapsed ? valid / elapsed : null, highFraction: valid ? high / valid : null,
    meanChange: valid ? weighted / valid : null, pairedCount: deltas.length,
    meanDiscomfortDelta: deltas.length ? deltas.reduce((a,b)=>a+b,0)/deltas.length : null,
    meanProcessingMs: processingCount ? processingTotal / processingCount : null,
    timedPairedCount:timedDeltas.length, meanTimedDelta:timedDeltas.length?timedDeltas.reduce((a,b)=>a+b,0)/timedDeltas.length:null,
    delayedResponseCount, skippedResponseCount, restResponseCount,
    excludedMs, unknownExcludedMs: rows.reduce((n,s) => n + Math.max(0,s.elapsed-s.valid-Object.values(s.excludedMs).reduce((a,b)=>a+b,0)), 0) };
}

export function aggregateReports(reports, errors = []) {
  const selected = new Map(), conflicts = new Set(); let duplicates = 0, superseded = 0;
  for (const report of reports) for (const s of report.sessions) {
    const prior = selected.get(s.id);
    if (!prior) selected.set(s.id, s);
    else if (s.revision > prior.revision) { selected.set(s.id, s); conflicts.delete(s.id); superseded++; }
    else if (s.revision < prior.revision) superseded++;
    else if (JSON.stringify(s) === JSON.stringify(prior)) duplicates++;
    else conflicts.add(s.id);
  }
  const sessions = [...selected.values()].filter(s => !conflicts.has(s.id)).sort((a,b)=>b.started-a.started || a.id.localeCompare(b.id));
  const careReportMap = new Map(), careEventMap = new Map();
  for (const report of reports) {
    for (const item of report.careReports ?? []) {
      const prior = careReportMap.get(item.id);
      if (!prior || (item.nrsAt ?? 0) > (prior.nrsAt ?? 0)) careReportMap.set(item.id, item);
    }
    for (const item of report.careEvents ?? []) careEventMap.set(item.id, item);
  }
  const careReports = [...careReportMap.values()].filter(r => selected.has(r.sessionId)).sort((a,b)=>b.createdAt-a.createdAt);
  const careEvents = [...careEventMap.values()].filter(e => selected.has(e.sessionId)).sort((a,b)=>a.at-b.at);
  function group(keys) {
    const groups = new Map();
    for (const s of sessions) { const key = JSON.stringify(keys.map(k=>s[k])); if (!groups.has(key)) groups.set(key, []); groups.get(key).push(s); }
    return [...groups.values()].map(rows=>({ ...Object.fromEntries(keys.map(k=>[k,rows[0][k]])), ...summarize(rows) }));
  }
  return { generatedAt: Date.now(), reportCount: reports.length, duplicates, superseded, conflicts: [...conflicts], errors,
    total: summarize(sessions), byEngine: group(['engine']), byDevice: group(['engine','device','sdk']), byContent: group(['engine','content']), sessions, careReports, careEvents };
}

function cell(v) {
  const value = v == null ? '' : String(v);
  const safe = typeof v === 'string' && /^\s*[=+\-@]/.test(value) ? `'${value}` : value;
  return `"${safe.replaceAll('"','""')}"`;
}
export function csv(rows) {
  const keys = ['id','started','device','sdk','appVersion','engine','content','elapsed','valid','high','mean','before','discomfort','revision','meanIntervalMs','maxIntervalMs','processingCount','processingTotalMs','processingMaxMs','afterUse','afterUseAt','afterRest','afterRestAt','afterUseSkipped','restStartedAt'];
  return '\ufeff' + [keys.map(cell).join(','), ...rows.map(r=>{const flat={...r,afterUse:r.feedback?.afterUse?.score,afterUseAt:r.feedback?.afterUse?.at,afterRest:r.feedback?.afterRest?.score,afterRestAt:r.feedback?.afterRest?.at,afterUseSkipped:r.feedback?.afterUse?.skipped};return keys.map(k=>cell(flat[k])).join(',');})].join('\r\n');
}
export function atomicWrite(file, data) {
  const temporary = `${file}.tmp`;
  fs.writeFileSync(temporary, data);
  fs.renameSync(temporary, file);
}
export function collect(root) {
  const inbox = path.join(root, 'inbox'), output = path.join(root, 'output');
  fs.mkdirSync(inbox, {recursive:true}); fs.mkdirSync(output, {recursive:true});
  const reports = [], errors = [];
  for (const name of fs.readdirSync(inbox).filter(n=>n.endsWith('.json')).sort()) {
    try {
      const file = path.join(inbox, name);
      check(fs.statSync(file).size <= MAX_BYTES, '파일이 10MB를 초과합니다');
      reports.push(validateReport(JSON.parse(fs.readFileSync(file,'utf8').replace(/^\ufeff/,''))));
    } catch (e) { errors.push({file:name,message:e.message}); }
  }
  const result = aggregateReports(reports, errors);
  atomicWrite(path.join(output,'summary.json'),JSON.stringify(result,null,2));
  atomicWrite(path.join(output,'sessions.csv'),csv(result.sessions));
  return result;
}
export function importReport(root, bytes) {
  check(bytes.length <= MAX_BYTES, '파일이 10MB를 초과합니다');
  validateReport(JSON.parse(bytes.toString('utf8').replace(/^\ufeff/,'')));
  const inbox = path.join(root,'inbox'); fs.mkdirSync(inbox,{recursive:true});
  const name = `report-${createHash('sha256').update(bytes).digest('hex')}.json`;
  const destination = path.join(inbox,name);
  if (!fs.existsSync(destination)) atomicWrite(destination, bytes);
  return collect(root);
}
