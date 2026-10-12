import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { validateReport, aggregateReports, importReport, collect, csv } from '../aggregate.mjs';
import { createCollector } from '../server.mjs';

const session=(extra={})=>({id:randomUUID(),started:1700000000000,elapsed:10000,valid:5000,high:1000,frames:11,mean:.12,engine:'exposure-0.2',reason:'user_stopped',discomfort:2,before:1,content:'video',device:'test phone',sdk:35,appVersion:'0.2.0-alpha',revision:1,bins:[[0,5000,.12,1000]],excludedMs:{missing_frames:5000},...extra});
const report=sessions=>({format:'neurolens-eyecare',schemaVersion:1,reportId:randomUUID(),exportedAt:Date.now(),sessions});
const valid=sessions=>validateReport(report(sessions));
const removeTestRoot=root=>{const resolved=fs.realpathSync(root);assert.equal(path.dirname(resolved),fs.realpathSync(os.tmpdir()));assert.ok(path.basename(resolved).startsWith('eyecare-'));fs.rmSync(resolved,{recursive:true,force:true});};
test('deduplicates repeated exports and replaces only higher revisions regardless of order',()=>{
 const s=session(),newer={...s,revision:2,discomfort:4};
 for(const order of [[valid([s]),valid([newer]),valid([s])],[valid([newer]),valid([s]),valid([newer])]]){
  const a=aggregateReports(order);assert.equal(a.total.count,1);assert.equal(a.sessions[0].discomfort,4);assert.equal(a.total.meanDiscomfortDelta,3);
 }
});
test('equal revision conflicts are excluded until a newer revision resolves them',()=>{
 const s=session(),a=valid([s]),b=valid([{...s,discomfort:3}]);
 assert.equal(aggregateReports([a,b]).total.count,0);
 assert.equal(aggregateReports([a,b]).conflicts.length,1);
 assert.equal(aggregateReports([a,b,valid([{...s,revision:2}])]).total.count,1);
});
test('JSON object key order does not create false conflicts',()=>{
 const s=session({excludedMs:{waiting:1000,hidden:4000}});
 const a=aggregateReports([valid([s]),valid([{...s,excludedMs:{hidden:4000,waiting:1000}}])]);
 assert.equal(a.total.count,1);assert.equal(a.conflicts.length,0);assert.equal(a.duplicates,1);
});
test('weighted totals, absent baseline and old engine remain explicit',()=>{
 const a=session(),b=session({elapsed:20000,valid:15000,high:0,bins:[[0,10000,.12,0],[10,5000,.12,0]],before:null});
 const s=aggregateReports([valid([a,b])]);assert.equal(s.total.coverage,2/3);assert.equal(s.total.highFraction,.05);assert.equal(s.total.pairedCount,1);
 const old=session({engine:'exposure-0.1',excludedMs:{},before:null});assert.equal(aggregateReports([valid([old])]).total.unknownExcludedMs,5000);
});
test('rejects malformed, impossible and unsupported data atomically',()=>{
 for(const bad of [{high:5001},{valid:11000},{mean:NaN},{before:5},{excludedMs:{hidden:1}},{bins:[[10,5000,.12,1000]]},{reason:'active'}])assert.throws(()=>valid([session(bad)]));
 assert.throws(()=>validateReport({...report([session()]),schemaVersion:99}));
});
test('schema 3 preserves periodic EFS reports, NRS labels and screen events',()=>{
 const s=session();const id=randomUUID();
 const r={id,sessionId:s.id,createdAt:s.started+9000,windowStartAt:s.started,intervalMin:30,
  score:62,level:'피로 누적',confidence:'낮음 · 화면·사용 기반',inputs:['U01','S06','S08'],
  usageScore:55,screenScore:71,screenCoverage:.5,eyeDetectionRatio:.72,cameraProcessedFps:12.5,
  eyeOpenMean:.18,gazeState:'stable_relative',nrs:7,nrsAt:s.started+9500,version:'efs-heuristic-0.3'};
 const e={id:randomUUID(),sessionId:s.id,at:s.started+5000,type:'screen_off',detail:''};
 const doc={...report([s]),schemaVersion:3,careReports:[r],careEvents:[e]};
 const imported=validateReport(doc);const result=aggregateReports([imported]);
 assert.equal(result.careReports[0].nrs,7);assert.equal(result.careReports[0].gazeState,'stable_relative');
 assert.equal(result.careEvents[0].type,'screen_off');
 assert.throws(()=>validateReport({...doc,careReports:[{...r,nrs:11}]}));
 assert.throws(()=>validateReport({...doc,careReports:[{...r,eyeDetectionRatio:1.5}]}));
});
test('inbox import is idempotent and malformed files are reported without losing valid records',()=>{
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'eyecare-'));
 try {const bytes=Buffer.from(JSON.stringify(report([session()])));importReport(root,bytes);importReport(root,bytes);assert.equal(collect(root).total.count,1);assert.equal(fs.readdirSync(path.join(root,'inbox')).length,1);fs.writeFileSync(path.join(root,'inbox','bad.json'),'{');assert.equal(collect(root).errors.length,1);assert.ok(fs.readFileSync(path.join(root,'output','sessions.csv'),'utf8').includes('test phone'));}
 finally {removeTestRoot(root);}
});
test('CSV neutralizes formulas and quotes device text',()=>{
 const output=csv([session({device:'=HYPERLINK("x")'})]);assert.ok(output.includes("'=HYPERLINK"));assert.ok(output.includes('""x""'));
 assert.ok(csv([session({device:'  =HYPERLINK("x")'})]).includes("'  =HYPERLINK"));
});
test('HTTP receiver requires key, rejects cross-site writes and imports valid data',async()=>{
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'eyecare-http-'));
 const app=createCollector({root,port:0});await app.listen();const base=`http://127.0.0.1:${app.server.address().port}`;
 const headers={Authorization:`Bearer ${app.token}`,'Content-Type':'application/json'};
 try {
  assert.equal((await fetch(base+'/api/summary')).status,401);
  assert.equal((await fetch(base+'/api/import',{method:'POST',headers:{...headers,Origin:'http://malicious.example'},body:'{}'})).status,403);
  assert.equal((await fetch(base+'/api/import',{method:'POST',headers,body:JSON.stringify(report([session()]))})).status,200);
  const summary=await(await fetch(base+'/api/summary',{headers})).json();assert.equal(summary.total.count,1);
  assert.equal((await fetch(base+'/api/import',{method:'POST',headers,body:'{}'})).status,400);
 }finally{app.server.closeAllConnections();await new Promise(resolve=>app.server.close(resolve));removeTestRoot(root);}
});
test('running collector automatically notices files added to inbox',async()=>{
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'eyecare-watch-'));
 const app=createCollector({root,port:0});await app.listen();
 try {
  fs.writeFileSync(path.join(root,'inbox','from-phone.json'),JSON.stringify(report([session()])));
  const base=`http://127.0.0.1:${app.server.address().port}`,headers={Authorization:`Bearer ${app.token}`};
  let count=0;const until=Date.now()+6000;
  while(!count&&Date.now()<until){await new Promise(r=>setTimeout(r,250));const result=await(await fetch(base+'/api/summary',{headers})).json();count=result.total.count;}
  assert.equal(count,1);assert.equal(JSON.parse(fs.readFileSync(path.join(root,'output','summary.json'))).total.count,1);
 }finally{app.server.closeAllConnections();await new Promise(resolve=>app.server.close(resolve));removeTestRoot(root);}
});

