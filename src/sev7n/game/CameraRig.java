package sev7n.game;

import sev7n.math.Vec3;
import sev7n.scene.Entity;
import sev7n.scene.Scene;
import sev7n.scene.ThirdPersonCamera;

/** Moves Third Person Cameras each frame, after physics, so they follow where things ended up. */
final class CameraRig {

    private CameraRig() { }

    static void update(Scene scene, Input input, double dt) {
        for (Entity camera : scene.entities()) {
            ThirdPersonCamera rig = camera.get(ThirdPersonCamera.class);
            if (rig == null || !camera.active) continue;
            Entity target = scene.find(rig.target);
            if (target == null) continue;
            if (!rig.started) {
                rig.started = true;
                rig.yaw = target.transform.rotation.y;   // start behind the character
                if (rig.lockMouse) input.setMouseLocked(true);
            }
            // orbit: the mouse (while it's captured, or with the right button held) and the right stick
            if (input.mouseLocked() || input.isRightMouseDown()) {
                rig.yaw -= input.mouseMoveX() * rig.sensitivity;
                rig.pitch += input.mouseMoveY() * rig.sensitivity;
            }
            rig.yaw -= input.padAxis("RX") * 120 * dt;
            rig.pitch += input.padAxis("RY") * 90 * dt;
            rig.pitch = Math.max(rig.minPitch, Math.min(rig.maxPitch, rig.pitch));

            Vec3 focus = target.transform.position.plus(new Vec3(0, rig.height, 0));
            double yaw = Math.toRadians(rig.yaw), pitch = Math.toRadians(rig.pitch);
            Vec3 back = new Vec3(Math.sin(yaw) * Math.cos(pitch), Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));

            // walls: come in closer at once; go back out smoothly
            double wanted = rig.distance;
            if (rig.avoidWalls) {
                Physics.RayHit hit = Physics.raycast(scene, focus, back, rig.distance, target);
                if (hit != null) wanted = Math.max(0.3, hit.distance() - 0.25);
            }
            if (rig.shownDistance < 0 || wanted < rig.shownDistance || rig.smoothing == 0) rig.shownDistance = wanted;
            else rig.shownDistance += (wanted - rig.shownDistance) * Math.min(1, rig.smoothing * dt * 0.5);

            Vec3 place = focus.plus(back.times(rig.shownDistance));
            Vec3 at = camera.transform.position;
            double follow = rig.smoothing == 0 ? 1 : Math.min(1, rig.smoothing * dt);
            if (at.minus(place).length() > rig.distance * 3) follow = 1; // too far (a teleport, the first frame): jump there
            at.set(at.x + (place.x - at.x) * follow, at.y + (place.y - at.y) * follow, at.z + (place.z - at.z) * follow);
            camera.transform.rotation.set(-rig.pitch, rig.yaw, 0);
        }
    }

    /** Where the main camera looks, flat on the ground (for moving a character "forward" from the player's view). */
    static Vec3 flatForward(Scene scene) {
        Entity camera = scene.mainCamera();
        if (camera == null) return new Vec3(0, 0, -1);
        Vec3 f = camera.transform.forward();
        Vec3 flat = new Vec3(f.x, 0, f.z);
        return flat.length() < 1e-6 ? new Vec3(0, 0, -1) : flat.normalized();
    }

    static Vec3 flatRight(Scene scene) {
        Vec3 f = flatForward(scene);
        return new Vec3(-f.z, 0, f.x);
    }
}
