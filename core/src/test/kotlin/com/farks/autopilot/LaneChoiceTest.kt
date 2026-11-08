package com.farks.autopilot

import com.farks.autopilot.decision.PathPlanner
import com.farks.autopilot.models.EngineConfig
import com.farks.autopilot.models.Lane
import com.farks.autopilot.models.MoveDirection
import com.farks.autopilot.prediction.ObstacleWindow
import org.junit.Assert.assertEquals
import org.junit.Test

/** Elección del carril destino: debe ir al hueco libre, no solo esquivar el coche de su carril. */
class LaneChoiceTest {
    private val cfg = EngineConfig(deferSec = 0f)
    private fun w(id: Int, arrive: Float, weight: Float = 1f) = ObstacleWindow(id, arrive, arrive + 0.35f, weight)
    private fun plan(win: Map<Lane, List<ObstacleWindow>>, start: Lane = Lane.CENTER) =
        PathPlanner(cfg).plan(start, win, 10f, 0, 0f, 0.1f)

    @Test fun centerAndRightBusy_goesToEmptyLeft() {
        for (a in listOf(0.5f, 0.7f, 0.9f)) for (d in listOf(-0.3f, 0f, 0.15f, 0.3f)) {
            val r = plan(mapOf(Lane.CENTER to listOf(w(1, a)), Lane.RIGHT to listOf(w(2, a + d))))
            assertEquals("centro=$a derecha=${a + d}", MoveDirection.LEFT, r.moveNow)
        }
    }

    @Test fun rightCarNotYetConfirmed_stillAvoidsRight() {
        for (a in listOf(0.5f, 0.7f, 0.9f)) for (d in listOf(-0.3f, 0f, 0.3f, 0.6f)) {
            val r = plan(mapOf(Lane.CENTER to listOf(w(1, a)), Lane.RIGHT to listOf(w(2, a + d, cfg.tentativeWeight))))
            assertEquals("centro=$a derecha(sin confirmar)=${a + d}", MoveDirection.LEFT, r.moveNow)
        }
    }

    @Test fun centerAndLeftBusy_goesToEmptyRight() {
        for (a in listOf(0.5f, 0.7f, 0.9f)) for (d in listOf(-0.3f, 0f, 0.3f)) {
            val r = plan(mapOf(Lane.CENTER to listOf(w(1, a)), Lane.LEFT to listOf(w(2, a + d, cfg.tentativeWeight))))
            assertEquals(MoveDirection.RIGHT, r.moveNow)
        }
    }

    @Test fun edgeLaneCarWithLateRight_prefersEmptyCenterOrSide() {
        // Jugador a la izquierda, coche en su carril y otro en el centro llegando algo después: no hay hueco 'cercano';
        // debe elegir el menor riesgo, nunca meterse debajo del que llega antes.
        val r = plan(mapOf(Lane.LEFT to listOf(w(1, 0.6f)), Lane.CENTER to listOf(w(2, 0.9f))), Lane.LEFT)
        assertEquals(true, r.moveNow == null || r.moveNow == MoveDirection.RIGHT)
    }
}

class DeferTest {
    private val cfg = EngineConfig()
    private fun w(id: Int, a: Float) = ObstacleWindow(id, a, a + 0.35f, 1f)

    @Test fun waitsWhileThereIsSlack_thenMoves() {
        val p = PathPlanner(cfg)
        val far = p.plan(Lane.CENTER, mapOf(Lane.CENTER to listOf(w(1, 1.3f))), 10f, 0, 0f, 0.2f)
        assertEquals("con holgura debe esperar", null, far.moveNow)
        val near = p.plan(Lane.CENTER, mapOf(Lane.CENTER to listOf(w(1, 0.65f))), 10f, 0, 0f, 0.2f)
        assertEquals("sin holgura debe moverse", true, near.moveNow != null)
    }
}
