"""Genera los fixtures de prueba del vehículo del jugador a partir de una captura REAL de la pantalla de calibración
(juego de coches de 3 carriles, captura aportada por el usuario). Pasos:
  1. Recorta el área de juego (ancho 822 px en la captura de pantalla) y borra la superposición de calibración
     (líneas, puntos, caja) con inpainting.
  2. Construye una carretera VACÍA rellenando la zona del coche con el color de asfalto de cada fila.
  3. Extrae el sprite del coche (RGBA) por diferencia con la carretera vacía.
Uso: python3 tools/make_player_fixtures.py captura.jpg  (salida en core/src/test/resources/player/)"""
import sys, cv2, numpy as np
src = sys.argv[1]
out = 'core/src/test/resources/player/'
im = cv2.imread(src)
s = im.shape[1] / 900.0                      # la captura se vio escalada a 900 px de ancho
game = im[0:int(1322 * s), int(39 * s):int(861 * s)].copy()
H, W = game.shape[:2]
P = dict(p1=(254, 645), p2=(626, 649), p3=(47, 1196), p4=(861, 1196), b1=(327, 853), b2=(576, 1036))
g = lambda p: (int((p[0] - 39) * s), int(p[1] * s))
P = {k: g(v) for k, v in P.items()}
m = np.zeros((H, W), np.uint8)
t = int(7 * s)
L = lambda a, b: cv2.line(m, a, b, 255, t)
L(P['p1'], P['p2']); L(P['p2'], P['p4']); L(P['p4'], P['p3']); L(P['p3'], P['p1'])
for k in (1, 2):
    f = k / 3
    a = (int(P['p1'][0] + (P['p2'][0] - P['p1'][0]) * f), int(P['p1'][1] + (P['p2'][1] - P['p1'][1]) * f))
    b = (int(P['p3'][0] + (P['p4'][0] - P['p3'][0]) * f), int(P['p3'][1] + (P['p4'][1] - P['p3'][1]) * f))
    L(a, b)
cv2.rectangle(m, P['b1'], P['b2'], 255, int(8 * s))
for v in P.values():
    cv2.circle(m, v, int(16 * s), 255, -1)
    cv2.rectangle(m, (v[0] + int(8 * s), v[1] - int(34 * s)), (v[0] + int(40 * s), v[1] - int(6 * s)), 255, -1)
clean = cv2.inpaint(game, m, 5, cv2.INPAINT_TELEA)
TW = 540
th = int(round(H * TW / W))
ref = cv2.resize(clean, (TW, th), interpolation=cv2.INTER_AREA)
h, w = ref.shape[:2]
empty = ref.copy()
y0, y1 = int(0.64 * h), int(0.87 * h)
x0, x1 = int(0.28 * w), int(0.74 * w)
for y in range(y0, y1):
    row = np.median(ref[y, int(0.15 * w):int(0.26 * w)].reshape(-1, 3), axis=0)
    empty[y, x0:x1] = row
diff = np.abs(ref.astype(int) - empty.astype(int)).sum(axis=2)
alpha = np.zeros((h, w), np.uint8)
alpha[y0:y1, x0:x1] = (diff[y0:y1, x0:x1] > 24) * 255
alpha = cv2.morphologyEx(alpha, cv2.MORPH_CLOSE, np.ones((9, 9), np.uint8))
n, lab, st, _ = cv2.connectedComponentsWithStats(alpha)
big = 1 + np.argmax(st[1:, 4])
alpha = ((lab == big) * 255).astype(np.uint8)
ys, xs = np.where(alpha > 0)
bx0, bx1, by0, by1 = xs.min(), xs.max() + 1, ys.min(), ys.max() + 1
sprite = np.dstack([ref, alpha])[by0:by1, bx0:bx1]
cv2.imwrite(out + 'reference_with_car.png', ref)
cv2.imwrite(out + 'empty_road.png', empty)
cv2.imwrite(out + 'car_sprite.png', sprite)
nx = lambda p: (round(p[0] / W, 4), round(p[1] / H, 4))
print('size', w, h)
print('puntos normalizados', {k: nx(v) for k, v in P.items()})
print('sprite bbox px', (bx0, by0, bx1, by1), 'norm', (bx0 / w, by0 / h, bx1 / w, by1 / h))
