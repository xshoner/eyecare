import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { randomBytes, timingSafeEqual } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { collect, importReport, csv, MAX_BYTES } from './aggregate.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
export function createCollector({root, lan = false, port = 8765, token = randomBytes(24).toString('hex')} = {}) {
  let result = collect(root), signature = '';
  const hosts = new Set(['127.0.0.1','localhost','[::1]']);
  for (const entries of Object.values(os.networkInterfaces())) for (const entry of entries ?? []) if (entry.family === 'IPv4') hosts.add(entry.address);
  const authorized = req => {
    const supplied = Buffer.from(req.headers.authorization ?? '');
    const expected = Buffer.from(`Bearer ${token}`);
    return supplied.length === expected.length && timingSafeEqual(supplied, expected);
  };
  const server = http.createServer(async (req,res) => {
    res.setHeader('Cache-Control','no-store');
    res.setHeader('X-Content-Type-Options','nosniff');
    res.setHeader('Referrer-Policy','no-referrer');
    res.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
    const send = (status, body, type='application/json; charset=utf-8') => { res.writeHead(status,{'Content-Type':type}); res.end(typeof body === 'string' || Buffer.isBuffer(body) ? body : JSON.stringify(body)); };
    let host;
    try { host = new URL(`http://${req.headers.host}`).hostname; } catch { return send(400,{error:'잘못된 주소'}); }
    if (!hosts.has(host)) return send(403,{error:'허용되지 않은 호스트'});
    if (req.headers.origin && req.headers.origin !== `http://${req.headers.host}`) return send(403,{error:'다른 사이트의 요청은 허용하지 않습니다'});
    const url = new URL(req.url, `http://${req.headers.host}`);
    if (req.method === 'GET' && (url.pathname === '/' || url.pathname === '/app.js')) {
      return send(200,fs.readFileSync(path.join(here,url.pathname === '/' ? 'index.html' : 'app.js')),url.pathname === '/' ? 'text/html; charset=utf-8' : 'text/javascript; charset=utf-8');
    }
    if (!authorized(req)) return send(401,{error:'실행 창의 연결 주소로 다시 접속해 주세요.'});
    if (req.method === 'GET' && url.pathname === '/api/summary') return send(200,result);
    if (req.method === 'GET' && url.pathname === '/api/sessions.csv') {
      res.setHeader('Content-Disposition','attachment; filename="eyecare-sessions.csv"'); return send(200,csv(result.sessions),'text/csv; charset=utf-8');
    }
    if (req.method === 'POST' && url.pathname === '/api/import') {
      if (!(req.headers['content-type'] ?? '').startsWith('application/json')) return send(415,{error:'JSON 파일을 선택해 주세요.'});
      try {
        const chunks = []; let length = 0;
        for await (const chunk of req) { length += chunk.length; if (length > MAX_BYTES) { send(413,{error:'파일은 최대 10MB입니다.'}); return; } chunks.push(chunk); }
        result = importReport(root, Buffer.concat(chunks));
        return send(200,{count:result.total.count,duplicates:result.duplicates,conflicts:result.conflicts.length});
      } catch(e) { return send(400,{error:e.message}); }
    }
    return send(404,{error:'없는 경로입니다.'});
  });
  server.requestTimeout = 30000;
  const watcher = setInterval(() => {
    try {
      const inbox = path.join(root,'inbox');
      const current = fs.readdirSync(inbox).filter(n=>n.endsWith('.json')).sort().map(n=>{ const s=fs.statSync(path.join(inbox,n)); return `${n}:${s.size}:${s.mtimeMs}`; }).join('|');
      if (current !== signature) { result = collect(root); signature = current; }
    } catch(e) { console.error('집계 갱신 실패:',e.message); }
  },2000);
  watcher.unref(); server.on('close',()=>clearInterval(watcher));
  return {server,token,hosts,listen:()=>new Promise(resolve=>server.listen(port,lan?'0.0.0.0':'127.0.0.1',resolve))};
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const value = (key,fallback) => args.includes(key) ? args[args.indexOf(key)+1] : fallback;
  const root = path.resolve(value('--data',path.join(here,'..','reports')));
  if (args.includes('--once')) { const r=collect(root); console.log(`${r.total.count}개 기록 집계. 오류 ${r.errors.length}, 충돌 ${r.conflicts.length}. ${root}/output`); }
  else {
    const lan=args.includes('--lan'), port=Number(value('--port','8765'));
    const app=createCollector({root,lan,port});
    app.server.on('error',e=>{console.error(e.message);process.exit(1);});
    await app.listen();
    console.log(`EyeCare 집계 실행 중. 종료: Ctrl+C\n데이터: ${root}\n노트북: http://127.0.0.1:${port}/#${app.token}`);
    if(lan) { for(const host of app.hosts) if(!['127.0.0.1','localhost','[::1]'].includes(host)) console.log(`같은 Wi-Fi 휴대폰: http://${host}:${port}/#${app.token}`); console.log('로컬 HTTP 연결입니다. 신뢰하는 사설 네트워크에서 사용하세요.'); }
  }
}
