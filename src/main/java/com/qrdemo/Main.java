package com.qrdemo;

import boofcv.factory.fiducial.ConfigMicroQrCode;
import boofcv.factory.fiducial.FactoryFiducial;

import boofcv.alg.fiducial.microqr.MicroQrCode;
//import boofcv.alg.fiducial.microqr.MicroQrCodeEncoder;
//import boofcv.alg.fiducial.microqr.MicroQrCodeGenerator;
import boofcv.struct.image.GrayU8;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class Main {

    static final int SIZE = 17;

    // Pattern grids
    static int[][][] FREE_GRIDS = new int[8][17][2];
    static int[][][] HALF_BRICKS_GRIDS = new int[4][17][2];
    static int[][][] CORNER_GRIDS = new int[4][17][2];

    public static void main(String[] args) {
        System.out.println("=== Micro QR V4 Superposition Tool (Generate + Detect) ===\n");

        // Master Patterns
        fillPatterns();

        // Generate all permutations
        List<int[][][]> halfPerms = generateAllPermutations(HALF_BRICKS_GRIDS);
        List<int[][][]> cornerPerms = generateAllPermutations(CORNER_GRIDS);
        List<int[][][]> freePerms = generateAllPermutations(FREE_GRIDS);
        List<String> binaryPerms = createMasterList();

        // 1. Use AtomicLong for thread-safe counting
        AtomicLong count = new AtomicLong(0);
        int nThreads = Runtime.getRuntime().availableProcessors();
        System.out.println("Running on " + nThreads + " threads");

        long total = (long) halfPerms.size() * cornerPerms.size() * freePerms.size() * binaryPerms.size();

        System.out.println("Total combinations: " + total);
        System.out.println("Starting full search...\n");

        // 2. Use a bounded queue to prevent memory overflow (Backpressure)
        ExecutorService threadPool = new ThreadPoolExecutor(
                nThreads, nThreads, // Core/Max threads (adjust based on CPU)
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(10000), // Bound the queue
                new ThreadPoolExecutor.CallerRunsPolicy() // Backpressure: Submit thread runs task if pool full
        );

         Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[!] Interrupt detected (Ctrl+C).");
            threadPool.shutdownNow(); // Force l'arrêt des tâches en cours et vide la file
            try {
                if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));

        Semaphore semaphore = new Semaphore(nThreads * 2);

        int batchSize = 50000;
        List<Runnable> currentBatch = new ArrayList<>(batchSize);

        for (int[][][] halfPerm : halfPerms) {
            for (int[][][] cornerPerm : cornerPerms) {
                for (int[][][] freePerm : freePerms) {
                    for (String binaryPerm : binaryPerms) {

                        final long currentCount = count.incrementAndGet();

                        // Add task to batch
                        currentBatch.add(() -> generateAndTryScanning(currentCount, halfPerm, cornerPerm, freePerm, binaryPerm));

                        // when batch is full, we send it
                        if (currentBatch.size() >= batchSize) {
                            final List<Runnable> tasksToRun = new ArrayList<>(currentBatch);
                            currentBatch.clear();

                            try {
                                semaphore.acquire(); // Block if too much batches are waiting
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                System.err.println("Queue is full, waiting for space");
                                break;
                            }
                            
                            threadPool.submit(() -> {
                                try {
                                    for (Runnable r : tasksToRun) r.run();
                                } finally {
                                    semaphore.release();
                                }
                            });
                        }

                        if (currentCount % 50000 == 0) {
                            System.out.printf("Progress: %d / %d (%.2f%%)%n",
                                    currentCount, total, (currentCount * 100.0 / total));
                        }
                    }
                }
            }
        }

         if (!currentBatch.isEmpty()) {
            threadPool.submit(() -> currentBatch.forEach(Runnable::run));
        }

        threadPool.shutdown();
        try {
            // Wait for tasks to finish (maximum a day)
            if (!threadPool.awaitTermination(1, TimeUnit.DAYS)) {
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println("\nSearch complete.");
    }


    static void generateAndTryScanning(long number, int[][][] halfPerm, int[][][] cornerPerm, int[][][] freePerm,
            String format) {

        int[][][] order = generateTestOrder(halfPerm, cornerPerm, freePerm);
        int[][] grid = buildMicroQr(order, format);

        /*
         * int[][] test_grid = {
         * { 1, 1, 1, 1, 1, 1, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1 },
         * { 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 1, 1 },
         * { 1, 0, 1, 1, 1, 0, 1, 0, 0, 1, 1, 1, 1, 0, 0, 0, 1 },
         * { 1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0, 1, 0, 1, 0, 1, 1 },
         * { 1, 0, 1, 1, 1, 0, 1, 0, 1, 1, 1, 0, 1, 1, 1, 0, 1 },
         * { 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 1, 1, 0, 1, 0 },
         * { 1, 1, 1, 1, 1, 1, 1, 0, 0, 1, 1, 1, 1, 0, 0, 1, 1 },
         * { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 1, 0 },
         * { 1, 0, 1, 0, 0, 1, 0, 1, 0, 1, 1, 0, 1, 0, 0, 0, 1 },
         * { 0, 0, 1, 1, 1, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0 },
         * { 1, 1, 1, 1, 0, 1, 1, 0, 1, 1, 0, 1, 0, 0, 0, 0, 1 },
         * { 0, 1, 1, 1, 1, 0, 1, 0, 0, 1, 1, 1, 0, 0, 1, 0, 1 },
         * { 1, 0, 1, 1, 1, 0, 0, 1, 1, 1, 1, 0, 1, 0, 1, 0, 1 },
         * { 0, 1, 0, 1, 1, 1, 0, 1, 1, 0, 0, 1, 1, 1, 0, 1, 1 },
         * { 1, 1, 1, 0, 1, 1, 1, 0, 0, 1, 0, 0, 1, 0, 1, 1, 0 },
         * { 0, 0, 1, 0, 1, 1, 0, 1, 1, 0, 1, 0, 1, 1, 0, 0, 0 },
         * { 1, 1, 1, 1, 0, 0, 0, 0, 1, 0, 1, 0, 1, 0, 0, 0, 1 }
         * };
         */

        // Add 2-pixel quiet zone (required for Micro QR detection)
        int moduleSize = 3; // 3 pixels per module
        int quietZone = 2;
        int qrSize = SIZE * moduleSize;
        int totalSize = qrSize + (quietZone * 2 * moduleSize);

        GrayU8 gray = new GrayU8(totalSize, totalSize);

        // Fill with white
        for (int r = 0; r < totalSize; r++) {
            for (int c = 0; c < totalSize; c++) {
                gray.set(c, r, 255);
            }
        }

        // Scale up the QR code (3x3 per module)
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                int color = (grid[r][c] == 1) ? 0 : 255;
                for (int dr = 0; dr < moduleSize; dr++) {
                    for (int dc = 0; dc < moduleSize; dc++) {
                        gray.set(
                                c * moduleSize + dc + (quietZone * moduleSize),
                                r * moduleSize + dr + (quietZone * moduleSize),
                                color);
                    }
                }
            }
        }

        // Test: Generate a known good Micro QR with BoofCV and detect it

        /*
         * MicroQrCode qrff = new MicroQrCodeEncoder()
         * .setError(MicroQrCode.ErrorLevel.M)
         * .addAutomatic("dQw4w9WgXcQ")
         * .fixate();
         * 
         * GrayU8 testGood = MicroQrCodeGenerator.renderImage(3, 2, qrff);
         * saveGrayU8(testGood, "good_debug_with_quiet_zone.png");
         * 
         * 
         * System.out.println("Saved debug image: debug_test_grid.png");
         * saveGrayU8(gray, "debug_with_quiet_zone.png");
         */

        // saveGridToImage(grid, "debug_test_grid.png");
        // saveGrayU8(gray, "debug_with_quiet_zone.png");

        var detector = FactoryFiducial.microqr(new ConfigMicroQrCode(), GrayU8.class);
        detector.process(gray);
        List<MicroQrCode> detections = detector.getDetections();

        if (!detections.isEmpty()) {

            for (MicroQrCode qr : detections) {
                if (!qr.message.isEmpty() && qr.version == 4) {
                    System.out.println("\n FOUND VALID MICRO QR CODE!");
                    System.out.println("   Message: " + qr.message);
                    System.out.println("   Version: " + qr.version);
                    System.out.println("   Error Correction: " + qr.error.toString());

                    saveGridToImage(grid, "found_microqr" + number + ".png");
                    System.out.println("   Saved to: found_microqr" + number + ".png");
                }
            }
        }
    }

    static void fillPatterns() {
        // NW S
        HALF_BRICKS_GRIDS[0] = new int[][] {
                { 0, 1 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 },
                { 1, 0 }, { 0, 1 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }
        };

        // NW E
        CORNER_GRIDS[0] = new int[][] {
                { 0, 1 }, { 0, 0 }, { 1, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 1, 0 }, { 0, 0 },
                { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }
        };

        // NW N
        FREE_GRIDS[0] = new int[][] {
                { 0, 1 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 1, 0 }, { 1, 0 }, { 0, 0 },
                { 0, 0 }, { 1, 0 }, { 1, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }
        };

        // NW W
        FREE_GRIDS[1] = new int[][] {
                { 0, 1 }, { 0, 0 }, { 0, 0 }, { 1, 0 }, { 0, 0 }, { 0, 0 }, { 0, 1 }, { 0, 0 },
                { 0, 0 }, { 1, 0 }, { 0, 0 }, { 0, 1 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 1 }, { 0, 0 }
        };

        // NE S
        CORNER_GRIDS[1] = new int[][] {
                { 0, 1 }, { 0, 0 }, { 0, 0 }, { 1, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 },
                { 0, 0 }, { 1, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }
        };

        // NE E
        FREE_GRIDS[2] = new int[][] {
                { 0, 1 }, { 1, 0 }, { 0, 0 }, { 0, 1 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 1, 0 },
                { 1, 0 }, { 0, 0 }, { 0, 0 }, { 1, 0 }, { 1, 0 }, { 1, 0 }, { 0, 0 }, { 0, 1 }, { 0, 0 }
        };

        // NE N
        FREE_GRIDS[3] = new int[][] {
                { 0, 1 }, { 0, 0 }, { 1, 0 }, { 0, 0 }, { 1, 0 }, { 0, 0 }, { 0, 1 }, { 0, 0 },
                { 1, 0 }, { 0, 0 }, { 1, 0 }, { 0, 1 }, { 0, 0 }, { 0, 0 }, { 1, 0 }, { 0, 0 }, { 0, 0 }
        };

        // NE W
        HALF_BRICKS_GRIDS[1] = new int[][] {
                { 0, 1 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 1, 1 }, { 0, 0 },
                { 0, 1 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 1, 1 }, { 0, 0 }, { 0, 0 }, { 1, 0 }, { 0, 0 }
        };

        // SE S
        FREE_GRIDS[4] = new int[][] {
                { 0, 1 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 1 }, { 0, 0 }, { 1, 0 }, { 0, 0 },
                { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 1 }, { 0, 0 }, { 0, 0 }, { 0, 1 }, { 0, 0 }, { 0, 0 }
        };

        // SE E
        FREE_GRIDS[5] = new int[][] {
                { 0, 1 }, { 0, 0 }, { 0, 0 }, { 1, 0 }, { 1, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 },
                { 0, 0 }, { 1, 0 }, { 1, 0 }, { 0, 0 }, { 0, 0 }, { 1, 1 }, { 0, 0 }, { 0, 0 }, { 0, 0 }
        };

        // SE N
        HALF_BRICKS_GRIDS[2] = new int[][] {
                { 0, 1 }, { 0, 0 }, { 0, 1 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 },
                { 1, 0 }, { 1, 0 }, { 0, 1 }, { 1, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }
        };

        // SE W
        CORNER_GRIDS[2] = new int[][] {
                { 0, 1 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 1 }, { 0, 0 }, { 0, 0 },
                { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 1 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }
        };

        // SW S
        FREE_GRIDS[6] = new int[][] {
                { 0, 1 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 1, 0 }, { 0, 0 },
                { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 1 }, { 0, 1 }, { 0, 0 }, { 1, 0 }, { 0, 0 }
        };

        // SW E
        HALF_BRICKS_GRIDS[3] = new int[][] {
                { 0, 1 }, { 0, 0 }, { 0, 1 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 },
                { 1, 0 }, { 0, 0 }, { 1, 0 }, { 0, 1 }, { 0, 1 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }
        };

        // SW N
        CORNER_GRIDS[3] = new int[][] {
                { 0, 1 }, { 1, 0 }, { 0, 0 }, { 0, 0 }, { 1, 0 }, { 0, 0 }, { 1, 1 }, { 0, 0 },
                { 0, 0 }, { 0, 0 }, { 0, 1 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }
        };

        // SW W
        FREE_GRIDS[7] = new int[][] {
                { 0, 1 }, { 0, 0 }, { 0, 0 }, { 0, 1 }, { 0, 1 }, { 0, 0 }, { 1, 0 }, { 0, 0 },
                { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 1 }, { 0, 1 }, { 0, 0 }, { 1, 1 }, { 0, 0 }, { 0, 0 }
        };
    }

    static List<int[][][]> generateAllPermutations(int[][][] arr) {
        List<int[][][]> result = new ArrayList<>();
        generatePermutationsRecursive(arr, 0, result);
        return result;
    }

    static void generatePermutationsRecursive(int[][][] arr, int start, List<int[][][]> result) {
        if (start == arr.length - 1) {
            result.add(copyArray(arr));
            return;
        }

        for (int i = start; i < arr.length; i++) {
            swap(arr, start, i);
            generatePermutationsRecursive(arr, start + 1, result);
            swap(arr, start, i); // backtrack
        }
    }

    static void swap(int[][][] arr, int i, int j) {
        int[][] temp = arr[i];
        arr[i] = arr[j];
        arr[j] = temp;
    }

    static int[][][] copyArray(int[][][] arr) {
        int[][][] copy = new int[arr.length][17][2];
        for (int i = 0; i < arr.length; i++) {
            for (int j = 0; j < 17; j++) {
                copy[i][j][0] = arr[i][j][0];
                copy[i][j][1] = arr[i][j][1];
            }
        }
        return copy;
    }

    // ==================== BUILD MICRO QR ====================
    static int[][] buildMicroQr(int[][][] order, String formatBits) {
        int[][] grid = new int[SIZE][SIZE];

        // Place test patterns
        int offset = 9;
        for (int i = 0; i < 16; i++) {
            if (i < 8) { // Vertical
                for (int j = 0; j < 17; j++) {
                    if (grid[j][offset] == 0 && order[i][j][0] == 1)
                        grid[j][offset] = 1;
                    if (grid[j][offset + 1] == 0 && order[i][j][1] == 1)
                        grid[j][offset + 1] = 1;
                }
            } else { // Horizontal
                for (int j = 0; j < 17; j++) {
                    if (grid[offset][j] == 0 && order[i][j][0] == 1)
                        grid[offset][j] = 1;
                    if (grid[offset + 1][j] == 0 && order[i][j][1] == 1)
                        grid[offset + 1][j] = 1;
                }
            }
            if (i % 2 != 0) {
                offset += 2;
                if (offset >= 17)
                    offset = 9;
            }
        }

        // Place format bits
        grid[0][8] = 1;
        grid[8][0] = 1;
        for (int i = 0; i < 15; i++) {
            if (i < 7)
                grid[i + 1][8] = Character.getNumericValue(formatBits.charAt(14 - i));
            else
                grid[8][15 - i] = Character.getNumericValue(formatBits.charAt(14 - i));
        }

        // Place finder pattern
        for (int r = 0; r < 7; r++) {
            for (int c = 0; c < 7; c++) {
                if (r == 0 || r == 6 || c == 0 || c == 6)
                    grid[r][c] = 1;
                else if (r >= 2 && r <= 4 && c >= 2 && c <= 4)
                    grid[r][c] = 1;
                else
                    grid[r][c] = 0;
            }
        }

        return grid;
    }

    static int[][][] generateTestOrder(int[][][] halfPerm, int[][][] cornerPerm, int[][][] freePerm) {
        int[][][] order = new int[16][17][2];
        order[0] = freePerm[0];
        order[1] = freePerm[1];
        order[2] = freePerm[2];
        order[3] = freePerm[3];
        order[4] = halfPerm[0];
        order[5] = halfPerm[1];
        order[6] = cornerPerm[0];
        order[7] = cornerPerm[1];
        order[8] = freePerm[4];
        order[9] = freePerm[5];
        order[10] = freePerm[6];
        order[11] = freePerm[7];
        order[12] = halfPerm[2];
        order[13] = halfPerm[3];
        order[14] = cornerPerm[2];
        order[15] = cornerPerm[3];
        return order;
    }

    // ==================== CREATE MASTER LIST (Format Bits with XOR)
    // ====================
    static List<String> createMasterList() {
        List<String> masterList = new ArrayList<>();

        // Generate all 12-bit combinations
        for (int i = 0; i < (1 << 12); i++) {
            String bits12 = String.format("%12s", Integer.toBinaryString(i)).replace(' ', '0');

            // 101 + 12 bits
            masterList.add("101" + bits12);
            // 110 + 12 bits
            masterList.add("110" + bits12);
        }

        // XOR with mask "100010001000101"
        String mask = "100010001000101";
        List<String> resultList = new ArrayList<>();

        for (String binaryStr : masterList) {
            StringBuilder xored = new StringBuilder();
            for (int j = 0; j < 15; j++) {
                int a = Character.getNumericValue(binaryStr.charAt(j));
                int b = Character.getNumericValue(mask.charAt(j));
                xored.append(a ^ b);
            }
            resultList.add(xored.toString());
        }

        return resultList;
    }

    // ==================== SAVE GRID TO IMAGE ====================
    static void saveGridToImage(int[][] grid, String filename) {
        try {
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                    SIZE * 20, SIZE * 20, java.awt.image.BufferedImage.TYPE_INT_RGB);

            java.awt.Graphics2D g = img.createGraphics();

            for (int r = 0; r < SIZE; r++) {
                for (int c = 0; c < SIZE; c++) {
                    int color = (grid[r][c] == 1) ? 0x000000 : 0xFFFFFF;
                    g.setColor(new java.awt.Color(color));
                    g.fillRect(c * 20, r * 20, 20, 20);
                }
            }

            g.dispose();
            javax.imageio.ImageIO.write(img, "png", new java.io.File(filename));

        } catch (Exception e) {
            System.out.println("Error saving image: " + e.getMessage());
        }
    }

    static void saveGrayU8(GrayU8 gray, String filename) {
        try {
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                    gray.getWidth(), gray.getHeight(), java.awt.image.BufferedImage.TYPE_BYTE_GRAY);

            for (int y = 0; y < gray.getHeight(); y++) {
                for (int x = 0; x < gray.getWidth(); x++) {
                    int val = gray.get(x, y);
                    int rgb = (val << 16) | (val << 8) | val;
                    img.setRGB(x, y, rgb);
                }
            }

            javax.imageio.ImageIO.write(img, "png", new java.io.File("detected/" + filename));
            System.out.println("Saved: " + filename);

        } catch (Exception e) {
            System.out.println("Error saving: " + e.getMessage());
        }
    }

}