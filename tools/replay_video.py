#!/usr/bin/env python3
"""
Reproduce el detector sobre un VIDEO REAL de una partida (grabación de pantalla) y escribe un CSV de detecciones
por fotograma que el motor Kotlin reproduce en `RealVideoReplayTest`.
Espejo en Python del algoritmo de OpenCvObstacleDetector (modelo Lab de la carretera por franjas) MÁS la franja de
horizonte con fondo temporal (los coches aparecen sobre la línea del horizonte, por encima de la carretera).
Uso: python3 -I tools/replay_video.py video.mp4 salida.csv [--no-horizon]
"""
import sys, csv
import numpy as np, cv2

W, H = 558, 1240
# Calibración tal como la marcó el usuario (coordenadas normalizadas de la captura completa)
NL, NR, FL, FR = (0.010, 0.931), (1.0, 0.931), (0.262, 0.629), (0.714, 0.632)
BOX = (0.351, 0.743, 0.654, 0.843)

def line(p, q):
    b = (q[0] - p[0]) / (q[1] - p[1]); return p[0] - b * p[1], b
aL, bL = line(NL, FL); aR, bR = line(NR, FR)
lx = lambda y: aL + bL * y
rx = lambda y: aR + bR * y
lw = lambda y: (rx(y) - lx(y)) / 3
FAR_Y = (FL[1] + FR[1]) / 2
NEAR_Y = NL[1]
N_BANDS = 16

def to_lab(bgr): return cv2.cvtColor(bgr, cv2.COLOR_BGR2LAB)

