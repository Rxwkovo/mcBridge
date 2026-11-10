// Read-only verification of disk history pagination around a known isolated fixture boundary.
import {Client} from '../mcp-server/node_modules/@modelcontextprotocol/sdk/dist/esm/client/index.js';
import {StdioClientTransport} from '../mcp-server/node_modules/@modelcontextprotocol/sdk/dist/esm/client/stdio.js';
import {readFileSync,writeFileSync} from 'node:fs';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';
const [monitorId,boundaryText] = process.argv.slice(2); const boundary = Number(boundaryText);
if (!/^[0-9a-f-]{36}$/.test(monitorId??'') || !Number.isSafeInteger(boundary) || boundary < 2) throw Error('Usage: node scripts/poc03-history.mjs <monitorId> <lastSequenceInFirstSegment>');
const cfg=JSON.parse(readFileSync('integration-test/production-server/config/mcpfabric.config.json','utf8').replace(/^\uFEFF/,''));
if(cfg.host!=='127.0.0.1'||!cfg.requireAuth||!cfg.readToken)throw Error('Unsafe test configuration');
const c=new Client({name:'poc03-history',version:'1'});
await c.connect(new StdioClientTransport({command:process.execPath,args:[resolve('mcp-server/dist/index.js')],env:{...process.env,MCPFABRIC_URL:`http://127.0.0.1:${cfg.port}`,MCPFABRIC_TOKEN:cfg.readToken,MCPFABRIC_TOOLSETS:'world'},stderr:'ignore'}));
async function page(sinceSequence){for(let i=0;i<40;i++){let r=await c.callTool({name:'query_region_history',arguments:{monitorId,disk:true,sinceSequence,limit:2}});if(r.isError)throw Error(JSON.stringify(r.content));let o=r.structuredContent??r.content;if(o.status==='pending'){await new Promise(resolve=>setTimeout(resolve,150));continue}assert.equal(o.status,'ok');return o}throw Error('disk scan did not finish')}
try{const first=await page(boundary-2);assert.deepEqual(first.entries.map(x=>x.sequence),[boundary-1,boundary]);assert.equal(first.hasMore,true);const second=await page(first.nextSequence);assert.deepEqual(second.entries.map(x=>x.sequence),[boundary+1,boundary+2]);writeFileSync('integration-test/evidence/poc03-cross-segment-history.json',JSON.stringify({first,second},null,2));console.log(JSON.stringify({status:'passed',boundary,first:first.entries.map(x=>x.sequence),second:second.entries.map(x=>x.sequence)}))}finally{await c.close()}
