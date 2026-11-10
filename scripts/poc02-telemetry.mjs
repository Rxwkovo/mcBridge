// Read-only-token telemetry harness. Tool argument schemas are supplied after discovery, not guessed.
import { Client } from '../mcp-server/node_modules/@modelcontextprotocol/sdk/dist/esm/client/index.js';
import { StdioClientTransport } from '../mcp-server/node_modules/@modelcontextprotocol/sdk/dist/esm/client/stdio.js';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve, join } from 'node:path';

const allowed = new Set(['create_region_monitor','list_region_monitors','get_region_monitor','update_region_monitor','stop_region_monitor','query_region_history','query_factory_metrics','get_block','inspect_block_entity','inspect_create_kinetic','inspect_create_network','get_status']);
const [action, phase, planFile] = process.argv.slice(2);
if (!['discover','capture','capture-control','capture-monitor'].includes(action) || (action !== 'discover' && (!phase || !planFile))) {
  throw Error('Usage: node scripts/poc02-telemetry.mjs discover OR capture|capture-control <phase> <plan.json> OR capture-monitor <phase> <monitor-id>');
}
if (phase && !/^[a-z0-9_-]{1,40}$/.test(phase)) throw Error('Invalid phase name');
const root = resolve('integration-test/production-server');
const config = JSON.parse(readFileSync(join(root,'config/mcpfabric.config.json'),'utf8').replace(/^\uFEFF/,''));
if (config.host !== '127.0.0.1' || config.requireAuth !== true || !config.readToken || config.enableWorldWrite || config.enableCommands || config.enablePlayerControl || config.enableSupervisedControl || config.enableUiControl) {
  throw Error('Refusing unsafe server config or missing read-only token');
}
const outDir=resolve('integration-test/evidence'); mkdirSync(outDir,{recursive:true});
const client = new Client({name:'factory-telemetry-acceptance',version:'1'});
if (action === 'capture-control' && !config.token) throw Error('Missing full token for authorized monitor management');
const transport = new StdioClientTransport({command:process.execPath,args:[resolve('mcp-server/dist/index.js')],env:{...process.env,MCPFABRIC_URL:`http://127.0.0.1:${config.port}`,MCPFABRIC_TOKEN:action === 'capture-control' ? config.token : config.readToken,MCPFABRIC_TOOLSETS:'world',MCPFABRIC_AGENT:'0',MCPFABRIC_SCRIPTS:'0'},stderr:'ignore'});
const output = {action,phase:phase??null,recordedAt:new Date().toISOString(),atomicSnapshot:false,calls:[]};
try {
  await client.connect(transport);
  if (action === 'discover') {
    const all = await client.listTools();
    output.tools = all.tools.filter(t=>allowed.has(t.name)).map(({name,inputSchema,annotations})=>({name,inputSchema,annotations}));
    writeFileSync(join(outDir,'telemetry-tool-schemas.json'),JSON.stringify(output,null,2));
    console.log(JSON.stringify({discovered:output.tools.map(t=>t.name)}));
  } else {
    const plan=action==='capture-monitor' ? {calls:[{name:'get_region_monitor',arguments:{monitorId:planFile}},{name:'query_region_history',arguments:{monitorId:planFile,sinceSequence:0,limit:100}},{name:'query_factory_metrics',arguments:{monitorId:planFile}}]} : JSON.parse(readFileSync(resolve(planFile),'utf8').replace(/^\uFEFF/,''));
    if (!Array.isArray(plan.calls) || plan.calls.length<1 || plan.calls.length>32) throw Error('Plan must contain 1..32 calls');
    for (const entry of plan.calls) {
      if (!entry || !allowed.has(entry.name) || !entry.arguments || typeof entry.arguments !== 'object' || Array.isArray(entry.arguments)) throw Error('Invalid or disallowed tool call');
      const start=performance.now();
      const response=await client.callTool({name:entry.name,arguments:entry.arguments});
      output.calls.push({name:entry.name,arguments:entry.arguments,elapsedMs:performance.now()-start,isError:!!response.isError,result:response.structuredContent??response.content});
      writeFileSync(join(outDir,`telemetry-${phase}.json`),JSON.stringify(output,null,2));
    }
    const errors=output.calls.filter(c=>c.isError).length;
    console.log(JSON.stringify({phase,calls:output.calls.length,errors,file:join(outDir,`telemetry-${phase}.json`)}));
    if(errors) process.exitCode=1;
  }
} finally { await client.close(); }
