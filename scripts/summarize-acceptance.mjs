import {readFileSync,writeFileSync,mkdirSync} from 'node:fs';
import assert from 'node:assert/strict';
const samples=JSON.parse(readFileSync('integration-test/evidence/mcp-samples.json','utf8').replace(/^\uFEFF/,''));
const fixture=JSON.parse(readFileSync('integration-test/production-server/mechagent-acceptance-evidence.json','utf8').replace(/^\uFEFF/,''));
const network=samples.filter(s=>s.name==='inspect_create_network'&&s.result.status==='ok');
const overloaded=network.find(s=>s.result.stressSU>s.result.capacitySU);
assert(overloaded); assert.equal(overloaded.result.anchor.overstressed,true); assert.equal(overloaded.result.anchor.rpm,0); assert.notEqual(overloaded.result.anchor.theoreticalRpm,0);
assert(fixture.samples.find(s=>s.stage==='add_one_consumer').press.rpm!==0);
assert.equal(fixture.samples.find(s=>s.stage==='disconnected').press.rpm,0);
const pages=samples.filter(s=>s.name==='inspect_create_network'&&s.args.snapshotId);
for(const page of pages){const first=network.find(s=>s.result.snapshotId===page.args.snapshotId&&!s.args.snapshotId); assert(first); assert.equal(first.result.sampleGameTime,page.result.sampleGameTime); const a=new Set(first.result.members.map(m=>`${m.x},${m.y},${m.z}`)); for(const m of page.result.members)assert(!a.has(`${m.x},${m.y},${m.z}`));}
const metrics={}; for(const name of new Set(samples.map(s=>s.name))){const group=samples.filter(s=>s.name===name);const t=group.map(s=>s.elapsedMs).sort((a,b)=>a-b); metrics[name]={count:group.length,requestMsP50:t[Math.floor(t.length/2)],requestMsP95:t[Math.min(t.length-1,Math.floor(t.length*.95))],maxResponseBytes:Math.max(...group.map(s=>s.bytes)),maxObservationMicros:Math.max(...group.map(s=>s.result.observationMicros??0))};}
const out={environment:{minecraft:'1.21.1',neoforge:'21.1.248',create:'6.0.10',java:'Temurin 21.0.12.1',pack:'minimal production Create dependencies; not complete Mechanomania'},fixture,paginationVerifiedPages:pages.length,observedOverload:{tick:overloaded.result.sampleGameTime,capacitySU:overloaded.result.capacitySU,stressSU:overloaded.result.stressSU,utilization:overloaded.result.utilization,members:overloaded.result.loadedMemberCacheCount},capabilities:samples.filter(s=>s.name==='inspect_block_entity'),metrics,limitations:['No graphical gauge comparison','Energy and filled inventories/tanks not tested','No TPS/MSPT measurement or large network benchmark','Generic persistence unavailable; serializer intentionally not invoked','No unchanged complete Mechanomania integration']};
mkdirSync('reports',{recursive:true});writeFileSync('reports/poc01-stage3.json',JSON.stringify(out,null,2));console.log(JSON.stringify({stages:fixture.samples.length,pages:pages.length,metrics}));
