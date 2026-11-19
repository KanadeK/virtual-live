import { ProxyAgent, setGlobalDispatcher } from 'undici';
import { HttpsProxyAgent } from 'https-proxy-agent';

const proxy = process.env.DOUYIN_PROXY_URL?.trim();
if (proxy) setGlobalDispatcher(new ProxyAgent(proxy));
export const websocketAgent = proxy ? new HttpsProxyAgent(proxy) : undefined;
