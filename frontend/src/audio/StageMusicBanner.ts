import type { Song } from './MusicPlayer';

/** The stage is scaled as a unit; measure and animate in its logical pixels. */
export class StageMusicBanner {
  private viewport = document.querySelector<HTMLElement>('.stage-music-marquee');
  private text = document.querySelector<HTMLElement>('#stage-music-title');
  private motion?: Animation;
  private motionKey = '';
  private song?: Song;
  private paused = false;
  private reduced = matchMedia('(prefers-reduced-motion: reduce)');
  private resize = new ResizeObserver(() => this.animate());
  private preference = () => this.animate();

  constructor() {
    if (this.viewport) this.resize.observe(this.viewport);
    this.reduced.addEventListener('change', this.preference);
  }
  render(song: Song | undefined, paused: boolean) {
    this.song = song; this.paused = paused;
    if (!this.text || !this.viewport) return;
    const label = song ? `${song.title}--${song.artist}` : '暂无播放音乐';
    if (this.text.textContent !== label) this.text.textContent = label;
    this.viewport.title = label;
    this.viewport.setAttribute('aria-label', song ? `当前音乐：${label}${paused ? '（已暂停）' : ''}` : label);
    this.animate();
  }
  private animate() {
    if (!this.text || !this.viewport) return;
    const travel = this.viewport.clientWidth - this.text.offsetWidth;
    const key = `${this.song?.songId || ''}:${this.text.textContent}:${travel}:${this.reduced.matches}`;
    if (key !== this.motionKey) {
      this.motionKey = key; this.motion?.cancel(); this.motion = undefined;
      // Short titles move right and back; overflowing titles move left to reveal their end.
      if (this.song && !this.reduced.matches && Math.abs(travel) > 1) {
        this.motion = this.text.animate([
          { transform: 'translateX(0)', offset: 0 },
          { transform: 'translateX(0)', offset: .12 },
          { transform: `translateX(${travel}px)`, offset: .88 },
          { transform: `translateX(${travel}px)`, offset: 1 },
        ], { duration: Math.max(8000, Math.abs(travel) / 35 * 1000), direction: 'alternate', iterations: Infinity, easing: 'linear' });
      }
      this.viewport.classList.toggle('static-music-title', this.reduced.matches);
    }
    if (this.paused) this.motion?.pause(); else this.motion?.play();
  }
  destroy() { this.motion?.cancel(); this.resize.disconnect(); this.reduced.removeEventListener('change', this.preference); }
}
