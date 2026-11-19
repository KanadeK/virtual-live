export async function api<T = Record<string, unknown>>(
  path: string,
  body?: unknown,
  method: "GET" | "POST" | "PUT" | "DELETE" = body === undefined ? "GET" : "POST",
): Promise<T> {
  const response = await fetch(`/api${path}`, {
    method,
    headers: body === undefined ? {} : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(8000),
  });
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}));
    throw new Error(problem.error || `请求失败 (${response.status})`);
  }
  return response.json() as Promise<T>;
}
export function toast(message: string, bad = false) {
  let el = document.querySelector<HTMLElement>("#toast");
  if (!el) {
    el = document.createElement("div");
    el.id = "toast";
    el.setAttribute("role", "status");
    document.body.append(el);
  }
  el.textContent = message;
  el.className = bad ? "toast visible bad" : "toast visible";
  clearTimeout(Number(el.dataset.timer));
  el.dataset.timer = String(
    window.setTimeout(() => el!.classList.remove("visible"), 3500),
  );
}
export async function command(path: string, body: unknown, success?: string) {
  try {
    const result = await api(path, body);
    if (success) toast(success);
    return result;
  } catch (e) {
    toast(e instanceof Error ? e.message : "连接失败", true);
    return null;
  }
}
