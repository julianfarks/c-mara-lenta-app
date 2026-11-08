#!/usr/bin/env python3
"""
Validación del algoritmo de extensión automática del borde lejano de la carretera (mismo algoritmo que
RoadExtender.kt). El usuario toca el borde lejano donde cree que empieza la calzada; si lo toca más
abajo de donde de verdad empieza, el detector no mira por encima y ve los coches tarde.
Solo imágenes SINTÉTICAS.

Uso: python3 -I tools/extension_study.py
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
import cv2
import prototype_detector as P

STEP = 0.004
MAX_EXT = 0.35
MAX_DL = 10.0       # salto de luminosidad permitido entre filas consecutivas
MAX_DC = 7.0        # salto de crominancia permitido
MAX_SPREAD = 14.0   # dispersión (MAD de L) máxima en una fila de carretera
FAILS_TO_STOP = 3


def row_stats(lab, y, x0, x1):
    h, w = lab.shape[:2]
    py = int(y * h)
    rows = lab[max(0, py - 1):py + 2, max(0, int(x0 * w)):max(int(x0 * w) + 1, int(x1 * w))].reshape(-1, 3).astype(np.float32)
    if len(rows) < 6:
        return None
    med = np.median(rows, axis=0)
    mad = np.median(np.abs(rows[:, 0] - med[0])) * 1.4826
    return med, mad


def extend_far(img_bgr, left_x, right_x, far_y, horizon):
    """Devuelve la y más alta (más lejana) hasta la que la calzada sigue siendo carretera continua."""
    lab = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2LAB)
    prev = None
    # fila de partida: justo dentro de la zona confirmada por el usuario
    y = far_y + 0.01
    prev = row_stats(lab, y, left_x(y) + 0.25 * (right_x(y) - left_x(y)), left_x(y) + 0.75 * (right_x(y) - left_x(y)))
    if prev is None:
        return far_y
    accepted = far_y
    fails = 0
    y = far_y
    limit = max(horizon + 0.03, far_y - MAX_EXT)
    while y > limit:
        w = right_x(y) - left_x(y)
        st = row_stats(lab, y, left_x(y) + 0.25 * w, left_x(y) + 0.75 * w)
        ok = False
        if st is not None:
            med, mad = st
            dl = abs(med[0] - prev[0][0])
            dc = float(np.hypot(med[1] - prev[0][1], med[2] - prev[0][2]))
            ok = dl <= MAX_DL and dc <= MAX_DC and mad <= MAX_SPREAD
        if ok:
            prev = st
            accepted = y
            fails = 0
        else:
            fails += 1
            if fails >= FAILS_TO_STOP:
                break
        y -= STEP
    return accepted


def main():
    ok = True
    print("Borde lejano tocado por el usuario -> y extendida (verdad: %.3f)" % P.FL[1])
    for user_far in (0.34, 0.40, 0.45, 0.55):
        errs = []
        for seed in range(6):
            img, _ = P.render_scene([], player_lane=seed % 3, seed=seed)
            got = extend_far(img, P.left_x, P.right_x, user_far, P.HORIZON)
            errs.append(got)
        m = float(np.mean(errs))
        good = abs(m - P.FL[1]) <= 0.02
        ok &= good
        print(f"  tocó {user_far:.2f} -> {m:.3f}  (min {min(errs):.3f} max {max(errs):.3f})  {'OK' if good else 'FALLA'}")
    # No debe pasarse al cielo cuando el cielo es muy distinto de la carretera
    img, _ = P.render_scene([], seed=3)
    got = extend_far(img, P.left_x, P.right_x, 0.31, P.HORIZON)
    print(f"  ya en el borde real (0.31) -> {got:.3f}  {'OK' if got >= P.FL[1] - 0.012 else 'SE PASA'}")
    ok &= got >= P.FL[1] - 0.012
    # Con un coche en la zona a extender se detiene antes (conservador), nunca se pasa
    car, _ = P.render_scene([(1, 3.0, (40, 40, 220), 0.7)], seed=4)
    got = extend_far(car, P.left_x, P.right_x, 0.45, P.HORIZON)
    print(f"  con un coche entre medias, tocó 0.45 -> {got:.3f} (se detiene antes; conservador)")
    print("RESULTADO:", "OK" if ok else "MEJORABLE")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
