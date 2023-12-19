package sc.fiji.snt

import graphics.scenery.*
import graphics.scenery.attribute.material.Material
import graphics.scenery.controls.InputHandler
import graphics.scenery.controls.OpenVRHMD
import graphics.scenery.controls.TrackedDeviceType
import graphics.scenery.controls.TrackerRole
import graphics.scenery.controls.behaviours.*
import graphics.scenery.numerics.Random
import graphics.scenery.utils.extensions.xyzw
import graphics.scenery.utils.lazyLogger
import net.imagej.DatasetService
import net.imagej.ImgPlus
import net.imagej.axis.Axes
import net.imagej.axis.AxisType
import net.imglib2.img.display.imagej.ImageJFunctions
import org.joml.Matrix4f
import org.joml.Vector3d
import org.joml.Vector3f
import org.scijava.ui.behaviour.ClickBehaviour
import sc.fiji.snt.util.PointInImage
import kotlin.concurrent.thread


private lateinit var hullbox: Box


class ControllerTracing(val sciViewSNT: SciViewSNT) {
    val logger by lazyLogger()

    init {
        sciViewSNT.sciView.toggleVRRendering()

        thread {
            Thread.sleep(10000)

            sciViewSNT.sciView.mainWindow.toggleSidebar()
            Thread.sleep(500)
            sciViewSNT.sciView.mainWindow.toggleSidebar()
        }

        val hmd = sciViewSNT.sciView.hub.get<OpenVRHMD>() //?: throw UnsupportedOperationException("No hmd found")
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
                    hmd?.addBehaviour(name, b)
                    hmd?.addKeyBinding(name, key)
                    if (b is MovementCommand) {
                        b.speed = 0.2f
                    }
                }
            }
        }


        val cam = sciViewSNT.sciView.camera ?: throw IllegalStateException("No camera found.")
        val scene = sciViewSNT.sciView.camera?.getScene() ?: throw IllegalStateException("No scene found.")

        // adds light sources to the scene
        val lights = Light.createLightTetrahedron<PointLight>(spread = 5.0f, radius = 8.0f)
        lights.forEach {
            it.emissionColor = Random.random3DVectorFromRange(0.8f, 1.0f)
            scene.addChild(it)
        }

        // adds borders to the scene
        hullbox = Box(Vector3f(10.0f, 10.0f, 10.0f), insideNormals = true)
        hullbox.material {
            ambient = Vector3f(0.6f, 0.6f, 0.6f)
            diffuse = Vector3f(0.4f, 0.4f, 0.4f)
            specular = Vector3f(0.0f, 0.0f, 0.0f)
            cullingMode = Material.CullingMode.Front
        }

        scene.addChild(hullbox)

        //alternate way to light up the scene
        scene.addChild(AmbientLight())

        //allows objects in the scene that are marked as grabable or pressable to be grabbed (side button) and pressed (trigger or A button)
        if (hmd != null) {
            VRTouch.createAndSet(scene, hmd, listOf(TrackerRole.RightHand, TrackerRole.LeftHand), true)
            VRGrab.createAndSet(
                scene,
                hmd,
                listOf(OpenVRHMD.OpenVRButton.Side),
                listOf(TrackerRole.LeftHand, TrackerRole.RightHand)
            )
            VRPress.createAndSet(
                scene,
                hmd,
                listOf(OpenVRHMD.OpenVRButton.Trigger, OpenVRHMD.OpenVRButton.A),
                listOf(TrackerRole.LeftHand, TrackerRole.RightHand)
            )
        }

        /*hmd.events.onDeviceConnect.add { _, device, _ ->
            if (device.type == TrackedDeviceType.Controller) {
                device.model?.let { controller ->
                    if (device.role == TrackerRole.RightHand) {

                        hmd.addBehaviour(
                            "placePoint", ClickBehaviour { _, _ ->
                                val trackingPoint = Sphere(0.03f)
                                trackingPoint.spatial().position = controller.spatialOrNull()!!.worldPosition()
                                scene.addChild(trackingPoint)
                            }

                        )
                        hmd.addKeyBinding("placePoint", TrackerRole.RightHand, OpenVRHMD.OpenVRButton.Trigger)
                    }
                }
            }
        }*/

        //adds image to the scene as a volume
        //the image added here is the OP1 neuron in Drosophila
        val image = ij.IJ.openImage("C:\\Users\\tempel52\\Documents\\GitHub\\SNT\\src\\test\\resources\\OP_1.tif")
        val imp = ImageJFunctions.wrapByte(image)
        val vol = sciViewSNT.sciView.addVolume(imp)
        vol?.spatial {
            scale = Vector3f(0.1f, 0.1f, 0.85f)
        }

        //adds a pen that can be used to place points
        val pen = Box(Vector3f(0.05f, 0.2f, 0.05f))
        pen.spatial {
            position = Vector3f(0f, 0f, 0f)
        }
        scene.addChild(pen)
        val tip = Box(Vector3f(0.025f, 0.1f, 0.025f))
        tip.spatial {
            position = Vector3f(0f, 0.08f, 0f)

        }
        pen.addChild(tip)
        var lastPenWriting = 0L

        var pointList = arrayListOf<Vector3d>()

        sciViewSNT.snt.initialize(image)


        pen.addAttribute(Grabable::class.java, Grabable())
        pen.addAttribute(
            Pressable::class.java, PerButtonPressable(
                mapOf(
                    OpenVRHMD.OpenVRButton.Trigger to SimplePressable(onHold = {
                        if (System.currentTimeMillis() - lastPenWriting > 200) {
                            val ink = Sphere(0.008f)
                            ink.spatial().position = tip.spatial().worldPosition()
                            scene.addChild(ink)
                            lastPenWriting = System.currentTimeMillis()
                            val volume =
                                scene.findByClassname("RAIVolume").firstOrNull() as? graphics.scenery.volumes.Volume
                            if (volume != null) {
                                val position = tip.spatial().worldPosition()
                                val voxelCoordinate =
                                    Matrix4f(volume.spatial().world).invert().transform(position.xyzw())
                                voxelCoordinate.div(
                                    volume.getDimensions().x.toFloat(),
                                    volume.getDimensions().y.toFloat(),
                                    volume.getDimensions().z.toFloat(),
                                    1.0f
                                )

                                if (voxelCoordinate.get(voxelCoordinate.maxComponent()) > 1.0f
                                    || voxelCoordinate.get(voxelCoordinate.minComponent()) < 0.0f
                                ) {
                                    logger.warn("Coordinates $voxelCoordinate out of bounds, skipping tracing.")
                                    return@SimplePressable
                                }

                                sciViewSNT.snt.clickForTrace(
                                    voxelCoordinate.x.toDouble()/volume.getDimensions().x,
                                    voxelCoordinate.y.toDouble()/volume.getDimensions().y,
                                    voxelCoordinate.z.toDouble()/volume.getDimensions().z,
                                    true
                                )

                                val point = Vector3d(
                                    voxelCoordinate.x.toDouble() * volume.getDimensions().x,
                                    voxelCoordinate.y.toDouble() * volume.getDimensions().y,
                                    voxelCoordinate.z.toDouble() * volume.getDimensions().z
                                )

                                pointList += point

                                if (pointList.size >= 2) {

                                    val p1 = pointList[pointList.size - 2 ]
                                    val p2 = pointList[pointList.size - 1 ]

                                    logger.info("Tracing from $p1 to $p2")

                                    val start = PointInImage(p1.x, p1.y, p1.z) // spatially calibrated
                                    val end = PointInImage(p2.x, p2.y, p2.z) // spatially calibrated



                                    //val tree = Tree(listOf(sciViewSNT.snt.autoTrace(start, end, null, false)))
                                    //sciViewSNT.addTree(tree, volume);

                                }



                                logger.info("$voxelCoordinate")
                            } else {
                                logger.warn("No Volume Found")
                            }
                        }
                    })
                )
            )
        )

        //adds the controller models to the scene and attaches them to the camera in order for them to move with the user
        hmd?.events?.onDeviceConnect?.add { _, device, timestamp ->
            if (device.type == TrackedDeviceType.Controller) {
                logger.info("Got device ${device.name} at $timestamp")
                device.model?.let { controller ->
                    // This attaches the model of the controller to the controller's transforms
                    // from the OpenVR/SteamVR system.
                    hmd.attachToNode(device, controller, cam)

                    if (device.role == TrackerRole.RightHand) {
                        hmd.addBehaviour(
                            "summonPen",
                            ClickBehaviour { _, _ ->
                                val position = controller.children.first().spatialOrNull()!!.worldPosition()
                                logger.info("Summoning controller to position $position}")
                                pen.spatial().position = position
                            })
                        hmd.addKeyBinding("summonPen", TrackerRole.RightHand, OpenVRHMD.OpenVRButton.A)
                    }
                }
            }
        }
    }
}







