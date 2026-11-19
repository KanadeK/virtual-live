// Test-only login fixture; no Chrome or platform request is made.
const input=require('node:readline').createInterface({input:process.stdin});
input.once('line',line=>{const config=JSON.parse(line),platform=config.platform||'douyin';
  const send=value=>process.stdout.write(JSON.stringify({platform,...value})+'\n');
  send({kind:'status',state:'waiting',message:'离线登录夹具等待'});
  if(config.roomId==='950')return;
  setTimeout(()=>{send({kind:'credentials',cookie:platform==='bilibili'?'SESSDATA=private-fixture-only; DedeUserID=42; buvid3=fixture':'sessionid_ss=private-fixture-only; ttwid=fixture'});},40);
});
input.on('close',()=>process.exit(0));
