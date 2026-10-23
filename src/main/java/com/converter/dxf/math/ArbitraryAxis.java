package com.converter.dxf.math;

/**
 * Implements the AutoCAD Arbitrary Axis Algorithm from the DXF specification.
 * Converts 2D Object Coordinate System (OCS) points and angles to 3D World Coordinate System (WCS).
 */
public final class ArbitraryAxis {
    private static final double EPSILON = 1.0 / 64.0; // 0.015625 (AutoCAD standard threshold)

    private final Vector3D ax; // OCS X-axis in WCS
    private final Vector3D ay; // OCS Y-axis in WCS
    private final Vector3D az; // Extrusion normal vector (OCS Z-axis)

    public ArbitraryAxis(Vector3D normal) {
        Vector3D n = normal.normalize();
        this.az = n;

        Vector3D axRaw;
        // DXF Specification: test if normal is within 1/64 of world Z
        if (Math.abs(n.x()) < EPSILON && Math.abs(n.y()) < EPSILON) {
            axRaw = Vector3D.Y_AXIS.cross(n);
        } else {
            axRaw = Vector3D.Z_AXIS.cross(n);
        }

        this.ax = axRaw.normalize();
        this.ay = n.cross(this.ax).normalize();
    }

    public Vector3D ocsToWcs(double x, double y, double z) {
        return new Vector3D(
            x * ax.x() + y * ay.x() + z * az.x(),
            x * ax.y() + y * ay.y() + z * az.y(),
            x * ax.z() + y * ay.z() + z * az.z()
        );
    }

    public Vector3D ocsDirectionToWcs(double ocsAngleRadians) {
        double cosA = Math.cos(ocsAngleRadians);
        double sinA = Math.sin(ocsAngleRadians);
        return new Vector3D(
            cosA * ax.x() + sinA * ay.x(),
            cosA * ax.y() + sinA * ay.y(),
            cosA * ax.z() + sinA * ay.z()
        );
    }

    public double getWcsAngleDegrees(double ocsAngleDegrees) {
        double rad = Math.toRadians(ocsAngleDegrees);
        Vector3D dirWcs = ocsDirectionToWcs(rad);
        double angleRad = Math.atan2(dirWcs.y(), dirWcs.x());
        double angleDeg = Math.toDegrees(angleRad);
        return (angleDeg < 0.0) ? (angleDeg + 360.0) : angleDeg;
    }

    public boolean isFlippedIn2D() {
        return az.z() < 0;
    }

    public Vector3D getAx() { return ax; }
    public Vector3D getAy() { return ay; }
    public Vector3D getAz() { return az; }
}
