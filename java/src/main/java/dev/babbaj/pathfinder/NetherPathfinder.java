package dev.babbaj.pathfinder;

import org.tukaani.xz.XZInputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class NetherPathfinder {

    // 光线追踪器如何处理未实际观察到的区块。
    // 当使用区块生成时，调用者必须同步读缓存和可能改变缓存的操作。
    // JNI 代码内部不做任何同步。
    public static int CACHE_MISS_GENERATE = 0; // 生成区块
    public static int CACHE_MISS_AIR = 1;      // 视为空气
    public static int CACHE_MISS_SOLID = 2;    // 视为实心

    public static int DIMENSION_OVERWORLD = 0; // 主世界
    public static int DIMENSION_NETHER = 1;    // 下界
    public static int DIMENSION_END = 2;       // 末地

    // 传入 true 以使用自定义区块分配器，它可以减少内存使用并可能更快。
    // false 则直接使用 new/delete。
    // 仅在 4k 内存页的系统上受支持。
    public static native long newContext(long seed, String baritoneCacheDirCanBeNull, int dimension, int maxHeight, boolean allocator);
    public static native void freeContext(long pointer);

    /*
    来源于 BlockStateContainer 中的方法：
    private static int getIndex(int x, int y, int z)
    {
        return y << 8 | z << 4 | x;
    }

    chunkX 和 chunkZ 是区块坐标，并非方块坐标。
    */
    public static native void insertChunkData(long context, int chunkX, int chunkZ, boolean[] data);

    public static native long allocateAndInsertChunk(long context, int x, int z);

    // 不要写入此方法返回的区块数据
    public static native long getChunkOrDefault(long context, int x, int z, boolean solid);

    public static native long getChunk(long context, int x, int z);

    // 如果区块存在且更改完成，返回 true
    public static native boolean setChunkState(long context, int x, int z, boolean fromJava);

    public static native boolean hasChunkFromJava(long context, int x, int z);

    public static native void cullFarChunks(long context, int chunkX, int chunkZ, int maxDistanceBlocks);

    public static native PathSegment pathFind(long context, int x1, int y1, int z1, int x2, int y2, int z2, boolean atLeastX4, boolean refine, int failTimeoutInMillis, boolean defaultAirElseGenerate, double fakeChunkCost);

    private static native void raytrace0(long context, int fakeChunkMode, int inputs, double[] start, double[] end, boolean[] hitsOut, double[] hitPosOutCanBeNull);

    public static void raytrace(long context, int fakeChunkMode, int inputs, double[] start, double[] end, boolean[] hitsOut, double[] hitPosOutCanBeNull) {
        if (start.length < (inputs * 3) || end.length < (inputs * 3) || hitsOut.length < inputs || (hitPosOutCanBeNull != null && hitPosOutCanBeNull.length < (inputs * 3))) {
            throw new IllegalArgumentException("数组长度有误，请检查参数");
        }
        raytrace0(context, fakeChunkMode, inputs, start, end, hitsOut, hitPosOutCanBeNull);
    }

    private static native int isVisibleMulti0(long context, int fakeChunkMode, int inputs, double[] start, double[] end, boolean anyIfTrueElseAll);

    public static int isVisibleMulti(long context, int fakeChunkMode, int inputs, double[] start, double[] end, boolean anyIfTrueElseAll) {
        if (start.length < (inputs * 3) || end.length < (inputs * 3)) {
            throw new IllegalArgumentException("数组长度有误，请检查参数");
        }
        return isVisibleMulti0(context, fakeChunkMode, inputs, start, end, anyIfTrueElseAll);
    }

    public static native boolean isVisible(long context, int fakeChunkMode, double x1, double y1, double z1, double x2, double y2, double z2);

    public static native boolean cancel(long context);

    static native long getX2Index();

    // TODO: 方便使用者计算完整路径的工具方法

    private static final boolean IS_LOADED;

    /**
     * 当前系统是否支持原生加速库。
     */
    public static boolean isThisSystemSupported() {
        return IS_LOADED;
    }

    private static String getNativeLibName() {
        final int bits = Integer.parseInt(System.getProperty("sun.arch.data.model"));
        if (bits != 64) {
            throw new UnsupportedOperationException("不支持的架构（需要64位）");
        }

        final String osName = System.getProperty("os.name").toLowerCase();
        final String osArch = System.getProperty("os.arch").toLowerCase();

        final String arch;
        if (osArch.contains("arm") || osArch.contains("aarch64")) {
            arch = "aarch64";
        } else if (osArch.equals("x86_64") || osArch.equals("amd64")) {
            arch = "x86_64";
        } else {
            throw new UnsupportedOperationException("不支持的架构: " + osArch);
        }

        if (osName.contains("linux")) {
            return "libnether_pathfinder-" + arch + ".so";
        } else if (osName.contains("windows")) {
            return "nether_pathfinder-" + arch + ".dll";
        } else if (osName.contains("mac")) {
            return "libnether_pathfinder-" + arch + ".dylib";
        } else {
            throw new UnsupportedOperationException("不支持的操作系统: " + osName);
        }
    }

    private static byte[] getNativeLib(final String libName) throws IOException {
        try (
            final InputStream nativesRaw = NetherPathfinder.class.getClassLoader().getResourceAsStream("natives.zip.xz");
            final XZInputStream nativesZx = new XZInputStream(nativesRaw);
            final ZipInputStream nativesZip = new ZipInputStream(nativesZx)
        ) {
            ZipEntry entry;
            while ((entry = nativesZip.getNextEntry()) != null) {
                if (!entry.getName().equals(libName)) {
                    continue;
                }

                final ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
                final byte[] buffer = new byte[4096];

                int read;
                while ((read = nativesZip.read(buffer)) != -1) {
                    byteStream.write(buffer, 0, read);
                }

                return byteStream.toByteArray();
            }
        }
        throw new NullPointerException("未找到寻路库: " + libName);
    }

    private static void tryLoadLibrary() throws IOException {
        final String libName = getNativeLibName();
        final byte[] libBytes = getNativeLib(libName);

        final String[] split = libName.split("\\.");
        final Path tempFile = Files.createTempFile(split[0], "." + split[1]);
        System.out.println("[下界寻路] 已创建临时文件: " + tempFile.toAbsolutePath());

        try {
            Files.write(tempFile, libBytes);
            System.load(tempFile.toAbsolutePath().toString());
        } finally {
            try {
                Files.delete(tempFile);
            } catch (IOException ignored) {
                System.err.println("[下界寻路] 无法删除临时文件");
            }
            if (!tempFile.toFile().delete()) {
                tempFile.toFile().deleteOnExit();
            }
        }
    }

    static {
        boolean loaded = false;
        try {
            // 始终尝试加载原生库，Android 环境下也会尝试
            tryLoadLibrary();
            System.out.println("[下界寻路] 已成功加载原生共享库");
            loaded = true;
        } catch (Throwable e) {
            // 加载失败时仅输出简短的错误原因，不打印完整堆栈，避免刷屏
            System.err.println("[下界寻路] 加载原生共享库失败，回退至纯 Java 实现。原因: " + e.toString());
        }
        IS_LOADED = loaded;
    }
}