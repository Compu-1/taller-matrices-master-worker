package co.icesi.matrix.client;

import MatrixCompute.MasterServicePrx;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.ObjectPrx;
import com.zeroc.Ice.Util;

import java.util.Random;

/**
 * ClientMain
 * 
 * Punto de entrada del nodo Cliente.
 * Responsabilidades:
 *  1. Se conecta al Master vía ZeroC Ice.
 *  2. Ejecuta una prueba de validación funcional con matrices pequeñas (4x4).
 *  3. Ejecuta un Benchmark de rendimiento con matrices grandes (NxN):
 *     - Mide el tiempo secuencial monohilo en local (T_sec).
 *     - Mide el tiempo distribuido con el cluster Master-Worker (T_dist).
 *     - Valida la exactitud numérica entre ambas matrices resultantes.
 *     - Calcula y muestra la Aceleración (Speedup S) y Eficiencia (E).
 */
public class ClientMain {

    public static void main(String[] args) {
        String masterHost = "localhost";
        int masterPort = 10000;
        int benchmarkSize = 500; // Tamaño por defecto para el benchmark

        if (args.length >= 1) {
            masterHost = args[0];
        }
        if (args.length >= 2) {
            try {
                masterPort = Integer.parseInt(args[1]);
            } catch (NumberFormatException ignored) {}
        }
        if (args.length >= 3) {
            try {
                benchmarkSize = Integer.parseInt(args[2]);
            } catch (NumberFormatException ignored) {}
        }

        System.out.println("=============================================================");
        System.out.println("   CLIENTE DE MULTIPLICACIÓN DISTRIBUIDA DE MATRICES (ICE)");
        System.out.printf("   Destino Master: %s:%d | Dimensión Benchmark: %dx%d%n", masterHost, masterPort, benchmarkSize, benchmarkSize);
        System.out.println("=============================================================\n");

        try (Communicator communicator = Util.initialize(args)) {
            String masterEndpoint = String.format("MasterService:tcp -h %s -p %d", masterHost, masterPort);
            ObjectPrx base = communicator.stringToProxy(masterEndpoint);
            MasterServicePrx master = MasterServicePrx.checkedCast(base);

            if (master == null) {
                System.err.println("[ERROR] No se pudo conectar al Master en " + masterEndpoint);
                return;
            }

            int activeWorkers = master.getWorkerCount();
            System.out.printf("[CLIENTE] Conexión establecida. Workers activos en el clúster: %d%n%n", activeWorkers);

            if (activeWorkers == 0) {
                System.err.println("[ADVERTENCIA] No hay workers registrados en el Master.");
                System.err.println("Inicie al menos un worker con: ./gradlew :worker:runWorker");
                return;
            }

            // =========================================================================
            // FASE 1: PRUEBA DE EXACTITUD CON MATRICES PEQUEÑAS (4x4)
            // =========================================================================
            System.out.println(">>> FASE 1: Verificación de Exactitud con Matrices Pequeñas (4x4)");
            double[][] smallA = {
                {1.0, 2.0, 3.0, 4.0},
                {5.0, 6.0, 7.0, 8.0},
                {2.0, 0.0, 1.0, 3.0},
                {4.0, 1.0, 2.0, 1.0}
            };
            double[][] smallB = {
                {2.0, 0.0, 1.0, 2.0},
                {1.0, 3.0, 2.0, 0.0},
                {0.0, 1.0, 4.0, 1.0},
                {3.0, 2.0, 0.0, 1.0}
            };

            System.out.println("[CLIENTE] Enviando matrices pequeñas al Master...");
            double[][] smallDist = master.multiply(smallA, smallB);
            double[][] smallLocal = multiplyLocal(smallA, smallB);

            System.out.println("\nMatriz Resultante C (Distribuida 4x4):");
            printMatrix(smallDist);

            boolean match = compareMatrices(smallLocal, smallDist, 1e-6);
            System.out.printf("[CLIENTE] ¿Coincidencia exacta con cálculo local? %s%n%n", (match ? "SI (CORRECTO)" : "NO (ERROR)"));

            // =========================================================================
            // FASE 2: BENCHMARK DE RENDIMIENTO (NxN)
            // =========================================================================
            System.out.printf(">>> FASE 2: Benchmark de Rendimiento Masivo (%dx%d)%n", benchmarkSize, benchmarkSize);
            System.out.println("[CLIENTE] Generando matrices pseudo-aleatorias...");
            double[][] bigA = generateRandomMatrix(benchmarkSize, benchmarkSize);
            double[][] bigB = generateRandomMatrix(benchmarkSize, benchmarkSize);

            // 1. Cómputo Secuencial Local
            System.out.print("[CLIENTE] Ejecutando multiplicación secuencial monohilo local... ");
            long t0Sec = System.currentTimeMillis();
            double[][] resultLocal = multiplyLocal(bigA, bigB);
            long tSec = System.currentTimeMillis() - t0Sec;
            System.out.printf("Listo en %d ms.%n", tSec);

            // 2. Cómputo Distribuido con Master y Workers
            System.out.printf("[CLIENTE] Despachando a clúster distribuido con %d worker(s)... ", activeWorkers);
            long t0Dist = System.currentTimeMillis();
            double[][] resultDist = master.multiply(bigA, bigB);
            long tDist = System.currentTimeMillis() - t0Dist;
            System.out.printf("Listo en %d ms.%n", tDist);

            // 3. Validación Numérica
            boolean validBenchmark = compareMatrices(resultLocal, resultDist, 1e-5);

            // 4. Cálculo de Métricas de Escalabilidad
            double speedup = (double) tSec / (double) Math.max(1, tDist);
            double efficiency = (speedup / activeWorkers) * 100.0;

            System.out.println("\n=============================================================");
            System.out.println("                 RESULTADOS DEL BENCHMARK");
            System.out.println("=============================================================");
            System.out.printf("  Dimensión de Matrices:      %d x %d%n", benchmarkSize, benchmarkSize);
            System.out.printf("  Operaciones Aritméticas:    ~%.2e ops%n", 2.0 * Math.pow(benchmarkSize, 3));
            System.out.printf("  Workers Activos (p):        %d%n", activeWorkers);
            System.out.printf("  Tiempo Secuencial (T_sec):  %d ms%n", tSec);
            System.out.printf("  Tiempo Distribuido (T_dist):%d ms%n", tDist);
            System.out.printf("  Aceleración (Speedup S):    %.2fx%n", speedup);
            System.out.printf("  Eficiencia Paralela (E):    %.1f%%%n", efficiency);
            System.out.printf("  Validación Numérica:        %s%n", (validBenchmark ? "EXITOSA (Delta < 1e-5)" : "FALLIDA"));
            System.out.println("=============================================================\n");

        } catch (Exception e) {
            System.err.println("[ERROR] Excepción en Cliente: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static double[][] generateRandomMatrix(int rows, int cols) {
        Random rand = new Random(42); // Semilla fija para reproducibilidad
        double[][] m = new double[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                m[i][j] = rand.nextDouble() * 10.0;
            }
        }
        return m;
    }

    private static double[][] multiplyLocal(double[][] A, double[][] B) {
        int rA = A.length;
        int cA = A[0].length;
        int cB = B[0].length;
        double[][] C = new double[rA][cB];
        for (int i = 0; i < rA; i++) {
            for (int j = 0; j < cB; j++) {
                double s = 0;
                for (int k = 0; k < cA; k++) {
                    s += A[i][k] * B[k][j];
                }
                C[i][j] = s;
            }
        }
        return C;
    }

    private static boolean compareMatrices(double[][] A, double[][] B, double epsilon) {
        if (A.length != B.length || A[0].length != B[0].length) return false;
        for (int i = 0; i < A.length; i++) {
            for (int j = 0; j < A[0].length; j++) {
                if (Math.abs(A[i][j] - B[i][j]) > epsilon) return false;
            }
        }
        return true;
    }

    private static void printMatrix(double[][] M) {
        for (int i = 0; i < Math.min(M.length, 6); i++) {
            System.out.print("  [ ");
            for (int j = 0; j < Math.min(M[0].length, 6); j++) {
                System.out.printf("%6.1f ", M[i][j]);
            }
            if (M[0].length > 6) System.out.print("... ");
            System.out.println("]");
        }
    }
}
