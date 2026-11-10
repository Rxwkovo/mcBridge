import {Client} from '../mcp-server/node_modules/@modelcontextprotocol/sdk/dist/esm/client/index.js';
import {StdioClientTransport} from '../mcp-server/node_modules/@modelcontextprotocol/sdk/dist/esm/client/stdio.js';
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
const cfg=JSON.parse(readFileSync('integration-test/production-server/config/mcpfabric.config.json','utf8').replace(/^\uFEFF/,''));
if(cfg.host!=='127.0.0.1'||!cfg.requireAuth||!cfg.token)throw Error('unsafe configuration');
const client=new Client({name:'isolated-meter-acceptance',version:'1'});
await client.connect(new StdioClientTransport({command:process.execPath,args:[resolve('mcp-server/dist/index.js')],env:{...process.env,MCPFABRIC_URL:`http://127.0.0.1:${cfg.port}`,MCPFABRIC_TOKEN:process.env.METER_READ_ONLY==='1'?cfg.readToken:cfg.token,MCPFABRIC_TOOLSETS:'world'},stderr:'ignore'}));
try {
 const mode=process.argv[2]; const name=mode==='create'?'create_factory_meter':mode==='stop'?'stop_factory_meter':'query_factory_meter';
 const args=mode==='create'?{areaId:'agent-build',dimension:'minecraft:overworld',hopper:{x:6,y:125,z:8},itemId:'create:iron_sheet'}:{meterId:process.argv[3]};
 const result=await client.callTool({name,arguments:args}); if(result.isError)throw Error(JSON.stringify(result.content));
 console.log(JSON.stringify(result.structuredContent??result.content));
} finally {await client.close()}
