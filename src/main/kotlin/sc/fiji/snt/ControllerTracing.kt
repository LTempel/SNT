package sc.fiji.snt

import graphics.scenery.controls.InputHandler
import graphics.scenery.controls.OpenVRHMD
import graphics.scenery.controls.TrackerRole
import graphics.scenery.utils.LazyLogger
import java.lang.UnsupportedOperationException

class ControllerTracing(val sciViewSNT: SciViewSNT) {
    val logger by LazyLogger()
    init {
        sciViewSNT.sciView.toggleVRRendering()
        val hmd = sciViewSNT.sciView.hub.get<OpenVRHMD>() ?: throw UnsupportedOperationException("No hmd found")
        val inputHandler = sciViewSNT.sciView.hub.get<InputHandler>()
        inputHandler?.let { handler ->
            hashMapOf(
                "move_forward" to OpenVRHMD.keyBinding(TrackerRole.RightHand, OpenVRHMD.OpenVRButton.Up),
                "move_back" to OpenVRHMD.keyBinding(TrackerRole.RightHand, OpenVRHMD.OpenVRButton.Down),
                "move_left" to OpenVRHMD.keyBinding(TrackerRole.RightHand, OpenVRHMD.OpenVRButton.Left),
                "move_right" to OpenVRHMD.keyBinding(TrackerRole.RightHand, OpenVRHMD.OpenVRButton.Right)
            ).forEach { (name, key) ->
                handler.getBehaviour(name)?.let { b ->
                    logger.info("Adding behaviour $name bound to $key to HMD")
                    hmd.addBehaviour(name, b)
                    hmd.addKeyBinding(name, key)
                }
            }
        }
    }
}

