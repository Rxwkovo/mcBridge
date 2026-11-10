import { Client } from '../mcp-server/node_modules/@modelcontextprotocol/sdk/dist/esm/client/index.js';
import { StdioClientTransport } from '../mcp-server/node_modules/@modelcontextprotocol/sdk/dist/esm/client/stdio.js';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
const gameDir=resolve(process.argv[2]??'integration-test/production-server');
const cfg=JSON.parse(readFileSync(resolve(gameDir,'config/mcpfabric.config.json'),'utf8').replace(/^\uFEFF/,''));
if(cfg.host!=='127.0.0.1'||!cfg.requireAuth||cfg.enableWorldWrite||cfg.enableCommands||cfg.enablePlayerControl) throw Error('Unsafe test config');
const evidence=resolve('integration-test/evidence'); mkdirSync(evidence,{recursive:true});
const client=new Client({name:'mechagent-acceptance',version:'1'});
const transport=new StdioClientTransport({command:process.execPath,args:[resolve('mcp-server/dist/index.js')],env:{...process.env,MCPFABRIC_URL:`http://127.0.0.1:${cfg.port}`,MCPFABRIC_TOKEN:cfg.readToken,MCPFABRIC_TOOLSETS:'readonly',MCPFABRIC_AGENT:'0',MCPFABRIC_SCRIPTS:'0'},stderr:'ignore'});
const samples=[];
async function call(name,args={}) { const started=performance.now(); const result=await client.callTool({name,arguments:args}); const value={name,args,elapsedMs:performance.now()-started,bytes:Buffer.byteLength(JSON.stringify(result)),isError:!!result.isError,result:result.structuredContent??result.content}; samples.push(value); return value; }
try {
 await client.connect(transport);
 await call('get_status'); await call('list_capabilities'); await call('list_registered_blocks',{modId:'create',limit:5});
 await call('get_block',{x:8,y:128,z:8});
 const first=await call('get_blocks_region',{from:{x:8,y:128,z:8},to:{x:18,y:128,z:12},maxBlocks:2,includeAir:true});
 if(first.result?.nextOffset) await call('get_blocks_region',{from:{x:8,y:128,z:8},to:{x:18,y:128,z:12},maxBlocks:2,includeAir:true,offset:first.result.nextOffset});
 for(const x of [8,10,12,14,16]) await call('inspect_block_entity',{x,y:128,z:12,includePersistent:true});
 await call('get_block',{x:1000000,y:128,z:1000000});
 for(let i=0;i<100;i++) {
  await call('inspect_create_kinetic',{x:10,y:128,z:8});
  const network=await call('inspect_create_network',{x:8,y:128,z:8,limit:2});
  if(network.result?.snapshotId && network.result?.hasMore) await call('inspect_create_network',{x:8,y:128,z:8,limit:2,offset:network.result.nextOffset,snapshotId:network.result.snapshotId});
  writeFileSync(resolve(evidence,'mcp-samples.json'),JSON.stringify(samples,null,2));
  await new Promise(r=>setTimeout(r,2000));
 }
} finally { writeFileSync(resolve(evidence,'mcp-samples.json'),JSON.stringify(samples,null,2)); await client.close(); }
console.log(JSON.stringify({samples:samples.length,errors:samples.filter(s=>s.isError).length,file:resolve(evidence,'mcp-samples.json')}));
