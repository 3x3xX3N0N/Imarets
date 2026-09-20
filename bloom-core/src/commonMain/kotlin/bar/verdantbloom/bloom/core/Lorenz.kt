package bar.verdantbloom.bloom.core

import kotlin.math.sqrt

/**
 * Plain Lorenz point integrated with fixed-step RK4 using ONLY + - * (SPEC 3.2): no division, no
 * transcendental functions, so every IEEE-754 engine produces the same bits.
 * Used for the visitor copy and for the burn-in of each hourly initial condition.
 */
internal class LorenzPoint(var x: Double, var y: Double, var z: Double) {
    fun set(nx: Double, ny: Double, nz: Double) {
        x = nx; y = ny; z = nz
    }

    fun copyFrom(o: LorenzPoint) {
        x = o.x; y = o.y; z = o.z
    }

    fun step(sigma: Double, rho: Double, beta: Double, dt: Double) {
        val h = dt * 0.5
        val k1x = sigma * (y - x)
        val k1y = x * (rho - z) - y
        val k1z = x * y - beta * z
        var px = x + h * k1x
        var py = y + h * k1y
        var pz = z + h * k1z
        val k2x = sigma * (py - px)
        val k2y = px * (rho - pz) - py
        val k2z = px * py - beta * pz
        px = x + h * k2x
        py = y + h * k2y
        pz = z + h * k2z
        val k3x = sigma * (py - px)
        val k3y = px * (rho - pz) - py
        val k3z = px * py - beta * pz
        px = x + dt * k3x
        py = y + dt * k3y
        pz = z + dt * k3z
        val k4x = sigma * (py - px)
        val k4y = px * (rho - pz) - py
        val k4z = px * py - beta * pz
        val w = dt * SIXTH
        x += w * (k1x + 2.0 * k2x + 2.0 * k3x + k4x)
        y += w * (k1y + 2.0 * k2y + 2.0 * k3y + k4y)
        z += w * (k1z + 2.0 * k2z + 2.0 * k3z + k4z)
    }

    companion object {
        /** 1/6 as a constant so the integrator multiplies instead of dividing. */
        const val SIXTH: Double = 1.0 / 6.0
    }
}

/**
 * Lorenz state + world quaternion (scalar first) as ONE 7-dimensional RK4 system.
 * Quaternion kinematics: q' = 0.5 * (0, omega) * q with omega = scale * (x, y, z - zCentre).
 * After each step q is re-normalised with one sqrt and one division, both exactly rounded IEEE
 * operations (SPEC 3.2 allows sqrt for quaternion normalisation).
 */
internal class LorenzQuat {
    var x = 0.0
    var y = 0.0
    var z = 0.0
    var qw = 1.0
    var qx = 0.0
    var qy = 0.0
    var qz = 0.0

    // derivative scratch
    private var dx = 0.0
    private var dy = 0.0
    private var dz = 0.0
    private var dqw = 0.0
    private var dqx = 0.0
    private var dqy = 0.0
    private var dqz = 0.0

    fun copyFrom(o: LorenzQuat) {
        x = o.x; y = o.y; z = o.z; qw = o.qw; qx = o.qx; qy = o.qy; qz = o.qz
    }

    private fun deriv(
        px: Double, py: Double, pz: Double, pw: Double, pi: Double, pj: Double, pk: Double,
        sigma: Double, rho: Double, beta: Double, omegaScale: Double, zCentre: Double,
    ) {
        dx = sigma * (py - px)
        dy = px * (rho - pz) - py
        dz = px * py - beta * pz
        val ox = omegaScale * px * 0.5
        val oy = omegaScale * py * 0.5
        val oz = omegaScale * (pz - zCentre) * 0.5
        dqw = 0.0 - (ox * pi + oy * pj + oz * pk)
        dqx = ox * pw + oy * pk - oz * pj
        dqy = oy * pw + oz * pi - ox * pk
        dqz = oz * pw + ox * pj - oy * pi
    }

    fun step(sigma: Double, rho: Double, beta: Double, dt: Double, omegaScale: Double, zCentre: Double) {
        val h = dt * 0.5
        deriv(x, y, z, qw, qx, qy, qz, sigma, rho, beta, omegaScale, zCentre)
        val a0 = dx; val a1 = dy; val a2 = dz; val a3 = dqw; val a4 = dqx; val a5 = dqy; val a6 = dqz
        deriv(x + h * a0, y + h * a1, z + h * a2, qw + h * a3, qx + h * a4, qy + h * a5, qz + h * a6,
            sigma, rho, beta, omegaScale, zCentre)
        val b0 = dx; val b1 = dy; val b2 = dz; val b3 = dqw; val b4 = dqx; val b5 = dqy; val b6 = dqz
        deriv(x + h * b0, y + h * b1, z + h * b2, qw + h * b3, qx + h * b4, qy + h * b5, qz + h * b6,
            sigma, rho, beta, omegaScale, zCentre)
        val c0 = dx; val c1 = dy; val c2 = dz; val c3 = dqw; val c4 = dqx; val c5 = dqy; val c6 = dqz
        deriv(x + dt * c0, y + dt * c1, z + dt * c2, qw + dt * c3, qx + dt * c4, qy + dt * c5, qz + dt * c6,
            sigma, rho, beta, omegaScale, zCentre)
        val w = dt * LorenzPoint.SIXTH
        x += w * (a0 + 2.0 * b0 + 2.0 * c0 + dx)
        y += w * (a1 + 2.0 * b1 + 2.0 * c1 + dy)
        z += w * (a2 + 2.0 * b2 + 2.0 * c2 + dz)
        qw += w * (a3 + 2.0 * b3 + 2.0 * c3 + dqw)
        qx += w * (a4 + 2.0 * b4 + 2.0 * c4 + dqx)
        qy += w * (a5 + 2.0 * b5 + 2.0 * c5 + dqy)
        qz += w * (a6 + 2.0 * b6 + 2.0 * c6 + dqz)
        normalise()
    }

    fun normalise() {
        val inv = 1.0 / sqrt(qw * qw + qx * qx + qy * qy + qz * qz)
        qw *= inv; qx *= inv; qy *= inv; qz *= inv
    }
}
