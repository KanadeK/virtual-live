import { icon } from "../pages/icons";
import type { Gift } from "../events/protocol";
export class StageEffects {
  private reduced = matchMedia('(prefers-reduced-motion: reduce)');
  constructor(
    private layer: HTMLElement,
    private giftLayer: HTMLElement,
  ) {}
  gift(gift: Gift) {
    this.giftLayer.hidden = false;
    this.giftLayer.setAttribute('aria-hidden', 'false');
    this.giftLayer.replaceChildren();
    const card = document.createElement("div");
    card.className = "gift-toast";
    const kind = gift.giftName.includes("心")
      ? "heart"
      : gift.giftName.includes("鱼")
        ? "fish"
        : gift.giftName.includes("花")
          ? "flower"
          : "star";
    card.innerHTML = `<span class="gift-art">${icon(kind)}</span><div><small></small><b></b></div><strong></strong>`;
    card.querySelector("small")!.textContent = `谢谢 ${gift.nickname} 的礼物`;
    card.querySelector("b")!.textContent = gift.giftName;
    card.querySelector("strong")!.textContent = `× ${gift.giftCount}`;
    this.giftLayer.append(card);
    card.animate(
      this.reduced.matches ? [{ opacity: 0 }, { opacity: 1 }] : [
        { opacity: 0, transform: "translateY(30px) scale(.85)" },
        { opacity: 1, transform: "translateY(0) scale(1.04)" },
        { opacity: 1, transform: "scale(1)" },
      ],
      { duration: this.reduced.matches ? 120 : 500, easing: "cubic-bezier(.2,.8,.2,1)" },
    );
    window.setTimeout(() => {
      card
        .animate(
          this.reduced.matches ? [{ opacity: 1 }, { opacity: 0 }] : [{ opacity: 1 }, { opacity: 0, transform: "translateY(-30px)" }],
          { duration: this.reduced.matches ? 120 : 500 },
        )
        .finished.then(() => {
          card.remove();
          if (!this.giftLayer.childElementCount) {
            this.giftLayer.hidden = true;
            this.giftLayer.setAttribute('aria-hidden', 'true');
          }
        });
    }, 4200);
    for (let i = 0; i < 44; i++)
      this.particle(
        i % 3 === 0 ? "heart" : i % 3 === 1 ? "star" : "spark",
        true,
      );
  }
  likes(count: number) {
    for (let i = 0; i < Math.min(8, count + 2); i++)
      this.particle("heart", false);
  }
  private particle(kind: string, burst: boolean) {
    if (this.reduced.matches) return;
    if (this.layer.childElementCount > 90) return;
    const p = document.createElement("span");
    p.className = `particle ${kind}`;
    p.innerHTML = icon(kind);
    const x = burst ? 180 + Math.random() * 720 : 850 + Math.random() * 100,
      y = burst ? 900 + Math.random() * 250 : 1470 + Math.random() * 100;
    p.style.left = `${x}px`;
    p.style.top = `${y}px`;
    p.style.color = ["#dd9b85", "#dcb769", "#81b7a2", "#bea3b3"][
      Math.floor(Math.random() * 4)
    ];
    this.layer.append(p);
    p.animate(
      [
        { opacity: 0, transform: "translate(0,0) scale(.4) rotate(0deg)" },
        { opacity: 1, offset: 0.15 },
        {
          opacity: 0,
          transform: `translate(${(Math.random() - 0.5) * (burst ? 600 : 160)}px,${-250 - Math.random() * 650}px) scale(${0.6 + Math.random()}) rotate(${Math.random() * 180 - 90}deg)`,
        },
      ],
      {
        duration: 1800 + Math.random() * 1600,
        easing: "cubic-bezier(.2,.6,.4,1)",
        delay: Math.random() * 500,
      },
    ).finished.then(() => p.remove());
  }
}
