package dev.babbaj.pathfinder;

/**
 * 代表一条寻路路径段。
 * 由原生寻路库返回，供 Java 层使用。
 */
public class PathSegment {
    /** 该路径段是否已抵达终点（寻路完成） */
    public final boolean finished;
    /** 打包的路径点数据，格式由原生库定义 */
    public final long[] packed;

    public PathSegment(boolean finished, long[] packed) {
        this.finished = finished;
        this.packed = packed;
    }
}