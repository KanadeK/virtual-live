// A fake login lifecycle. No browser, credentials or remote requests are used.
const {createInterface}=require('node:readline');
const fs=require('node:fs'),path=require('node:path');
const input=createInterface({input:process.stdin});
input.once('line',line=>{
  const config=JSON.parse(line),send=value=>process.stdout.write(JSON.stringify({platform:config.platform||'douyin',...value})+'\n');
  if(process.env.BILI_FIXTURE_STATE_DIR)fs.writeFileSync(path.join(process.env.BILI_FIXTURE_STATE_DIR,`login-${process.pid}.json`),JSON.stringify({kind:'login',pid:process.pid,roomId:config.roomId}));
  send({kind:'status',state:'waiting',message:'本地离线登录夹具等待中'});
  if(String(config.roomId)!=='1501')setTimeout(()=>{
    send({kind:'credentials',cookie:'SESSDATA=fixture-bili-session; DedeUserID=9007199254740993127; bili_jct=fixture; sessionid_ss=fixture-douyin-session'});
    process.exit(0);
  },250);
});
input.on('close',()=>process.exit(0));