const diagnosticSession=(extra={})=>session({engine:'exposure-0.2.1',diagnosticsVersion:1,
 excludedMs:{sample_gap:4500,end_tail:500},qualityWindows:[[0,{sample_gap:4500,end_tail:500}]],counters:{long_gaps:1},intervalBuckets:{gt1500:1},captureCounters:{callbacks:20,acquired:20,selected:11},discomfort:null,...extra});
test('new diagnostics preserve per-window conservation and reject corrupted windows',()=>{
 const s=diagnosticSession();const r=validateReport({...report([s]),schemaVersion:2});assert.equal(r.sessions[0].qualityWindows[0][1].sample_gap,4500);
 assert.throws(()=>valid([{...s,qualityWindows:[[0,{sample_gap:4500,end_tail:499}]]}]));
 assert.throws(()=>valid([{...s,diagnosticsVersion:0}]));
 const a=aggregateReports([valid([s]),valid([{...s,qualityWindows:[[0,{end_tail:500,sample_gap:4500}]]}])]);assert.equal(a.duplicates,1);assert.equal(a.total.unknownExcludedMs,0);
});
test('timed, delayed, skipped and legacy responses remain separate and updates deduplicate',()=>{
 const s=diagnosticSession();const at=s.started+s.elapsed+10000;
 const timed={...s,feedback:{afterUse:{score:2,at,skipped:false}}};
 const delayed=diagnosticSession({feedback:{afterUse:{score:3,at:s.started+s.elapsed+130000,skipped:false}}});
 const skipped=diagnosticSession({feedback:{afterUse:{score:null,at,skipped:true}}});
 const a=aggregateReports([valid([timed,delayed,skipped,session()])]);
 assert.equal(a.total.timedPairedCount,1);assert.equal(a.total.meanTimedDelta,1);assert.equal(a.total.delayedResponseCount,1);assert.equal(a.total.skippedResponseCount,1);assert.equal(a.total.pairedCount,1);
 const revised={...timed,revision:2,feedback:{afterUse:{score:4,at,skipped:false}}};
 assert.equal(aggregateReports([valid([revised]),valid([timed])]).total.meanTimedDelta,3);
 assert.throws(()=>valid([{...s,feedback:{afterUse:{score:2,at,skipped:true}}}]));
});
test('maximum 30-minute diagnostics fit 100-record upload budget',()=>{
 const sessions=Array.from({length:100},()=>diagnosticSession({elapsed:1800000,valid:900000,high:180000,bins:Array.from({length:180},(_,i)=>[i*10,5000,.12,1000]),excludedMs:{sample_gap:180000,dark_or_protected:180000,hidden:180000,resized:180000,continuity_reset:180000},qualityWindows:Array.from({length:180},(_,i)=>[i*10,{sample_gap:1000,dark_or_protected:1000,hidden:1000,resized:1000,continuity_reset:1000}])}));
 const doc={...report(sessions),schemaVersion:2};assert.ok(Buffer.byteLength(JSON.stringify(doc,null,2))<10*1024*1024);assert.equal(validateReport(doc).sessions.length,100);
});
