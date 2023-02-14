package sc.fiji.snt

import graphics.scenery.*
import graphics.scenery.controls.InputHandler
import graphics.scenery.controls.OpenVRHMD
import graphics.scenery.controls.TrackerRole
import graphics.scenery.controls.behaviours.Grabable
import graphics.scenery.controls.behaviours.Pressable
import graphics.scenery.utils.LazyLogger
import java.lang.UnsupportedOperationException
import org.joml.Vector3f
import java.lang.IllegalStateException
import java.lang.System



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
        val scene = sciViewSNT.sciView.camera?.getScene()?:throw IllegalStateException("No scene found.")
        val pen = Box(Vector3f(0.05f, 0.2f, 0.05f))
        pen.spatial{
            position = Vector3f(-0.5f, 1.0f, 0f)
        }
        scene.addChild(pen)
        val tip = Box(Vector3f(0.025f, 0.025f, 0.025f))
        tip.spatial {
            position = Vector3f(0f, 0.08f, 0f)
        }
        pen.addChild(tip)
        var lastPenWriting = 0L
        pen.addAttribute(Grabable::class.java, Grabable())
        pen.addAttribute(Pressable::class.java, Pressable(onHold = {
            if (System.currentTimeMillis() - lastPenWriting > 50){
                val ink = Sphere()
                ink.spatial().position=tip.spatial().worldPosition()
                scene.addChild(ink)
                lastPenWriting = System.currentTimeMillis()
            }
        }))
    }
}

