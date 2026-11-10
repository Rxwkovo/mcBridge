import {Client} from '../mcp-server/node_modules/@modelcontextprotocol/sdk/dist/esm/client/index.js';
import {StdioClientTransport} from '../mcp-server/node_modules/@modelcontextprotocol/sdk/dist/esm/client/stdio.js';
import {readFileSync,writeFileSync} from 'node:fs';
import {resolve} from 'node:path';
const cfg=JSON.parse(readFileSync('integration-test/production-server/config/mcpfabric.config.json','utf8').replace(/^\uFEFF/,''));
const c=new Client({name:'poc02-acceptance',version:'1'});let output=[];
await c.connect(new StdioClientTransport({command:process.execPath,args:[resolve('mcp-server/dist/index.js')],env:{...process.env,MCPFABRIC_URL:'http://127.0.0.1:25603',MCPFABRIC_TOKEN:cfg.token,MCPFABRIC_TOOLSETS:'world',MCPFABRIC_AGENT:'0',MCPFABRIC_SCRIPTS:'0'},stderr:'ignore'}));
async function call(name,args){let r=await c.callTool({name,arguments:args});let x={name,isError:!!r.isError,result:r.structuredContent??r.content}; output.push(x);return x;}
const sleep=()=>new Promise(r=>setTimeout(r,300));
try { const coord={x:9,y:128,z:8};const original=await call('get_block',coord);if(original.result.id!=='create:shaft')throw Error('unexpected initial block');
const denied=await call('preview_batch_edit',{areaId:'test',dimension:'minecraft:overworld',operations:[{x:10,y:128,z:8,blockId:'minecraft:air'}]});if(!denied.isError)throw Error('outside allowed');
const p=await call('preview_batch_edit',{areaId:'test',dimension:'minecraft:overworld',operations:[{...coord,blockId:'minecraft:air'}]});if(p.isError)throw Error('preview failed');
const args={previewId:p.result.previewId,idempotencyKey:'poc02-commit-001'};const a=await call('commit_batch_edit',args);if(a.isError)throw Error('commit failed');const again=await call('commit_batch_edit',args);if(again.result.operationId!==a.result.operationId)throw Error('idempotency failed');
for(let i=0;i<20;i++){await sleep();let q=await call('get_edit_operation',{operationId:a.result.operationId});if(q.result.status==='completed')break;}
let removed=await call('get_block',coord);if(removed.result.id!=='minecraft:air')throw Error('edit not observed');let u=await call('undo_edit_operation',{operationId:a.result.operationId,idempotencyKey:'poc02-undo-001'});if(u.isError)throw Error('undo rejected');
for(let i=0;i<20;i++){await sleep();let q=await call('get_edit_operation',{operationId:a.result.operationId});if(q.result.status==='undone')break;}
let restored=await call('get_block',coord);if(restored.result.id!=='create:shaft')throw Error('undo not observed');
let j=await call('start_create_network_capture',{x:8,y:128,z:8,target:4096});if(j.isError||!j.result.jobId)throw Error('network job not started');for(let i=0;i<20;i++){await sleep();let s=await call('get_create_network_capture',{jobId:j.result.jobId});if(['finished','partial','completed'].includes(s.result.state??s.result.status)){await call('page_create_network_capture',{jobId:j.result.jobId,offset:0,limit:10});break;}}
} finally {writeFileSync('integration-test/evidence/poc02-live.json',JSON.stringify(output,null,2));await c.close();}
console.log('Live test completed');
