package dev.babbaj.pathfinder;

import sun.misc.Unsafe;

import java.lang.reflect.Field;

@SuppressWarnings("sunapi")
public class Octree {

    // 标记八叉树加速是否可用
    private static final boolean IS_AVAILABLE;

    private static final Unsafe UNSAFE;
    private static final long X2_INDEX_PTR;

    static {
        Unsafe unsafe = null;
        long x2IndexPtr = 0;
        boolean success = false;

        try {
            // 尝试获取 Unsafe 实例
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = (Unsafe) f.get(null);

            // 尝试获取原生寻路库中的 X2 索引指针
            x2IndexPtr = NetherPathfinder.getX2Index();
            success = true;
        } catch (Throwable ex) {
            // 任何失败（如 Unsafe 不可用、原生库未加载）都会导致回退
            System.err.println("[八叉树] 初始化失败，将回退至纯 Java 数据结构。原因: " + ex.toString());
        }

        UNSAFE = unsafe;
        X2_INDEX_PTR = x2IndexPtr;
        IS_AVAILABLE = success;
    }

    /**
     * 八叉树加速是否可用。
     */
    public static boolean isAvailable() {
        return IS_AVAILABLE;
    }

    // 各级子单元大小
    public static final int SIZEOF_X2 = 1;
    public static final int SIZEOF_X4 = SIZEOF_X2 * 8;
    public static final int SIZEOF_X8 = SIZEOF_X4 * 8;
    public static final int SIZEOF_X16 = SIZEOF_X8 * 8;
    public static final int SIZEOF_CHUNK = SIZEOF_X16 * 24;

    // 根据 Y 坐标计算所在 X16 索引
    public static int x16Index(int y) {
        return y >> 4;
    }

    // 根据方块的区块内坐标（0~15）计算其所在的 X8 索引
    public static int x8Index(int x, int y, int z) {
        return ((x & 8) >> 1) | ((y & 8) >> 2) | ((z & 8) >> 3);
    }

    // X4 索引
    public static int x4Index(int x, int y, int z) {
        return ((x & 4)) | ((y & 4) >> 1) | ((z & 4) >> 2);
    }

    // X2 索引
    public static int x2Index(int x, int y, int z) {
        return ((x & 2) << 1) | ((y & 2)) | ((z & 2) >> 1);
    }

    // 最低位索引（0 或 1 为 bit 位）
    public static int bitIndex(int x, int y, int z) {
        return ((x & 1) << 2) | ((y & 1) << 1) | ((z & 1));
    }

    // 根据区块指针和方块坐标，获取对应的 X2 指针
    private static long getX2Ptr(long chunk, int x, int y, int z) {
        // 偏移计算说明：
        // 数据结构为 std::array<std::array<std::array<uint16_t, 192>, 8>, 8>
        // auto x2Idx = X2_INDEX[x/2][z/2][y/2];
        // 一个 uint16_t 数组大小为 384 字节，8 个为一组共 3072 字节
        final int indexOffset = (3072 * (x / 2)) + (384 * (z / 2)) + (2 * (y / 2));
        // 从 X2_INDEX 表中读取 uint16_t 偏移值（可能为负，因此作为有符号 short 返回）
        final int offset = UNSAFE.getShort(X2_INDEX_PTR + indexOffset);
        return chunk + offset;
    }

    // 设置方块（必须使用区块相对坐标 0~15）
    public static void setBlock(long pointer, int x, int y, int z, boolean solid) {
        if (!IS_AVAILABLE) {
            throw new UnsupportedOperationException("八叉树加速不可用（原生库未加载），请使用纯 Java 路径或等待适配");
        }
        final long x2Ptr = getX2Ptr(pointer, x, y, z);
        final int bit = bitIndex(x, y, z);
        byte x2 = UNSAFE.getByte(x2Ptr);

        if (solid) {
            x2 |= (1 << bit);
        } else {
            x2 &= ~(1 << bit);
        }
        UNSAFE.putByte(x2Ptr, x2);
    }

    // 初始化方块（直接置位，可能无分支）
    public static void initBlock(long pointer, int x, int y, int z, boolean solid) {
        if (!IS_AVAILABLE) {
            throw new UnsupportedOperationException("八叉树加速不可用（原生库未加载），请使用纯 Java 路径或等待适配");
        }
        final long x2Ptr = getX2Ptr(pointer, x, y, z);
        final int bit = bitIndex(x, y, z);
        byte x2 = UNSAFE.getByte(x2Ptr);

        int b = solid ? 1 : 0;
        x2 |= (b << bit);

        UNSAFE.putByte(x2Ptr, x2);
    }

    // 获取方块是否为实心
    public static boolean getBlock(long pointer, int x, int y, int z) {
        if (!IS_AVAILABLE) {
            throw new UnsupportedOperationException("八叉树加速不可用（原生库未加载），请使用纯 Java 路径或等待适配");
        }
        final long x2Ptr = getX2Ptr(pointer, x, y, z);
        final int bit = bitIndex(x, y, z);
        final byte x2 = UNSAFE.getByte(x2Ptr);
        return ((x2 >> bit) & 1) != 0;
    }
}