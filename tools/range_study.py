#!/usr/bin/env python3
"""
Alcance del detector: ¿a qué distancia (y en pantalla) empieza a ver un vehículo, y con qué error
en el borde inferior (que es lo que mide el TTC)? Compara anchos de trabajo del detector.
Solo imágenes SINTÉTICAS: el alcance real en un juego depende de su contraste y nitidez.

Uso: python3 -I tools/range_study.py
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
import prototype_detector as P

COLORS = {k: v for k, v in P.COLORS.items() if k != "gris"}  # el gris-asfalto es un límite conocido


def study(work_w):
    P.WORK_W = work_w
    ref, _ = P.render_scene([], seed=99)
    ys, model = P.learn_road_model(ref, exclude_rects=[(P.PLAYER[0] - .02, P.PLAYER[1] - .02, P.PLAYER[2] + .02, P.PLAYER[3] + .02)])
    rows = []
    for y in np.arange(0.31, 0.42, 0.01):
        u = 1.0 / (y - P.HORIZON)
        hit = tot = 0
        errs = []
        for cname, col in COLORS.items():
            for lane in range(3):
                for seed in range(3):
                    img, truth = P.render_scene([(lane, u, col, 0.7)], player_lane=(lane + 1) % 3, seed=seed + 11 * lane)
                    if not truth:
                        continue
                    tb = truth[0]
                    dets = [d for d in P.detect(img, ys, model) if d[0] == lane]
                    tot += 1
                    best = max(dets, key=lambda d: P.iou(d[1:], (tb[1], max(tb[2], P.FL[1] + .002), tb[3], tb[4])), default=None)
                    if best is not None and P.iou(best[1:], (tb[1], max(tb[2], P.FL[1] + .002), tb[3], tb[4])) >= 0.3:
                        hit += 1
                        errs.append(abs(best[4] - tb[4]) * P.H)
        rows.append((float(y), hit, tot, float(np.mean(errs)) if errs else float('nan')))
    return rows


def main():
    print("y_pantalla  " + "  ".join(f"ancho={w:<4d}" for w in (160, 224, 288)))
    results = {w: study(w) for w in (160, 224, 288)}
    for i in range(len(results[160])):
        y = results[160][i][0]
        cells = []
        for w in (160, 224, 288):
            _, h, t, e = results[w][i]
            cells.append(f"{h:2d}/{t:<2d} e={e:4.1f}px")
        print(f"  {y:.2f}      " + "  ".join(cells))
    for w in (160, 224, 288):
        ok = [r[0] for r in results[w] if r[2] and r[1] / r[2] >= 0.95]
        print(f"ancho={w}: primera y con recall>=95%: {min(ok):.2f}" if ok else f"ancho={w}: nunca alcanza 95%")


if __name__ == "__main__":
    main()
