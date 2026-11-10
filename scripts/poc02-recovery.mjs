// Disposable-world, single-shaft recovery experiment; never retries commits or replays on restart.
import {Client} from '../mcp-server/node_modules/@modelcontextprotocol/sdk/dist/esm/client/index.js';
import {StdioClientTransport} from '../mcp-server/node_modules/@modelcontextprotocol/sdk/dist/esm/client/stdio.js';
import {readFileSync,writeFileSync,mkdirSync,existsSync} from 'node:fs';
import {resolve,join} from 'node:path';
import {randomUUID} from 'node:crypto';
const [action,runId] = process.argv.slice(2);
if (!['prepare','observe'].includes(action) || !/^[a-zA-Z0-9_-]{1,60}$/.test(runId??'')) throw Error('Usage: node scripts/poc02-recovery.mjs prepare|observe <unique-run-id>');
const root=resolve('integration-test/production-server');
const cfg=JSON.parse(readFileSync(join(root,'config/mcpfabric.config.json'),'utf8').replace(/^\uFEFF/,''));
if(cfg.host!=='127.0.0.1'||cfg.requireAuth!==true||cfg.enableWorldWrite||cfg.enableCommands||cfg.enablePlayerControl||cfg.enableSupervisedControl||cfg.enableUiControl) throw Error('Unsafe bridge configuration');
if(action==='prepare' && (cfg.accessMode!=='REGION_BUILD' || cfg.allowAdmin!==false)) throw Error('Recovery preparation requires isolated REGION_BUILD without admin authority');
const path=resolve(`integration-test/evidence/recovery-${runId}.json`);mkdirSync(resolve('integration-test/evidence'),{recursive:true});
if(action==='prepare'&&existsSync(path)) throw Error('Run id already exists; refusing to replay');
if(action==='observe'&&!existsSync(path)) throw Error('Prior run record missing; nothing will be replayed');
let record=action==='prepare'?{runId,createdAt:new Date().toISOString(),phase:'preparing',coordinate:{x:9,y:128,z:8},autoReplay:false}:JSON.parse(readFileSync(path,'utf8'));
const persist=()=>writeFileSync(path,JSON.stringify(record,null,2));
const client=new Client({name:'mechagent-recovery-acceptance',version:'1'});
const token=action==='prepare'?cfg.token:cfg.readToken;if(!token)throw Error('Required token missing');
await client.connect(new StdioClientTransport({command:process.execPath,args:[resolve('mcp-server/dist/index.js')],env:{...process.env,MCPFABRIC_URL:`http://127.0.0.1:${cfg.port}`,MCPFABRIC_TOKEN:token,MCPFABRIC_TOOLSETS:'world',MCPFABRIC_AGENT:'0',MCPFABRIC_SCRIPTS:'0'},stderr:'ignore'}));
async function call(name,args){const r=await client.callTool({name,arguments:args});const out={name,isError:!!r.isError,result:r.structuredContent??r.content};if(out.isError)throw Error(`${name} failed: ${JSON.stringify(out.result)}`);return out.result}
try {
 if(action==='prepare') {
  record.before=await call('get_block',record.coordinate); if(record.before.id!=='create:shaft')throw Error('Expected a shaft at test coordinate');
  const preview=await call('preview_batch_edit',{areaId:'test',dimension:'minecraft:overworld',operations:[{...record.coordinate,blockId:'minecraft:air',expected:{blockId:'create:shaft',properties:record.before.properties}}]});
  if(!preview.previewId)throw Error('Preview id missing');
  record.idempotencyKey=`recovery-${randomUUID()}`;record.previewId=preview.previewId;record.phase='commit_submitting';persist();
  const commit=await call('commit_batch_edit',{previewId:record.previewId,idempotencyKey:record.idempotencyKey});
  record.operationId=commit.operationId;if(!record.operationId)throw Error('Commit operation id missing');record.commit=commit;record.phase='submitted';persist();
  record.beforeRestart=await call('get_edit_operation',{operationId:record.operationId});record.phase='ready_for_restart';persist();
  console.log(JSON.stringify({runId,operationId:record.operationId,status:record.beforeRestart.status,file:path}));
 } else {
  if(!record.operationId)throw Error('Operation id unavailable; do not retry or replay the uncertain commit');
  const observation={at:new Date().toISOString(),operation:await call('get_edit_operation',{operationId:record.operationId}),block:await call('get_block',record.coordinate)};
  (record.afterRestart??=[]).push(observation);record.phase='observed_after_restart';persist();
  console.log(JSON.stringify({runId,status:observation.operation.status,journalStatus:observation.operation.journalStatus??null,itemCount:observation.operation.items?.length??0,block:observation.block.id,file:path}));
 }
} catch(e) {record.error=String(e.message??e);persist();throw e} finally {await client.close()}
