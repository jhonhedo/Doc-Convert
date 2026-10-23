package com.converter.dxf.math;

import java.util.Objects;

public final class Vector3D {
    public static final Vector3D ZERO = new Vector3D(0, 0, 0);
    public static final Vector3D Z_AXIS = new Vector3D(0, 0, 1);
    public static final Vector3D Y_AXIS = new Vector3D(0, 1, 0);
    public static final Vector3D X_AXIS = new Vector3D(1, 0, 0);

    public final double x;
    public final double y;
    public final double z;

    public Vector3D(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public Vector3D normalize() {
        double len = length();
        if (len < 1e-12) {
            return Z_AXIS;
        }
        return new Vector3D(x / len, y / len, z / len);
    }

    public Vector3D cross(Vector3D o) {
        return new Vector3D(
            this.y * o.z - this.z * o.y,
            this.z * o.x - this.x * o.z,
            this.x * o.y - this.y * o.x
        );
    }

    public double dot(Vector3D o) {
        return this.x * o.x + this.y * o.y + this.z * o.z;
    }

    public Vector3D add(Vector3D o) {
        return new Vector3D(this.x + o.x, this.y + o.y, this.z + o.z);
    }

    public Vector3D subtract(Vector3D o) {
        return new Vector3D(this.x - o.x, this.y - o.y, this.z - o.z);
    }

    public Vector3D multiply(double s) {
        return new Vector3D(this.x * s, this.y * s, this.z * s);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Vector3D)) return false;
        Vector3D vector3D = (Vector3D) o;
        return Double.compare(vector3D.x, x) == 0 &&
               Double.compare(vector3D.y, y) == 0 &&
               Double.compare(vector3D.z, z) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y, z);
    }

    @Override
    public String toString() {
        return "Vector3D[" + "x=" + x + ", y=" + y + ", z=" + z + ']';
    }
}
