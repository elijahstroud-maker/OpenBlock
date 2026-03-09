package com.openblock.terrain;

/**
 * Simplex noise implementation.
 * Based on Stefan Gustavson's public-domain Java implementation.
 */
public class SimplexNoise {

    private final int[] perm = new int[512];

    private static final int[][] GRAD3 = {
        {1,1,0},{-1,1,0},{1,-1,0},{-1,-1,0},
        {1,0,1},{-1,0,1},{1,0,-1},{-1,0,-1},
        {0,1,1},{0,-1,1},{0,1,-1},{0,-1,-1}
    };

    public SimplexNoise(long seed) {
        // Build permutation table from seed
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;
        // Shuffle using LCG seeded with the given seed
        long s = seed;
        for (int i = 255; i > 0; i--) {
            s = s * 6364136223846793005L + 1442695040888963407L;
            int j = (int) ((s >>> 33) % (i + 1));
            if (j < 0) j += (i + 1);
            int tmp = p[i]; p[i] = p[j]; p[j] = tmp;
        }
        for (int i = 0; i < 512; i++) perm[i] = p[i & 255];
    }

    private static double dot(int[] g, double x, double y) {
        return g[0] * x + g[1] * y;
    }

    /** 2D simplex noise in range approximately [-1, 1]. */
    public double noise(double xin, double yin) {
        double n0, n1, n2;

        final double F2 = 0.5 * (Math.sqrt(3.0) - 1.0);
        double s = (xin + yin) * F2;
        int i = fastFloor(xin + s);
        int j = fastFloor(yin + s);

        final double G2 = (3.0 - Math.sqrt(3.0)) / 6.0;
        double t = (i + j) * G2;
        double X0 = i - t;
        double Y0 = j - t;
        double x0 = xin - X0;
        double y0 = yin - Y0;

        int i1, j1;
        if (x0 > y0) { i1 = 1; j1 = 0; }
        else          { i1 = 0; j1 = 1; }

        double x1 = x0 - i1 + G2;
        double y1 = y0 - j1 + G2;
        double x2 = x0 - 1.0 + 2.0 * G2;
        double y2 = y0 - 1.0 + 2.0 * G2;

        int ii = i & 255;
        int jj = j & 255;
        int gi0 = perm[ii +      perm[jj     ]] % 12;
        int gi1 = perm[ii + i1 + perm[jj + j1]] % 12;
        int gi2 = perm[ii +  1 + perm[jj +  1]] % 12;

        double t0 = 0.5 - x0*x0 - y0*y0;
        if (t0 < 0) n0 = 0.0;
        else { t0 *= t0; n0 = t0 * t0 * dot(GRAD3[gi0], x0, y0); }

        double t1 = 0.5 - x1*x1 - y1*y1;
        if (t1 < 0) n1 = 0.0;
        else { t1 *= t1; n1 = t1 * t1 * dot(GRAD3[gi1], x1, y1); }

        double t2 = 0.5 - x2*x2 - y2*y2;
        if (t2 < 0) n2 = 0.0;
        else { t2 *= t2; n2 = t2 * t2 * dot(GRAD3[gi2], x2, y2); }

        return 70.0 * (n0 + n1 + n2);
    }

    /**
     * Fractional Brownian Motion: sums {@code octaves} layers of noise.
     * @param freq  base frequency
     * @param gain  amplitude multiplier per octave (lacunarity fixed at 2.0)
     */
    public double octaves(double x, double z, int octaves, double freq, double gain) {
        double sum = 0, amp = 1, maxAmp = 0;
        for (int i = 0; i < octaves; i++) {
            sum   += noise(x * freq, z * freq) * amp;
            maxAmp += amp;
            amp   *= gain;
            freq  *= 2.0;
        }
        return sum / maxAmp; // normalised to [-1, 1]
    }

    /** Ridged noise: 1 - |octaves(...)| mapped to [0,1]. Good for mountain ridges. */
    public double ridged(double x, double z, int octaves, double freq, double gain) {
        double sum = 0, amp = 1, maxAmp = 0;
        for (int i = 0; i < octaves; i++) {
            sum   += (1.0 - Math.abs(noise(x * freq, z * freq))) * amp;
            maxAmp += amp;
            amp   *= gain;
            freq  *= 2.0;
        }
        return sum / maxAmp; // [0, 1]
    }

    private static int fastFloor(double x) {
        int xi = (int) x;
        return x < xi ? xi - 1 : xi;
    }
}