def learn_model(frame_bgr, exclude):
    lab = to_lab(cv2.resize(frame_bgr, (270, int(frame_bgr.shape[0] * 270 / frame_bgr.shape[1])), interpolation=cv2.INTER_AREA))
    h, w = lab.shape[:2]
    hist = [[np.zeros(256, int) for _ in range(3)] for _ in range(N_BANDS)]
    cnt = np.zeros(N_BANDS, int)
    for py in range(h):
        yn = (py + .5) / h
        if yn < FAR_Y or yn > NEAR_Y: continue
        b = min(N_BANDS - 1, int((yn - FAR_Y) / (NEAR_Y - FAR_Y) * N_BANDS))
        xm, xM = lx(yn) + .1 * lw(yn), rx(yn) - .1 * lw(yn)
        xs = np.arange(w); xn = (xs + .5) / w
        ok = (xn >= xm) & (xn <= xM)
        for (l, t, r, bt) in exclude:
            ok &= ~((xn >= l) & (xn <= r) & (yn >= t) & (yn <= bt))
        for c in range(3):
            np.add.at(hist[b][c], lab[py, ok, c], 1)
        cnt[b] += int(ok.sum())
    def med(hh, tot):
        return int(np.searchsorted(np.cumsum(hh), (tot + 1) // 2))
    st = []
    for b in range(N_BANDS):
        if cnt[b] < 50:
            st.append(st[-1] if st else (110, 128, 128, 24, 14)); continue
        m = [med(hist[b][c], cnt[b]) for c in range(3)]
        mad = []
        for c in range(3):
            dev = np.zeros(256, int)
            for v in range(256): dev[abs(v - m[c])] += hist[b][c][v]
            mad.append(med(dev, cnt[b]) * 1.4826)
        st.append((m[0], m[1], m[2], max(24, 4 * mad[0]), max(14, 4 * np.hypot(mad[1], mad[2]))))
    return st

def expected(st, y):
    cs = FAR_Y + (np.arange(N_BANDS) + .5) / N_BANDS * (NEAR_Y - FAR_Y)
    return np.array([np.interp(y, cs, [s[k] for s in st]) for k in range(5)])

class Detector:
    def __init__(self, model, horizon=True, above=0.05, work_w=224):
        self.model, self.horizon, self.above, self.ww = model, horizon, above, work_w
        self.top = (FAR_Y - above) if horizon else FAR_Y + .002
        self.bot = BOX[1] - .004
        self.x0 = max(0, lx(self.bot) - .02); self.x1 = min(1, rx(self.bot) + .02)
        self.bg = None; self.n = 0
    def detect(self, bgr):
        H_, W_ = bgr.shape[:2]
        fx0, fx1 = int(self.x0 * W_), int(self.x1 * W_); fy0, fy1 = int(self.top * H_), int(self.bot * H_)
        sub = bgr[fy0:fy1, fx0:fx1]
        wh = max(16, int(round(sub.shape[0] * self.ww / sub.shape[1])))
        lab = to_lab(cv2.resize(sub, (self.ww, wh), interpolation=cv2.INTER_AREA)).astype(np.float32)
        ph = wh / (self.bot - self.top)
        yn = self.top + (np.arange(wh) + .5) / ph
        mask = np.zeros((wh, self.ww), np.uint8)
        for r in range(wh):
            if yn[r] < FAR_Y + .002:
                continue
            e = expected(self.model, yn[r])
            dl = np.abs(lab[r, :, 0] - e[0]); dc = np.hypot(lab[r, :, 1] - e[1], lab[r, :, 2] - e[2])
            mask[r] = ((dl > e[3]) | (dc > e[4])) * 255
        nb = int(round((FAR_Y + .002 - self.top) * ph))
        if self.horizon and nb > 0:
            band = lab[:nb]
            if self.bg is None: self.bg = band.copy()
            d = np.abs(band - self.bg); dd = np.maximum(d[..., 0] * 0.8, np.hypot(d[..., 1], d[..., 2]) * 1.6)
            fg = dd > 22
            mask[:nb] = fg * 255
            # el fondo se actualiza lento y solo donde no hay primer plano (un coche que crece no se absorbe)
            keep = (~cv2.dilate(fg.astype(np.uint8), np.ones((5, 5), np.uint8)).astype(bool))[..., None]
            self.bg = np.where(keep, self.bg * .96 + band * .04, self.bg)
        dets = []
        for lane in range(3):
            valid = np.zeros_like(mask)
            pts = []
            for y in (self.top, FAR_Y, self.bot):
                yy = max(y, FAR_Y)           # sobre el horizonte los carriles son verticales
                w_ = lw(yy); xl = lx(yy) + w_ * lane + .12 * w_
                pts.append(((xl - self.x0) / (self.x1 - self.x0) * self.ww, (y - self.top) * ph))
            for y in (self.bot, FAR_Y, self.top):
                yy = max(y, FAR_Y); w_ = lw(yy); xr = lx(yy) + w_ * (lane + 1) - .12 * w_
                pts.append(((xr - self.x0) / (self.x1 - self.x0) * self.ww, (y - self.top) * ph))
            cv2.fillConvexPoly(valid, np.array(pts, np.int32), 255)
            m = cv2.bitwise_and(mask, valid)
            m = cv2.morphologyEx(m, cv2.MORPH_OPEN, np.ones((2, 2), np.uint8))
            m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, np.ones((7, 5), np.uint8))
            n, lab_, st, _ = cv2.connectedComponentsWithStats(m, 8)
            for i in range(1, n):
                x, y, w, h, a = st[i]
                xl_ = self.x0 + x / self.ww * (self.x1 - self.x0); xr_ = self.x0 + (x + w) / self.ww * (self.x1 - self.x0)
                yt = self.top + y / ph; yb = self.top + (y + h) / ph
                lwb = lw(max(yb, FAR_Y))
                far = yt < FAR_Y and yb < FAR_Y + .035          # zona del horizonte: el coche apenas asoma, umbrales relajados
                mw, mh = (.22, .07) if far else (.30, .15)
                if (xr_ - xl_) < mw * lwb or (yb - yt) < mh * lwb: continue
                if a / (w * h) < .35: continue
                dets.append((lane, xl_, yt, xr_, yb, .5 + .5 * a / (w * h)))
        return dets

if __name__ == '__main__':
    vid, out = sys.argv[1], sys.argv[2]
    horizon = '--no-horizon' not in sys.argv
    cap = cv2.VideoCapture(vid)
    fps = cap.get(5)
    frames = []
    while True:
        ok, f = cap.read()
        if not ok: break
        frames.append(cv2.resize(f, (W, H), interpolation=cv2.INTER_AREA))
    ref = frames[int(2.6 * fps)]               # carretera vacía (coche del jugador en el centro), antes del primer obstáculo
    zone = (BOX[0] - .045, BOX[1] - .01, BOX[2] + .045, min(.995, BOX[3] + .4 * (BOX[3] - BOX[1])))
    model = learn_model(ref, [zone])
    det = Detector(model, horizon)
    step = int(round(fps / 30))               # análisis a ~30/s
    rows = []
    for i in range(int(2.4 * fps), len(frames), step):   # antes: pantalla de inicio del juego
        t = i / fps
        for d in det.detect(frames[i]):
            rows.append((round(t, 4),) + tuple(round(float(v), 5) for v in d))
    with open(out, 'w', newline='') as fh:
        wr = csv.writer(fh); wr.writerow(['t', 'lane', 'x0', 'y0', 'x1', 'y1', 'conf']); wr.writerows(rows)
    first = [r for r in rows if r[0] > 3.0]
    print('horizonte' if horizon else 'sin horizonte', 'primeras detecciones tras t=3s:', [(r[0], r[1], round(r[4], 3)) for r in first[:6]])
    pre = [r for r in rows if r[0] <= 4.2]
    print('detecciones espurias antes de 4.2 s:', len(pre), [(r[0], r[1]) for r in pre[:10]])
