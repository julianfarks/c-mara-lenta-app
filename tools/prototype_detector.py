#!/usr/bin/env python3
"""
Prototipo de referencia del detector de obstáculos (mismo algoritmo que el módulo Kotlin
`OpenCvObstacleDetector`) validado con escenas sintéticas. Sirve para comprobar la idea
y para tener números reales de precisión/recuerdo SOBRE IMÁGENES SINTÉTICAS (no sobre un
juego real).

Uso:  python3 -I tools/prototype_detector.py
"""
import sys
import numpy as np
import cv2

W, H = 540, 1200  # tamaño de pantalla sintético (hasta 1080x2400 da lo mismo en normalizado)

# Geometría de la carretera en coordenadas normalizadas (igual que TestGeo del núcleo).
NL, NR, FL, FR = (0.05, 0.95), (0.95, 0.95), (0.38, 0.30), (0.62, 0.30)
PLAYER = (0.40, 0.74, 0.60, 0.92)  # left, top, right, bottom


def line(p, q):
    b = (q[0] - p[0]) / (q[1] - p[1])
    return p[0] - b * p[1], b


aL, bL = line(NL, FL)
aR, bR = line(NR, FR)
left_x = lambda y: aL + bL * y
right_x = lambda y: aR + bR * y
lane_w = lambda y: (right_x(y) - left_x(y)) / 3
lane_cx = lambda lane, y: left_x(y) + lane_w(y) * (lane + 0.5)
HORIZON = (aR - aL) / (bL - bR)


def road_polygon_px(shrink=0.0):
    ys = [FL[1], NL[1]]
    pts = []
    for y in ys:
        w = lane_w(y)
        pts.append((left_x(y) + shrink * w, y))
    for y in reversed(ys):
        w = lane_w(y)
        pts.append((right_x(y) - shrink * w, y))
    return np.array([(int(x * W), int(y * H)) for x, y in pts], np.int32)


