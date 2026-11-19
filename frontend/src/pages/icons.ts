const paths: Record<string, string> = {
  book: '<path d="M12 5v16M3 3c4-1 6 0 9 2 3-2 5-3 9-2v16c-4-1-6 0-9 2-3-2-5-3-9-2Z"/>',
  cloud: '<path d="M7 18a5 5 0 0 1-1-10 7 7 0 0 1 13-1 5.5 5.5 0 0 1 0 11H7Z"/><path d="M9 12h6M12 9v6"/>',
  history: '<path d="M3 4v6h6M3 10a9 9 0 1 1 1 8"/><path d="M12 7v6l4 2"/>',
  radio: '<circle cx="12" cy="12" r="2"/><path d="M7 7a7 7 0 0 0 0 10M17 7a7 7 0 0 1 0 10M4 4a11 11 0 0 0 0 16M20 4a11 11 0 0 1 0 16"/>',
  repeat: '<path d="m17 2 4 4-4 4M3 11V9a3 3 0 0 1 3-3h15M7 22l-4-4 4-4M21 13v2a3 3 0 0 1-3 3H3"/>',
  music: '<path d="M9 18V5l12-2v13M9 8l12-2"/><ellipse cx="6" cy="18" rx="3" ry="3"/><ellipse cx="18" cy="16" rx="3" ry="3"/>',
  plus: '<path d="M12 4v16M4 12h16"/>',
  next: '<path d="m5 4 12 8-12 8ZM20 4v16"/>',
  fish: '<path d="M3 12c4-7 10-7 15 0-5 7-11 7-15 0Z"/><path d="m18 12 4-5v10Z"/><circle cx="8" cy="11" r="1"/>',
  grid: '<rect x="3" y="3" width="7" height="7" rx="2"/><rect x="14" y="3" width="7" height="7" rx="2"/><rect x="3" y="14" width="7" height="7" rx="2"/><rect x="14" y="14" width="7" height="7" rx="2"/>',
  chat: '<path d="M21 11a8 8 0 0 1-8 8H7l-5 3 2-6a8 8 0 0 1-1-5 9 9 0 0 1 18 0Z"/><path d="M7 10h10M7 14h6"/>',
  heart:
    '<path d="M20.8 4.7c-2.4-2.4-6.2-1.9-8.8 1C9.4 2.8 5.6 2.3 3.2 4.7c-4 4.1 1 9.7 8.8 15.3 7.8-5.6 12.8-11.2 8.8-15.3Z"/>',
  gift: '<rect x="3" y="8" width="18" height="4" rx="1"/><path d="M5 12v9h14v-9M12 8v13M12 8C4 9 4 1 8 3c3 0 4 5 4 5Zm0 0c8 1 8-7 4-5-3 0-4 5-4 5Z"/>',
  user: '<circle cx="10" cy="7" r="4"/><path d="M2 21v-3a8 8 0 0 1 16 0v3M20 7v6M17 10h6"/>',
  volume:
    '<path d="m3 9 5 0 5-5v16l-5-5H3ZM17 8a6 6 0 0 1 0 8M20 5a10 10 0 0 1 0 14"/>',
  spark: '<path d="m12 2 3 7 7 3-7 3-3 7-3-7-7-3 7-3ZM20 1v4M18 3h4"/>',
  play: '<path d="m8 4 13 8-13 8Z"/>',
  pause: '<path d="M7 4v16M17 4v16"/>',
  link: '<path d="M14 3h7v7M21 3l-9 9M10 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-5"/>',
  sliders:
    '<path d="M4 3v18M12 3v18M20 3v18"/><path d="M1 8h6M9 16h6M17 7h6"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v6l4 2"/>',
  smile:
    '<circle cx="12" cy="12" r="9"/><path d="M7 14q5 7 10 0M8 8h.01M16 8h.01"/>',
  clear: '<path d="M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7M14 10v7"/>',
  check: '<path d="m5 12 4 4L20 5"/>',
  flower:
    '<path d="M12 9C4-5 0 12 8 12c-13 4 0 15 4 4 4 11 17 0 4-4 8 0 4-17-4-3Z"/><circle cx="12" cy="12" r="3"/>',
  star: '<path d="m12 2 3 6 7 1-5 5 1 8-6-4-6 4 1-8-5-5 7-1Z"/>',
  expand:
    '<path d="M8 3H5a2 2 0 0 0-2 2v3M21 8V5a2 2 0 0 0-2-2h-3M3 16v3a2 2 0 0 0 2 2h3M16 21h3a2 2 0 0 0 2-2v-3"/>',
  shrink:
    '<path d="M8 3v3a2 2 0 0 1-2 2H3M21 8h-3a2 2 0 0 1-2-2V3M3 16h3a2 2 0 0 1 2 2v3M16 21v-3a2 2 0 0 1 2-2h3"/>',
};
export function icon(name: string, cls = "") {
  return `<svg class="icon ${cls}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[name] || paths.spark}</svg>`;
}
