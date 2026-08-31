/**
 * Round-trips Viewport.project against the shader's own forward ray construction: project a world point to a
 * pixel, rebuild the primary ray for that pixel exactly as SdfComposer.primaryRay does, and check the ray from
 * the eye actually points at the point. If these disagree the grid slides against the geometry.
 */
public final class ProjectRoundTrip {

    static final double FOCAL = 1.4;

    public static void main(String[] args) {
        double[] eye = worldEye(Math.toRadians(38), Math.toRadians(26), 5.0);
        double yaw = Math.toRadians(38), pitch = Math.toRadians(26);
        double aspect = 900.0 / 520.0;
        int w = 900, h = 520;
        double worst = 0;

        for (double x = -3; x <= 3; x += 1.5) {
            for (double z = -3; z <= 3; z += 1.5) {
                for (double y : new double[]{0, 0.8}) {
                    double[] p = {x, y, z};
                    double[] px = project(eye, yaw, pitch, aspect, w, h, p);
                    double u = px[0] / w, v = px[1] / h;
                    double[] rd = primaryRay(u, v, yaw, pitch, aspect);
                    // the true direction from eye to p, normalised
                    double dx = p[0] - eye[0], dy = p[1] - eye[1], dz = p[2] - eye[2];
                    double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    double err = Math.abs(rd[0] - dx / len) + Math.abs(rd[1] - dy / len) + Math.abs(rd[2] - dz / len);
                    worst = Math.max(worst, err);
                }
            }
        }
        System.out.printf("worst direction error over the sampled grid: %.3e%n", worst);
        System.out.println(worst < 1e-9 ? "PASS - the overlay and the march agree" : "FAIL - the grid will slide");
    }

    /** Viewport.worldEye: Camera.eye in plot coords (z up), swapped into the marched world (y up). */
    static double[] worldEye(double yaw, double pitch, double d) {
        double cp = Math.cos(pitch);
        double[] fwdPlot = {cp * Math.sin(yaw), cp * Math.cos(yaw), -Math.sin(pitch)};
        double[] plot = {-d * fwdPlot[0], -d * fwdPlot[1], -d * fwdPlot[2]};
        return new double[]{plot[0], plot[2], plot[1]};
    }

    /** Viewport.project, verbatim. */
    static double[] project(double[] eye, double yaw, double pitch, double aspect, int w, int h, double[] p) {
        double dx = p[0] - eye[0], dy = p[1] - eye[1], dz = p[2] - eye[2];
        double cosYaw = Math.cos(yaw), sinYaw = Math.sin(yaw);
        double sx = dx * cosYaw - dz * sinYaw;
        double pz = dx * sinYaw + dz * cosYaw;
        double cosPitch = Math.cos(pitch), sinPitch = Math.sin(pitch);
        double sy = dy * cosPitch + pz * sinPitch;
        double forward = pz * cosPitch - dy * sinPitch;
        double k = FOCAL / forward;
        double u = (sx * k / aspect + 1) / 2;
        double v = (1 - sy * k) / 2;
        return new double[]{u * w, v * h};
    }

    /** SdfComposer.primaryRay, transcribed from the IR it builds. */
    static double[] primaryRay(double u, double v, double yaw, double pitch, double aspect) {
        double sx = (u * 2 - 1) * aspect;
        double sy = 1 - v * 2;
        double cosPitch = Math.cos(pitch), sinPitch = Math.sin(pitch);
        double py = sy * cosPitch - FOCAL * sinPitch;
        double pz = sy * sinPitch + FOCAL * cosPitch;
        double cosYaw = Math.cos(yaw), sinYaw = Math.sin(yaw);
        double rx = sx * cosYaw + pz * sinYaw;
        double rz = pz * cosYaw - sx * sinYaw;
        double len = Math.sqrt(rx * rx + py * py + rz * rz);
        return new double[]{rx / len, py / len, rz / len};
    }
}
