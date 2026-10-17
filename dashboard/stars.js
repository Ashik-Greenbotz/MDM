// Dark theme background: a slowly drifting, twinkling star field with the odd shooting star. Draws only while
// <html data-theme="dark">; light theme is untouched. Shared by the portal and the login page.
(() => {
  const cv = document.body.appendChild(document.createElement("canvas")), cx = cv.getContext("2d");
  cv.setAttribute("aria-hidden", "true");
  cv.style.cssText = "position:fixed;inset:0;width:100%;height:100%;z-index:-1;pointer-events:none";
  const still = matchMedia("(prefers-reduced-motion: reduce)").matches;
  let W, H, stars = [], shots = [], nextShot = 0, running = false, last = 0;
  const dark = () => document.documentElement.dataset.theme === "dark";

  function build() {
    const dpr = Math.min(devicePixelRatio || 1, 2);
    W = innerWidth; H = innerHeight; cv.width = W * dpr; cv.height = H * dpr; cx.setTransform(dpr, 0, 0, dpr, 0, 0);
    // ponytail: count scales with screen area (~170 on a laptop), fine for canvas 2D
    stars = Array.from({ length: Math.round(W * H / 8000) }, () => {
      const z = Math.random(); // depth: far stars are small, dim and slow
      return { x: Math.random() * W, y: Math.random() * H, r: .3 + z * 1.2, a: .35 + z * .6, v: 2 + z * 8, ph: Math.random() * 6.28, tw: .6 + Math.random() * 2 };
    });
  }
  function shoot(t) {
    const ang = Math.PI * (.15 + Math.random() * .15), sp = 700 + Math.random() * 500; // down and to the left
    shots.push({ x: W * (.3 + Math.random() * .8), y: -20 + Math.random() * H * .35, vx: -Math.cos(ang) * sp, vy: Math.sin(ang) * sp, life: 0, max: .7 + Math.random() * .5 });
    nextShot = t + 2.5 + Math.random() * 5;
  }
  function frame(ms) {
    if (!dark()) { running = false; cx.clearRect(0, 0, W, H); return; }
    const t = ms / 1000, dt = Math.min(.05, t - (last || t)); last = t;
    cx.clearRect(0, 0, W, H);
    for (const s of stars) {
      if (!still) { s.x -= s.v * dt; if (s.x < -2) { s.x = W + 2; s.y = Math.random() * H; } }
      cx.globalAlpha = s.a * (still ? 1 : .55 + .45 * Math.sin(t * s.tw + s.ph));
      cx.fillStyle = "#fff";
      cx.beginPath(); cx.arc(s.x, s.y, s.r, 0, 6.283); cx.fill();
    }
    if (!still) {
      if (t > nextShot) shoot(t);
      shots = shots.filter(s => (s.life += dt) < s.max);
      for (const s of shots) {
        s.x += s.vx * dt; s.y += s.vy * dt;
        const fade = Math.sin(Math.PI * s.life / s.max), tx = s.x - s.vx * .12, ty = s.y - s.vy * .12;
        const g = cx.createLinearGradient(s.x, s.y, tx, ty);
        g.addColorStop(0, `rgba(255,255,255,${fade})`); g.addColorStop(.3, `rgba(255,214,150,${fade * .5})`); g.addColorStop(1, "rgba(255,255,255,0)");
        cx.globalAlpha = 1; cx.strokeStyle = g; cx.lineWidth = 1.8; cx.lineCap = "round";
        cx.beginPath(); cx.moveTo(s.x, s.y); cx.lineTo(tx, ty); cx.stroke();
      }
    }
    if (still) { running = false; return; }
    requestAnimationFrame(frame);
  }
  const start = () => { if (dark() && !running) { running = true; last = 0; nextShot = performance.now() / 1000 + 1.5; requestAnimationFrame(frame); } };
  new MutationObserver(start).observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
  addEventListener("resize", () => { build(); if (still) start(); });
  build(); start();
})();
