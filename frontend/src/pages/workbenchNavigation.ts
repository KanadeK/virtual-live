type Workspace = "capture" | "debug" | "corpus" | "cloud" | "history";
const labels: Record<Workspace, [string, string]> = {
  capture: ["抖音实时采集", "连接直播间，查看实时互动与播报进度。"],
  debug: ["调试台", "模拟互动、安排表演和台词，管理直播间音乐。"],
  corpus: ["语料库配置", "欢迎、空闲与礼物感谢直接转语音，保存后立即生效。"],
  cloud: ["云端链路", "管理 DeepSeek 与豆包配置，查看今日调用情况。"],
  history: ["互动记录", "查看直播间最近的互动与系统处理记录。"],
};
const storageKey = "dafeiyu-workbench-page";
const valid = (value: string | null): value is Workspace => !!value && value in labels;

export function bindWorkbenchNavigation(onChange?: (page: Workspace) => void) {
  const buttons = Array.from(document.querySelectorAll<HTMLButtonElement>("[data-workspace]"));
  const panels = document.querySelectorAll<HTMLElement>("[data-workspace-panel]");
  const menu = document.querySelector<HTMLElement>('.rail [role="tablist"]')!;
  const narrow = matchMedia("(max-width: 730px)");
  const orient = () => menu.setAttribute("aria-orientation", narrow.matches ? "horizontal" : "vertical");
  orient(); narrow.addEventListener("change", orient);
  const scroll = document.getElementById("workbench-content")!;
  const positions = new Map<string, number>();
  let active: Workspace | undefined;
  const activate = (page: Workspace, persist = true) => {
    if (active === page) return;
    if (active) positions.set(active, scroll.scrollTop);
    active = page;
    buttons.forEach(button => {
      const selected = button.dataset.workspace === page;
      button.classList.toggle("active", selected);
      button.setAttribute("aria-selected", String(selected));
      button.tabIndex = selected ? 0 : -1;
    });
    panels.forEach(panel => { panel.hidden = panel.dataset.workspacePanel !== page; });
    document.getElementById("workspace-title")!.textContent = labels[page][0];
    document.getElementById("workspace-description")!.textContent = labels[page][1];
    document.title = `${labels[page][0]} · Virtual Live`;
    scroll.scrollTop = positions.get(page) ?? 0;
    if (persist) {
      try { localStorage.setItem(storageKey, page); } catch { /* Navigation still works without storage. */ }
      history.replaceState(null, "", `#${page}`);
    }
    onChange?.(page);
  };
  buttons.forEach((button, index) => {
    button.onclick = () => activate(button.dataset.workspace as Workspace);
    button.onkeydown = event => {
      let next = index;
      if (["ArrowDown", "ArrowRight"].includes(event.key)) next = (index + 1) % buttons.length;
      else if (["ArrowUp", "ArrowLeft"].includes(event.key)) next = (index + buttons.length - 1) % buttons.length;
      else if (event.key === "Home") next = 0;
      else if (event.key === "End") next = buttons.length - 1;
      else return;
      event.preventDefault();
      buttons[next].focus();
      buttons[next].click();
      buttons[next].scrollIntoView({ block: "nearest", inline: "nearest" });
    };
  });
  let saved: string | null = null;
  try { saved = localStorage.getItem(storageKey); } catch { /* Optional preference. */ }
  const hash = location.hash.slice(1);
  activate(valid(hash) ? hash : hash === "music" ? "debug" : valid(saved) ? saved : "capture");
  window.addEventListener("hashchange", () => {
    const page = location.hash.slice(1);
    if (valid(page)) activate(page);
  });
}