def render_scene(cars, player_lane=1, seed=0, texture=6.0, with_player=True):
    """cars: lista de (carril, profundidad_u, color_bgr, ancho_rel). u = 1/(y-h)."""
    rng = np.random.default_rng(seed)
    img = np.zeros((H, W, 3), np.uint8)
    img[:] = (200, 170, 120)  # cielo / fondo azul-ish (BGR)
    # césped a los lados
    ys = np.arange(H)
    poly = road_polygon_px()
    cv2.rectangle(img, (0, int(0.28 * H)), (W, H), (60, 140, 70), -1)
    # asfalto con gradiente (más claro lejos) y ruido de textura
    road = np.zeros((H, W), np.uint8)
    cv2.fillConvexPoly(road, poly, 255)
    yy = np.linspace(0, 1, H)[:, None]
    base = 95 + 35 * (1 - np.clip((yy - 0.3) / 0.65, 0, 1))
    asphalt = np.repeat(base[:, :, None], 3, axis=2).repeat(W, axis=1).astype(np.float32)
    asphalt += rng.normal(0, texture, asphalt.shape)
    asphalt[:, :, 0] += 4  # tono ligeramente frío
    asphalt = np.clip(asphalt, 0, 255).astype(np.uint8)
    img[road > 0] = asphalt[road > 0]
    # líneas de carril discontinuas (blancas, finas) y bordillos amarillos
    for k in (1, 2):
        for s in np.arange(0.30, 0.95, 0.045):
            y0, y1 = s, s + 0.022
            p0 = (left_x(y0) + lane_w(y0) * k, y0)
            p1 = (left_x(y1) + lane_w(y1) * k, y1)
            w0, w1 = max(1, lane_w(y0) * 0.05 * W), max(1, lane_w(y1) * 0.05 * W)
            pts = np.array([
                (p0[0] * W - w0 / 2, p0[1] * H), (p0[0] * W + w0 / 2, p0[1] * H),
                (p1[0] * W + w1 / 2, p1[1] * H), (p1[0] * W - w1 / 2, p1[1] * H)], np.int32)
            cv2.fillConvexPoly(img, pts, (235, 235, 235))
    for fx in (left_x, right_x):
        pts = np.array([(int(fx(y) * W), int(y * H)) for y in np.linspace(0.30, 0.95, 20)], np.int32)
        cv2.polylines(img, [pts], False, (40, 200, 240), 3)
    boxes = []
    for lane, u, color, wrel in cars:
        y = HORIZON + 1.0 / u
        if y > 1.0 or y < FL[1] + 0.005:
            continue
        w = wrel * lane_w(y)
        h = w * 1.1
        cx = lane_cx(lane, y)
        x0, x1, y0, y1 = int((cx - w / 2) * W), int((cx + w / 2) * W), int((y - h) * H), int(y * H)
        cv2.rectangle(img, (x0, y0), (x1, y1), color, -1)
        cv2.rectangle(img, (x0 + (x1 - x0) // 6, y0 + (y1 - y0) // 6), (x1 - (x1 - x0) // 6, y0 + (y1 - y0) // 2), (30, 30, 30), -1)
        cv2.rectangle(img, (x0, y1 - (y1 - y0) // 8), (x1, y1), (20, 20, 20), -1)  # parachoques oscuro
        boxes.append((lane, x0 / W, y0 / H, x1 / W, y1 / H))
    if with_player:
        pw = (PLAYER[2] - PLAYER[0])
        cx = lane_cx(player_lane, PLAYER[3])
        x0, x1 = int((cx - pw / 2) * W), int((cx + pw / 2) * W)
        cv2.rectangle(img, (x0, int(PLAYER[1] * H)), (x1, int(PLAYER[3] * H)), (200, 60, 30), -1)
    return img, boxes


# ------------------------------------------------------------------ modelo de color de la carretera
# Se aprende en espacio Lab (L: luminosidad; a,b: crominancia). El tono (H de HSV) es inútil
# sobre asfalto casi sin saturación, donde su ruido es enorme.
N_BANDS = 16
MIN_TOL_L = 24.0
MIN_TOL_C = 14.0


def learn_road_model(ref_bgr, exclude_rects=()):
    lab = cv2.cvtColor(ref_bgr, cv2.COLOR_BGR2LAB)
    mask = np.zeros((H, W), np.uint8)
    cv2.fillConvexPoly(mask, road_polygon_px(0.10), 255)
    for (l, t, r, b) in exclude_rects:
        cv2.rectangle(mask, (int(l * W), int(t * H)), (int(r * W), int(b * H)), 0, -1)
    ys = np.linspace(FL[1], NL[1], N_BANDS + 1)
    model = []
    for i in range(N_BANDS):
        band = mask.copy()
        band[: int(ys[i] * H)] = 0
        band[int(ys[i + 1] * H):] = 0
        px = lab[band > 0].astype(np.float32)
        if len(px) < 50:
            model.append(model[-1] if model else (110.0, 128.0, 128.0, MIN_TOL_L, MIN_TOL_C))
            continue
        med = np.median(px, axis=0)
        mad = np.median(np.abs(px - med), axis=0) * 1.4826
        tol_l = max(MIN_TOL_L, 4.0 * float(mad[0]))
        tol_c = max(MIN_TOL_C, 4.0 * float(np.hypot(mad[1], mad[2])))
        model.append((float(med[0]), float(med[1]), float(med[2]), tol_l, tol_c))
    return ys, model


def build_expected(ys, model, crop_top, crop_bottom, work_w, work_h):
    """Valores esperados y tolerancias por fila del recorte de trabajo (interpolados entre bandas)."""
    exp = np.zeros((work_h, 5), np.float32)
    centers = (ys[:-1] + ys[1:]) / 2
    for r in range(work_h):
        y = crop_top + (r + 0.5) / work_h * (crop_bottom - crop_top)
        for k in range(5):
            exp[r, k] = np.interp(y, centers, [m[k] for m in model])
    return exp


WORK_W = 160


def detect(frame_bgr, ys, model, shrink=0.12, debug=None):
    crop_top = FL[1] + 0.002   # justo en el borde lejano de la carretera (nada de césped/cielo)
    crop_bottom = PLAYER[1] - 0.004  # justo antes del automóvil del jugador
    x0n = left_x(crop_bottom) - 0.02
    x1n = right_x(crop_bottom) + 0.02
    x0, x1 = int(x0n * W), int(x1n * W)
    y0, y1 = int(crop_top * H), int(crop_bottom * H)
    crop = frame_bgr[y0:y1, x0:x1]
    scale = WORK_W / crop.shape[1]
    small = cv2.resize(crop, (WORK_W, int(crop.shape[0] * scale)), interpolation=cv2.INTER_AREA)
    wh, ww = small.shape[:2]
    lab = cv2.cvtColor(small, cv2.COLOR_BGR2LAB).astype(np.float32)
    exp = build_expected(ys, model, crop_top, crop_bottom, ww, wh)
    col = lambda k: exp[:, k:k + 1].repeat(ww, 1)
    dl = np.abs(lab[:, :, 0] - col(0))
    dc = np.hypot(lab[:, :, 1] - col(1), lab[:, :, 2] - col(2))
    mask = ((dl > col(3)) | (dc > col(4))).astype(np.uint8) * 255
    if debug is not None:
        debug['raw'] = mask.copy()
    dets = []
    k_open = np.ones((3, 3), np.uint8)
    k_close = cv2.getStructuringElement(cv2.MORPH_RECT, (5, 7))
    for lane in range(3):
        # Cada carril se analiza por separado y algo reducido: las líneas divisorias quedan fuera
        # y no pueden unir vehículos de carriles vecinos en un solo bloque.
        valid = np.zeros_like(mask)
        pts = []
        for yn in (crop_top, crop_bottom):
            w = lane_w(yn)
            xl_ = left_x(yn) + w * lane + shrink * w
            pts.append(((xl_ - x0n) / (x1n - x0n) * ww, (yn - crop_top) / (crop_bottom - crop_top) * wh))
        for yn in (crop_bottom, crop_top):
            w = lane_w(yn)
            xr_ = left_x(yn) + w * (lane + 1) - shrink * w
            pts.append(((xr_ - x0n) / (x1n - x0n) * ww, (yn - crop_top) / (crop_bottom - crop_top) * wh))
        cv2.fillConvexPoly(valid, np.round(np.array(pts)).astype(np.int32), 255)
        m = cv2.bitwise_and(mask, valid)
        m = cv2.morphologyEx(m, cv2.MORPH_OPEN, k_open)
        m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, k_close)
        n, _, stats, _ = cv2.connectedComponentsWithStats(m, connectivity=8)
        for i in range(1, n):
            x, y, w, h, area = stats[i]
            xl = x0n + x / ww * (x1n - x0n); xr = x0n + (x + w) / ww * (x1n - x0n)
            yt = crop_top + y / wh * (crop_bottom - crop_top); yb = crop_top + (y + h) / wh * (crop_bottom - crop_top)
            lw = lane_w(yb)
            reason = None
            if (xr - xl) < 0.30 * lw or (yb - yt) < 0.15 * lw:
                reason = 'tamaño'
            elif area / float(w * h) < 0.35:
                reason = 'relleno'
            if debug is not None:
                debug.setdefault('comps', []).append((lane, round(xl, 3), round(yt, 3), round(xr, 3), round(yb, 3), int(area), reason))
            if reason is None:
                dets.append((lane, xl, yt, xr, yb))
    return dets


def iou(a, b):
    ix = max(0, min(a[2], b[2]) - max(a[0], b[0])); iy = max(0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = ix * iy
    u = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / u if u > 0 else 0


COLORS = {"rojo": (40, 40, 220), "azul": (220, 90, 30), "blanco": (240, 240, 240), "negro": (25, 25, 25),
          "amarillo": (30, 210, 240), "verde": (60, 180, 60), "gris": (120, 120, 120)}


def main():
    ref, _ = render_scene([], seed=99)
    ys, model = learn_road_model(ref, exclude_rects=[(PLAYER[0] - 0.02, PLAYER[1] - 0.02, PLAYER[2] + 0.02, PLAYER[3] + 0.02)])
    crop_top = FL[1] + 0.002
    tp = fn = fp = fp_gray = 0
    per_color = {k: [0, 0] for k in COLORS}
    rng = np.random.default_rng(5)
    for trial in range(120):
        ncars = int(rng.integers(1, 4))
        lanes = rng.permutation(3)[:ncars]
        cars = []
        for ln in lanes:
            u = float(rng.uniform(1.8, 3.9))
            cname = list(COLORS)[int(rng.integers(0, len(COLORS)))]
            cars.append((int(ln), u, COLORS[cname], 0.7, cname))
        img, truth = render_scene([c[:4] for c in cars], player_lane=int(rng.integers(0, 3)), seed=trial)
        dets = detect(img, ys, model)
        tr = {t[0]: (t[1], max(t[2], crop_top), t[3], t[4]) for t in truth}
        used = set()
        for c in cars:
            if c[0] not in tr:
                continue
            tb = tr[c[0]]
            best, bi = 0, None
            for i, d in enumerate(dets):
                if d[0] != c[0]:
                    continue
                v = iou(d[1:], tb)
                if v > best:
                    best, bi = v, i
            per_color[c[4]][1] += 1
            if best >= 0.5:
                tp += 1; used.add(bi); per_color[c[4]][0] += 1
            else:
                fn += 1
        for i, d in enumerate(dets):
            if i in used:
                continue
            if any(c[0] == d[0] and c[4] == 'gris' for c in cars):
                fp_gray += 1   # fragmentos (ventanas/parachoques) de un coche del color del asfalto
            else:
                fp += 1
    empty_fp = 0
    for sd in range(60):
        img, _ = render_scene([], player_lane=sd % 3, seed=1000 + sd)
        empty_fp += len(detect(img, ys, model))
    prec = tp / max(1, tp + fp); rec = tp / max(1, tp + fn)
    print(f"escenas=120 vehículos={tp+fn}  TP={tp} FN={fn} FP={fp} (+{fp_gray} fragmentos de coche gris-asfalto)")
    print(f"falsos positivos en 60 carreteras vacías: {empty_fp}")
    print(f"precisión={prec:.3f}  recuerdo={rec:.3f}  (sobre imágenes SINTÉTICAS)")
    for k, (a_, b_) in per_color.items():
        print(f"  {k:9s} {a_}/{b_}")
    # Excluyendo el color gris (idéntico al asfalto: límite inherente de un método basado en color)
    tpg = sum(v[0] for k, v in per_color.items() if k != 'gris'); totg = sum(v[1] for k, v in per_color.items() if k != 'gris')
    print(f"recuerdo sin el coche gris-asfalto: {tpg}/{totg} = {tpg/max(1,totg):.3f}")
    ok = prec >= 0.95 and tpg / max(1, totg) >= 0.95 and empty_fp == 0
    print("RESULTADO:", "OK" if ok else "MEJORABLE")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
